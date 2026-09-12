package com.moaje.asset.transfer.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.moaje.asset.account.domain.Account
import com.moaje.asset.account.application.gateway.BankingProjectionTransferStatus
import com.moaje.asset.account.application.gateway.BankingAccountProjectionSnapshot
import com.moaje.asset.account.application.gateway.BankingExternalProjectionTransaction
import com.moaje.asset.account.application.gateway.BankingTransferProjectionState
import com.moaje.asset.account.application.reconciliation.ProjectionRepairResult
import com.moaje.asset.account.persistence.AccountRepository
import com.moaje.asset.common.id.IdGenerator
import com.moaje.asset.outbox.domain.TransactionalOutbox
import com.moaje.asset.outbox.persistence.TransactionalOutboxRepository
import com.moaje.asset.transfer.application.usecase.ApplyBankingTransferCompletedCommand
import com.moaje.asset.transfer.application.usecase.ApplyBankingTransferFailedCommand
import com.moaje.asset.transfer.application.usecase.ApplyBankingTransferReversedCommand
import com.moaje.asset.transfer.application.usecase.TransferUseCase
import com.moaje.asset.transfer.domain.BalanceChangeType
import com.moaje.asset.transfer.domain.ProcessedEvent
import com.moaje.asset.transfer.domain.TransactionHistory
import com.moaje.asset.transfer.domain.TransactionStatus
import com.moaje.asset.transfer.domain.TransactionType
import com.moaje.asset.transfer.messaging.publisher.AssetOutboxKafkaPublisher
import com.moaje.asset.transfer.messaging.publisher.TransactionSucceededOutboxPayload
import com.moaje.asset.transfer.persistence.ProcessedEventRepository
import com.moaje.asset.transfer.persistence.TransactionHistoryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.UUID

