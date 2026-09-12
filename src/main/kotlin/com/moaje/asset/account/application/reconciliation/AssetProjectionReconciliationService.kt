package com.moaje.asset.account.application.reconciliation

import com.moaje.asset.account.application.gateway.BankingAccountProjectionGateway
import com.moaje.asset.account.application.gateway.BankingTransferProjectionGateway
import com.moaje.asset.account.persistence.AccountRepository
import com.moaje.asset.transfer.application.service.TransferApplicationService
import com.moaje.asset.transfer.persistence.TransactionHistoryRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

@Service
class AssetProjectionReconciliationService(
    private val accountRepository: AccountRepository,
    private val transactionHistoryRepository: TransactionHistoryRepository,
    private val bankingGateway: BankingTransferProjectionGateway,
    private val accountProjectionGateway: BankingAccountProjectionGateway,
    private val transferApplicationService: TransferApplicationService,
    private val properties: AssetProjectionReconciliationProperties,
    private val metrics: AssetProjectionReconciliationMetrics,
    private val transactionTemplate: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 후보 조회와 lease 선점만 짧은 DB 트랜잭션에서 수행하고 Banking gRPC 호출은 트랜잭션 밖에서 실행한다.
     * 원격 응답을 기다리는 동안 DB Connection과 계좌 Row Lock을 점유하지 않아 다른 정상 조회·소비 흐름을 막지 않는다.
     */
    fun reconcileBatch(): AssetProjectionReconciliationResult {
        val now = LocalDateTime.now()
        val auditBefore = now.minus(Duration.ofMillis(properties.auditIntervalMs.coerceAtLeast(1)))
        val accountIds = accountRepository.findProjectionReconciliationCandidateIds(
            now,
            auditBefore,
            PageRequest.of(0, properties.batchSize.coerceAtLeast(1)),
        )
        var claimed = 0
        var resolved = 0
        var failed = 0
        var repairedEffects = 0

        accountIds.forEach { accountId ->
            // 계좌마다 새 작업 번호와 현재 시각을 사용한다. 앞 계좌의 원격 호출 때문에 다음 lease가 이미 늙어 있으면 안 된다.
            // 같은 서버의 이전 작업도 다른 작업으로 구분해야, 늦게 돌아온 응답이 새 작업의 권한을 빌려 쓰지 못한다.
            val lockOwner = UUID.randomUUID().toString()
            if (!claim(accountId, LocalDateTime.now(), lockOwner)) return@forEach
            claimed += 1
            metrics.recordClaimed()

            val cursor = accountRepository.findById(accountId).orElseThrow {
                IllegalArgumentException("대사할 Asset 계좌를 찾을 수 없습니다. accountId=$accountId")
            }.snapshotCursorAt?.toInstant(ZoneOffset.UTC) ?: java.time.Instant.EPOCH

            val transferIds = transactionHistoryRepository
                .findByAccountIdAndReconciliationRequiredTrueOrderByCreatedAtAsc(accountId)
                .mapNotNull { it.bankingTransferId }
                .distinct()

            try {
                // transferId가 없는 외부 입출금 또는 역직렬화 DLT도 계좌 Snapshot 비교로 복구할 수 있다.
                // 두 원격 조회를 모두 끝낸 뒤 하나의 Asset DB 트랜잭션에서 반영해 부분 복구 상태를 commit하지 않는다.
                val snapshot = accountProjectionGateway.getAccountSnapshot(accountId, cursor)
                val states = if (transferIds.isEmpty()) emptyList() else bankingGateway.getTransferStates(accountId, transferIds)
                val returnedIds = states.map { it.transferId }
                require(returnedIds.size == returnedIds.distinct().size && returnedIds.toSet() == transferIds.toSet()) {
                    "Banking 대사 응답의 transferId 집합이 요청과 일치하지 않습니다."
                }
                val result = transferApplicationService.repairProjection(
                    accountId = accountId,
                    lockOwner = lockOwner,
                    states = states,
                    snapshot = snapshot,
                    now = LocalDateTime.now(),
                    maxAttempts = properties.maxAttempts,
                    baseBackoffMs = properties.baseBackoffMs,
                    maxBackoffMs = properties.maxBackoffMs,
                )
                repairedEffects += result.repairedEffects
                if (result.remainingTransfers == 0) {
                    resolved += 1
                    metrics.recordResolved(result.repairedEffects)
                } else {
                    failed += 1
                    metrics.recordFailed(result.retryExhausted)
                }
            } catch (exception: Exception) {
                log.warn("Asset projection reconciliation lookup failed. accountId={}", accountId, exception)
                val exhausted = recordFailure(accountId, exception.rootMessage(), lockOwner)
                metrics.recordFailed(exhausted)
                failed += 1
            }
        }

        return AssetProjectionReconciliationResult(accountIds.size, claimed, resolved, failed, repairedEffects)
    }

    private fun claim(accountId: Long, now: LocalDateTime, lockOwner: String): Boolean {
        val lockedUntil = now.plus(Duration.ofMillis(properties.leaseDurationMs.coerceAtLeast(1)))
        val auditBefore = now.minus(Duration.ofMillis(properties.auditIntervalMs.coerceAtLeast(1)))
        return transactionTemplate.execute {
            accountRepository.claimProjectionReconciliation(accountId, now, auditBefore, lockedUntil, lockOwner) == 1
        } ?: false
    }

    private fun recordFailure(accountId: Long, reason: String, lockOwner: String): Boolean {
        return transactionTemplate.execute {
            val account = accountRepository.findByIdForUpdate(accountId) ?: return@execute false
            if (!account.hasValidSyncLease(lockOwner, LocalDateTime.now())) return@execute false
            val wasExhausted = account.syncRetryExhausted
            account.recordSyncFailure(
                reason = reason,
                now = LocalDateTime.now(),
                maxAttempts = properties.maxAttempts,
                baseBackoffMillis = properties.baseBackoffMs,
                maxBackoffMillis = properties.maxBackoffMs,
            )
            !wasExhausted && account.syncRetryExhausted
        } ?: false
    }

    private fun Exception.rootMessage(): String {
        val root = generateSequence(this as Throwable) { it.cause }.last()
        return root.message ?: root::class.java.simpleName
    }

}