@Service
class TransferApplicationService(
    private val accountRepository: AccountRepository,
    private val transactionHistoryRepository: TransactionHistoryRepository,
    private val processedEventRepository: ProcessedEventRepository,
    private val transactionalOutboxRepository: TransactionalOutboxRepository,
    private val idGenerator: IdGenerator,
    private val objectMapper: ObjectMapper,
) : TransferUseCase {
    /**
     * Banking이 확정한 송금 성공 이벤트를 Asset 조회 모델에 반영합니다.
     *
     * Asset은 출금 승인자가 아니므로 잔액 충분 여부를 판단하지 않습니다.
     * Banking/Core Banking 결과를 source of truth로 보고, Asset은 projection을 갱신합니다.
     */
    @Transactional
    override fun applyBankingTransferCompleted(command: ApplyBankingTransferCompletedCommand) {
        if (processedEventRepository.existsById(command.eventId)) {
            return
        }

        val account = accountRepository.findByIdForUpdate(command.accountId)
            ?: throw IllegalArgumentException("계좌를 찾을 수 없습니다.")

        if (processedEventRepository.existsById(command.eventId)) {
            return
        }

        require(account.userId == command.userId) { "사용자의 계좌가 아닙니다." }

        val balanceChangeType = BalanceChangeType.TRANSFER_OUT
        if (linkImportedEffect(command.externalTransactionId, command.bankingTransferId, account,
                command.amount, balanceChangeType)) {
            saveProcessedEvent(command.eventId, BANKING_TRANSFER_COMPLETED, command.bankingTransferId, command.accountId)
            completePendingReversalIfPresent(command.bankingTransferId, account)
            return
        }
        if (transactionHistoryRepository.existsByBankingTransferIdAndAccountIdAndBalanceChangeType(
                bankingTransferId = command.bankingTransferId,
                accountId = command.accountId,
                balanceChangeType = balanceChangeType,
            )
        ) {
            saveProcessedEvent(
                eventId = command.eventId,
                eventType = BANKING_TRANSFER_COMPLETED,
                transferId = command.bankingTransferId,
                accountId = command.accountId,
            )
            completePendingReversalIfPresent(command.bankingTransferId, account)
            return
        }

        account.applyDebit(command.amount)
        account.recordEventApplied()

        val transaction = transactionHistoryRepository.save(
            TransactionHistory(
                accountId = account.id ?: throw IllegalStateException("계좌 ID가 없습니다."),
                bankingTransferId = command.bankingTransferId,
                balanceChangeType = balanceChangeType,
                publicTransferId = idGenerator.nextBankingTransferPublicId(),
                type = TransactionType.TRANSFER,
                amount = command.amount,
                status = TransactionStatus.SUCCESS,
                idempotencyKey = "banking-effect:${command.bankingTransferId}:${command.accountId}:$balanceChangeType",
                externalTransactionId = command.externalTransactionId,
            ),
        )

        saveProcessedEvent(
            eventId = command.eventId,
            eventType = BANKING_TRANSFER_COMPLETED,
            transferId = command.bankingTransferId,
            accountId = command.accountId,
        )
        saveTransactionSucceededOutbox(transaction, account)
        completePendingReversalIfPresent(command.bankingTransferId, account)
    }

    /**
     * Banking이 확정한 송금 실패 이벤트를 Asset 거래 projection에 기록합니다.
     *
     * 실패한 거래는 실제 출금이 아니므로 Asset 잔액을 변경하지 않습니다.
     */
    @Transactional
    override fun applyBankingTransferFailed(command: ApplyBankingTransferFailedCommand) {
        if (processedEventRepository.existsById(command.eventId)) {
            return
        }

        val idempotencyKey = "banking-failed:${command.bankingTransferId}:${command.accountId}"
        if (transactionHistoryRepository.findByIdempotencyKey(idempotencyKey) != null) {
            saveProcessedEvent(
                eventId = command.eventId,
                eventType = BANKING_TRANSFER_FAILED,
                transferId = command.bankingTransferId,
                accountId = command.accountId,
            )
            return
        }

        val account = accountRepository.findByIdForUpdate(command.accountId)
            ?: throw IllegalArgumentException("계좌를 찾을 수 없습니다.")

        if (processedEventRepository.existsById(command.eventId)) {
            return
        }

        if (transactionHistoryRepository.findByIdempotencyKey(idempotencyKey) != null) {
            saveProcessedEvent(
                eventId = command.eventId,
                eventType = BANKING_TRANSFER_FAILED,
                transferId = command.bankingTransferId,
                accountId = command.accountId,
            )
            return
        }

        require(account.userId == command.userId) { "사용자의 계좌가 아닙니다." }

        val transaction = transactionHistoryRepository.save(
            TransactionHistory(
                accountId = account.id ?: throw IllegalStateException("계좌 ID가 없습니다."),
                bankingTransferId = command.bankingTransferId,
                publicTransferId = idGenerator.nextBankingTransferPublicId(),
                type = TransactionType.TRANSFER,
                amount = command.amount,
                status = TransactionStatus.PENDING,
                idempotencyKey = idempotencyKey,
                visibleToUser = false,
            ),
        )

        when (command.failureStatus.uppercase()) {
            "REVERSED", "CANCELED" -> transaction.markCanceled(command.reason)
            "TIMEOUT" -> {
                transaction.markUnknown(command.reason)
                account.markSyncRequired()
            }
            else -> transaction.markFailed(command.reason)
        }

        saveProcessedEvent(
            eventId = command.eventId,
            eventType = BANKING_TRANSFER_FAILED,
            transferId = command.bankingTransferId,
            accountId = command.accountId,
        )
    }

    /**
     * 이미 Mock Banking에서 완료된 보상 결과를 Asset Projection에 반영한다.
     * 원 TRANSFER_OUT이 확인되기 전에 금액을 더하면 Topic 순서 역전 때문에 차감된 적 없는 잔액이 증가할 수 있다.
     * 그래서 원 효과가 없으면 REVERSAL_IN을 PENDING으로 보관하고, Completed 이벤트가 도착할 때 함께 완결한다.
     * 이 메서드는 보상을 승인하거나 실행하지 않으며 외부 Source of Truth의 확정 결과만 투영한다.
     */
    @Transactional
    override fun applyBankingTransferReversed(command: ApplyBankingTransferReversedCommand) {
        if (processedEventRepository.existsById(command.eventId)) {
            return
        }

        val account = accountRepository.findByIdForUpdate(command.accountId)
            ?: throw IllegalArgumentException("계좌를 찾을 수 없습니다.")

        if (processedEventRepository.existsById(command.eventId)) {
            return
        }
        require(account.userId == command.userId) { "사용자의 계좌가 아닙니다." }

        if (linkImportedEffect(command.externalReversalTransactionId, command.bankingTransferId, account,
                command.amount, BalanceChangeType.REVERSAL_IN)) {
            saveProcessedEvent(command.eventId, BANKING_TRANSFER_REVERSED, command.bankingTransferId, command.accountId)
            return
        }

        val existingReversal = transactionHistoryRepository
            .findByBankingTransferIdAndAccountIdAndBalanceChangeType(
                bankingTransferId = command.bankingTransferId,
                accountId = command.accountId,
                balanceChangeType = BalanceChangeType.REVERSAL_IN,
            )
        if (existingReversal != null) {
            saveProcessedEvent(
                eventId = command.eventId,
                eventType = BANKING_TRANSFER_REVERSED,
                transferId = command.bankingTransferId,
                accountId = command.accountId,
            )
            completePendingReversalIfPresent(command.bankingTransferId, account)
            return
        }

        val originalTransferOut = transactionHistoryRepository
            .findByBankingTransferIdAndAccountIdAndBalanceChangeType(
                bankingTransferId = command.bankingTransferId,
                accountId = command.accountId,
                balanceChangeType = BalanceChangeType.TRANSFER_OUT,
            )
        val reversal = transactionHistoryRepository.save(
            TransactionHistory(
                accountId = account.id ?: throw IllegalStateException("계좌 ID가 없습니다."),
                bankingTransferId = command.bankingTransferId,
                balanceChangeType = BalanceChangeType.REVERSAL_IN,
                publicTransferId = idGenerator.nextBankingTransferPublicId(),
                type = TransactionType.REVERSAL,
                amount = command.amount,
                status = TransactionStatus.PENDING,
                idempotencyKey = "banking-effect:${command.bankingTransferId}:${command.accountId}:${BalanceChangeType.REVERSAL_IN}",
                externalTransactionId = command.externalReversalTransactionId,
                reconciliationRequired = originalTransferOut == null,
            ),
        )

        saveProcessedEvent(
            eventId = command.eventId,
            eventType = BANKING_TRANSFER_REVERSED,
            transferId = command.bankingTransferId,
            accountId = command.accountId,
        )

        if (originalTransferOut == null) {
            // 서로 다른 Kafka Topic은 상대 순서를 보장하지 않는다. 원 출금이 없을 때 credit을 미루면 일시적인 잔액 부풀림을 막을 수 있다.
            // 대기 상태가 오래 남는 경우 Completed 유실 가능성이 있으므로 계좌를 SYNC_REQUIRED로 표시해 후속 Asset 대사가 찾을 수 있게 한다.
            account.markSyncRequired()
            return
        }

        applyReversalEffect(reversal, account)
    }

    /**
     * Banking Transaction Journal의 확정 결과로 누락된 Asset 금융 효과를 재구성한다.
     * 계좌 Row Lock과 lease owner를 함께 확인하고, 이미 존재하는 효과는 건너뛰어 늦은 Worker와 중복 실행에 의한 이중 증감을 막는다.
     * REQUESTED/PROCESSING/UNKNOWN은 아직 확정 결과가 아니므로 잔액을 추측하지 않고 다음 대사 대상으로 남긴다.
     */
    @Transactional
    fun repairProjection(
        accountId: Long,
        lockOwner: String,
        states: Collection<BankingTransferProjectionState>,
        snapshot: BankingAccountProjectionSnapshot,
        now: LocalDateTime,
        maxAttempts: Int,
        baseBackoffMs: Long,
        maxBackoffMs: Long,
    ): ProjectionRepairResult {
        val account = accountRepository.findByIdForUpdate(accountId)
            ?: throw IllegalArgumentException("계좌를 찾을 수 없습니다.")
        // Row Lock을 기다리는 동안에도 시간이 흐른다. 호출 직전 시각이 아니라 Lock을 얻은 뒤의 시각으로 검사한다.
        // 이미 끝난 작업 권한으로 늦은 원격 응답을 저장하지 않도록 소유자와 만료를 함께 확인한다.
        check(account.hasValidSyncLease(lockOwner, LocalDateTime.now())) { "Asset Projection 대사 lease가 만료되거나 소유자가 변경되었습니다." }

        var repairedEffects = 0
        states.forEach { state ->
            require(state.accountId == accountId) { "Banking 응답의 accountId가 대사 계좌와 다릅니다." }
            require(state.principalId == account.userId) { "Banking 거래 소유자와 Asset 계좌 소유자가 다릅니다." }
            require(state.currency == "KRW") { "현재 Asset Projection은 KRW 거래만 지원합니다." }
            require(state.amount > java.math.BigDecimal.ZERO) { "대사 거래 금액은 0보다 커야 합니다." }

            repairedEffects += when (state.status) {
                BankingProjectionTransferStatus.SUCCEEDED -> repairSucceededTransfer(account, state)
                BankingProjectionTransferStatus.FAILED -> repairFailedTransfer(account, state)
                BankingProjectionTransferStatus.REVERSED -> repairReversedTransfer(account, state)
                BankingProjectionTransferStatus.REQUESTED,
                BankingProjectionTransferStatus.PROCESSING,
                BankingProjectionTransferStatus.UNKNOWN,
                -> 0
            }
        }

        applyAccountSnapshot(account, snapshot)

        val remaining = transactionHistoryRepository
            .findByAccountIdAndReconciliationRequiredTrueOrderByCreatedAtAsc(accountId)
            .mapNotNull { it.bankingTransferId }
            .distinct()
            .size
        if (remaining == 0) {
            account.recordSyncResolved(now, LocalDateTime.ofInstant(snapshot.asOf, ZoneOffset.UTC))
        } else {
            account.recordSyncFailure(
                reason = "Banking에서 아직 확정되지 않은 거래가 ${remaining}건 남아 있습니다.",
                now = now,
                maxAttempts = maxAttempts,
                baseBackoffMillis = baseBackoffMs,
                maxBackoffMillis = maxBackoffMs,
            )
        }
        return ProjectionRepairResult(repairedEffects, remaining, account.syncRetryExhausted)
    }

    /**
     * 계정계 Snapshot은 현재 잔액의 기준값이고 transactions는 누락된 사용자 조회 이력을 설명하는 증분 자료다.
     * 따라서 이력을 다시 증감 계산하지 않고 잔액을 절대값으로 교체해야 중복·누락 이벤트가 누적 오차를 만들지 않는다.
     * 계좌 Lock과 대사 lease를 잡은 동일 트랜잭션에서 처리해 이력만 저장되거나 잔액만 바뀌는 부분 성공을 막는다.
     */
    private fun applyAccountSnapshot(
        account: Account,
        snapshot: BankingAccountProjectionSnapshot,
    ) {
        val accountId = account.id ?: throw IllegalStateException("계좌 ID가 없습니다.")
        require(snapshot.accountId == accountId) { "Banking Snapshot의 accountId가 대사 계좌와 다릅니다." }
        require(snapshot.principalId == account.userId) { "Banking Snapshot 소유자와 Asset 계좌 소유자가 다릅니다." }
        require(snapshot.currency == "KRW") { "현재 Asset Projection은 KRW 계좌만 지원합니다." }
        require(account.snapshotCursorAt == null || !snapshot.asOf.isBefore(account.snapshotCursorAt!!.toInstant(ZoneOffset.UTC))) {
            "이전에 확인한 범위보다 오래된 스냅샷은 반영할 수 없습니다."
        }
        require(snapshot.transactions.map { it.externalTransactionId }.distinct().size == snapshot.transactions.size) {
            "Banking Snapshot에 같은 externalTransactionId가 중복되어 있습니다."
        }

        account.replaceProjection(snapshot.balance, snapshot.accountStatus)
        snapshot.transactions.forEach { importSnapshotTransaction(account, it, snapshot.asOf) }
    }

    private fun importSnapshotTransaction(
        account: Account,
        external: BankingExternalProjectionTransaction,
        snapshotAsOf: Instant,
    ) {
        require(external.externalTransactionId.isNotBlank()) { "외부 거래 식별자는 비어 있을 수 없습니다." }
        require(external.currency == "KRW") { "현재 Asset Projection은 KRW 거래만 지원합니다." }
        require(external.amount > java.math.BigDecimal.ZERO) { "외부 거래 금액은 0보다 커야 합니다." }

        val accountId = account.id ?: throw IllegalStateException("계좌 ID가 없습니다.")
        transactionHistoryRepository.findByExternalTransactionId(external.externalTransactionId)?.let { existing ->
            require(existing.accountId == accountId) { "외부 거래 ID가 다른 Asset 계좌에 이미 연결되어 있습니다." }
            require(existing.amount.compareTo(external.amount) == 0) { "동일 외부 거래 ID의 금액이 Snapshot과 다릅니다." }
            require(existing.type == external.type.toSnapshotTransactionType()) { "외부 거래 유형이 다릅니다." }
            require(existing.balanceChangeType == null || existing.balanceChangeType!!.name == external.type) {
                "외부 거래의 입출금 방향이 다릅니다."
            }
            require(existing.status in setOf(TransactionStatus.SUCCESS, TransactionStatus.PENDING, TransactionStatus.UNKNOWN)) {
                "계정계 성공 이력과 Asset의 확정 실패 상태가 충돌합니다."
            }
            // 절대 잔액에는 이 금융 효과가 이미 포함되어 있다. PENDING 반전을 그대로 두면
            // 나중에 Completed가 와서 보상 입금을 한 번 더 적용하므로, 이력도 함께 성공으로 확정한다.
            existing.markSuccess(external.externalTransactionId)
            existing.externalOccurredAt = LocalDateTime.ofInstant(external.occurredAt, ZoneOffset.UTC)
            existing.createdAt = existing.externalOccurredAt!!
            existing.externalCompletedAt = external.completedAt?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) }
            existing.externalType = external.type
            saveTransactionSucceededOutbox(existing, account, snapshotAsOf)
            return
        }

        val transaction = transactionHistoryRepository.save(
            TransactionHistory(
                accountId = accountId,
                publicTransferId = idGenerator.nextBankingTransferPublicId(),
                type = external.type.toSnapshotTransactionType(),
                amount = external.amount,
                status = TransactionStatus.SUCCESS,
                idempotencyKey = "external:${external.externalTransactionId}",
                externalTransactionId = external.externalTransactionId,
                externalOccurredAt = LocalDateTime.ofInstant(external.occurredAt, ZoneOffset.UTC),
                externalCompletedAt = external.completedAt?.let { LocalDateTime.ofInstant(it, ZoneOffset.UTC) },
                externalType = external.type,
                recoveredAt = LocalDateTime.now(ZoneOffset.UTC),
                createdAt = LocalDateTime.ofInstant(external.occurredAt, ZoneOffset.UTC),
            ),
        )
        saveTransactionSucceededOutbox(transaction, account, snapshotAsOf)
    }

    private fun String.toSnapshotTransactionType(): TransactionType = when (uppercase()) {
        "TRANSFER_OUT", "TRANSFER_IN" -> TransactionType.TRANSFER
        "WITHDRAWAL", "WITHDRAW" -> TransactionType.WITHDRAW
        "DEPOSIT", "CREDIT" -> TransactionType.DEPOSIT
        "REVERSAL_IN", "REVERSAL_OUT", "REVERSAL" -> TransactionType.REVERSAL
        else -> throw IllegalArgumentException("지원하지 않는 Snapshot 거래 유형입니다. type=$this")
    }

    private fun saveTransactionSucceededOutbox(
        transaction: TransactionHistory,
        account: Account,
        snapshotAsOf: Instant? = null,
    ) {
        // 이벤트를 받은 시각을 실제 거래 시각으로 꾸미지 않는다. 계정계 원시각을 스냅샷에서 확인한 뒤 발행한다.
        // 이 때문에 Work 전달은 정기 조회까지 지연될 수 있지만, 대사로 어제의 거래가 오늘 소비로 집계되는 오류를 피한다.
        if (transaction.type == TransactionType.REVERSAL || transaction.status != TransactionStatus.SUCCESS ||
            transaction.workEventRecorded || transaction.externalOccurredAt == null || snapshotAsOf == null) return
        val transactionId = transaction.id ?: throw IllegalStateException("거래 ID가 없습니다.")
        val accountId = account.id ?: throw IllegalStateException("계좌 ID가 없습니다.")
        val now = Instant.now()

        transactionalOutboxRepository.save(
            TransactionalOutbox(
                aggregateType = AssetOutboxKafkaPublisher.TRANSACTION_SUCCEEDED,
                aggregateId = transactionId,
                payload = objectMapper.writeValueAsString(
                    TransactionSucceededOutboxPayload(
                        eventId = UUID.randomUUID().toString(),
                        transactionId = transactionId,
                        publicTransactionId = transaction.publicTransferId,
                        userId = account.userId,
                        accountId = accountId,
                        externalTransactionId = transaction.externalTransactionId,
                        transactionType = transaction.type.name,
                        amount = transaction.amount,
                        targetToken = transaction.targetToken,
                        balanceAfterTransaction = account.balance,
                        succeededAt = transaction.externalCompletedAt?.toInstant(ZoneOffset.UTC),
                        occurredAt = transaction.externalOccurredAt!!.toInstant(ZoneOffset.UTC),
                        recoveredAt = transaction.recoveredAt?.toInstant(ZoneOffset.UTC),
                        recordedAt = now,
                        externalType = transaction.externalType,
                        timestampSource = "CORE_BANKING",
                        snapshotAsOf = snapshotAsOf,
                    ),
                ),
            ),
        )
        transaction.workEventRecorded = true
    }

    private fun repairSucceededTransfer(
        account: Account,
        state: BankingTransferProjectionState,
    ): Int {
        val repaired = ensureTransferOut(account, state)
        supersedeUnresolvedPlaceholders(account, state.transferId)
        return repaired
    }

    private fun repairFailedTransfer(
        account: Account,
        state: BankingTransferProjectionState,
    ): Int {
        val histories = transactionHistoryRepository.findByBankingTransferIdAndAccountId(state.transferId, account.id!!)
        check(histories.none { it.balanceChangeType != null && it.status == TransactionStatus.SUCCESS }) {
            "이미 반영된 성공 금융 효과를 FAILED 결과로 되돌릴 수 없습니다. transferId=${state.transferId}"
        }
        histories.filter { it.reconciliationRequired }.forEach {
            it.markFailed(state.failureReason ?: state.failureCode ?: "Banking에서 송금 실패를 확인했습니다.")
        }
        return 0
    }

    /**
     * REVERSED는 원 성공을 삭제하는 상태가 아니라 TRANSFER_OUT과 REVERSAL_IN 두 금융 효과가 모두 존재한 결과다.
     * 둘 중 누락된 효과만 생성·적용해 최종 잔액을 0의 순효과로 맞추고, 각각의 외부 거래 ID와 감사 이력을 보존한다.
     */
    private fun repairReversedTransfer(
        account: Account,
        state: BankingTransferProjectionState,
    ): Int {
        var repaired = ensureTransferOut(account, state)
        if (linkImportedEffect(state.externalReversalTransactionId, state.transferId, account, state.amount,
                BalanceChangeType.REVERSAL_IN)) {
            supersedeUnresolvedPlaceholders(account, state.transferId)
            return repaired
        }
        val histories = transactionHistoryRepository.findByBankingTransferIdAndAccountId(state.transferId, account.id!!)
        val existing = histories.firstOrNull { it.balanceChangeType == BalanceChangeType.REVERSAL_IN }
        validateAmount(existing, state)

        if (existing == null) {
            val reversal = transactionHistoryRepository.save(
                TransactionHistory(
                    accountId = account.id!!,
                    bankingTransferId = state.transferId,
                    balanceChangeType = BalanceChangeType.REVERSAL_IN,
                    publicTransferId = idGenerator.nextBankingTransferPublicId(),
                    type = TransactionType.REVERSAL,
                    amount = state.amount,
                    status = TransactionStatus.SUCCESS,
                    idempotencyKey = "banking-effect:${state.transferId}:${account.id}:${BalanceChangeType.REVERSAL_IN}",
                    externalTransactionId = state.externalReversalTransactionId,
                ),
            )
            account.applyCredit(state.amount)
            saveTransactionSucceededOutbox(reversal, account)
            repaired += 1
        } else if (existing.status != TransactionStatus.SUCCESS) {
            existing.confirmBalanceEffect(BalanceChangeType.REVERSAL_IN, state.externalReversalTransactionId)
            account.applyCredit(state.amount)
            saveTransactionSucceededOutbox(existing, account)
            repaired += 1
        }
        supersedeUnresolvedPlaceholders(account, state.transferId)
        return repaired
    }

    private fun ensureTransferOut(
        account: Account,
        state: BankingTransferProjectionState,
    ): Int {
        if (linkImportedEffect(state.externalTransactionId, state.transferId, account, state.amount,
                BalanceChangeType.TRANSFER_OUT)) {
            supersedeUnresolvedPlaceholders(account, state.transferId)
            return 0
        }
        val histories = transactionHistoryRepository.findByBankingTransferIdAndAccountId(state.transferId, account.id!!)
        val existingEffect = histories.firstOrNull { it.balanceChangeType == BalanceChangeType.TRANSFER_OUT }
        validateAmount(existingEffect, state)
        if (existingEffect?.status == TransactionStatus.SUCCESS) return 0

        val transaction = existingEffect
            ?: histories.firstOrNull { it.balanceChangeType == null && it.reconciliationRequired }
            ?: transactionHistoryRepository.save(
                TransactionHistory(
                    accountId = account.id!!,
                    bankingTransferId = state.transferId,
                    publicTransferId = idGenerator.nextBankingTransferPublicId(),
                    type = TransactionType.TRANSFER,
                    amount = state.amount,
                    status = TransactionStatus.PENDING,
                    idempotencyKey = "banking-effect:${state.transferId}:${account.id}:${BalanceChangeType.TRANSFER_OUT}",
                    visibleToUser = false,
                ),
            )
        validateAmount(transaction, state)
        transaction.confirmBalanceEffect(BalanceChangeType.TRANSFER_OUT, state.externalTransactionId)
        transaction.recoveredAt = LocalDateTime.now(ZoneOffset.UTC)
        account.applyDebit(state.amount)
        saveTransactionSucceededOutbox(transaction, account)
        return 1
    }

    private fun supersedeUnresolvedPlaceholders(account: Account, transferId: Long) {
        transactionHistoryRepository.findByBankingTransferIdAndAccountId(transferId, account.id!!)
            .filter { it.reconciliationRequired && it.balanceChangeType == null }
            .forEach { it.markReconciliationSuperseded("확정 금융 효과가 별도 거래내역으로 복구되어 대사 대상에서 제외했습니다.") }
    }

    private fun validateAmount(
        transaction: TransactionHistory?,
        state: BankingTransferProjectionState,
    ) {
        if (transaction != null) {
            check(transaction.amount.compareTo(state.amount) == 0) {
                "Asset 거래 금액과 Banking Journal 금액이 일치하지 않습니다. transferId=${state.transferId}"
            }
        }
    }

    /**
     * Completed가 늦게 도착한 경우 앞서 PENDING으로 보관한 보상 효과를 같은 계좌 Lock 안에서 완결한다.
     * 출금과 보상 입금을 한 DB 트랜잭션에서 연속 적용하므로 중간 상태가 commit되지 않고 최종 순효과는 0이 된다.
     */
    private fun completePendingReversalIfPresent(
        bankingTransferId: Long,
        account: Account,
    ) {
        val reversal = transactionHistoryRepository.findByBankingTransferIdAndAccountIdAndBalanceChangeType(
            bankingTransferId = bankingTransferId,
            accountId = account.id ?: throw IllegalStateException("계좌 ID가 없습니다."),
            balanceChangeType = BalanceChangeType.REVERSAL_IN,
        ) ?: return

        val originalTransferOutExists = transactionHistoryRepository
            .existsByBankingTransferIdAndAccountIdAndBalanceChangeType(
                bankingTransferId = bankingTransferId,
                accountId = account.id ?: throw IllegalStateException("계좌 ID가 없습니다."),
                balanceChangeType = BalanceChangeType.TRANSFER_OUT,
            )
        if (reversal.status == TransactionStatus.PENDING && originalTransferOutExists) {
            applyReversalEffect(reversal, account)
        }
    }

    /**
     * 보상 금융 효과는 원 거래내역을 삭제하는 대신 별도 REVERSAL_IN 거래로 잔액을 되돌린다.
     * 별도 이력을 남겨야 원송금과 보상 시점, 외부 거래 ID를 모두 추적하고 중복 보상도 DB 제약으로 막을 수 있다.
     */
    private fun applyReversalEffect(
        reversal: TransactionHistory,
        account: Account,
    ) {
        account.applyCredit(reversal.amount)
        account.recordEventApplied()
        reversal.markSuccess(reversal.externalTransactionId)
        saveTransactionSucceededOutbox(reversal, account)
    }

    private fun linkImportedEffect(
        externalId: String?, transferId: Long, account: Account,
        amount: java.math.BigDecimal, effect: BalanceChangeType,
    ): Boolean {
        if (externalId.isNullOrBlank()) return false
        val existing = transactionHistoryRepository.findByExternalTransactionId(externalId) ?: return false
        require(existing.accountId == account.id && existing.amount.compareTo(amount) == 0) {
            "같은 외부 거래의 계좌 또는 금액이 다릅니다."
        }
        if (existing.status != TransactionStatus.SUCCESS) return false
        existing.linkBankingEffect(transferId, effect)
        return true
    }

    private fun saveProcessedEvent(
        eventId: String,
        eventType: String,
        transferId: Long?,
        accountId: Long?,
    ) {
        processedEventRepository.save(
            ProcessedEvent(
                eventId = eventId,
                eventType = eventType,
                transferId = transferId,
                accountId = accountId,
            ),
        )
    }

    companion object {
        private const val BANKING_TRANSFER_COMPLETED = "BankingTransferCompletedEvent"
        private const val BANKING_TRANSFER_FAILED = "BankingTransferFailedEvent"
        private const val BANKING_TRANSFER_REVERSED = "BankingTransferReversedEvent"
    }
}
