package com.moaje.asset.account.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.moaje.asset.account.application.gateway.BankingAccountProjectionGateway
import com.moaje.asset.account.application.usecase.AccountDetailCommand
import com.moaje.asset.account.application.usecase.AccountDetailResult
import com.moaje.asset.account.application.usecase.AccountTransactionCommand
import com.moaje.asset.account.application.usecase.AccountTransactionItem
import com.moaje.asset.account.application.usecase.AccountTransactionListCommand
import com.moaje.asset.account.application.usecase.AccountTransactionScrollResult
import com.moaje.asset.account.application.usecase.GetAccountDetailUseCase
import com.moaje.asset.account.domain.Account
import com.moaje.asset.account.domain.AccountSyncStatus
import com.moaje.asset.account.persistence.AccountRepository
import com.moaje.asset.transfer.application.service.TransferApplicationService
import com.moaje.asset.transfer.domain.TransactionHistory
import com.moaje.asset.transfer.persistence.TransactionHistoryRepository
import org.springframework.data.domain.PageRequest
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.time.Instant.EPOCH
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

@Service
class AccountDetailQueryService(
    private val accountRepository: AccountRepository,
    private val transactionHistoryRepository: TransactionHistoryRepository,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val bankingGateway: BankingAccountProjectionGateway,
    private val transferApplicationService: TransferApplicationService,
    private val transactionTemplate: TransactionTemplate,
) : GetAccountDetailUseCase {

    /**
     * 계좌정보를 조회한다.
     */
    @Transactional(readOnly = true)
    override fun getDetail(command: AccountDetailCommand): AccountDetailResult {
        val account = accountRepository.findById(command.accountId).orElse(null)
            ?: throw IllegalArgumentException("계좌를 찾을 수 없습니다.")

        require(account.userId == command.userId) { "사용자의 계좌가 아닙니다." }

        val cached = readCachedDetail(command.accountId) // redis에서 계좌데이터를 가져온다.
        if (account.syncStatus == AccountSyncStatus.RECONCILING && cached != null) { // 만약 계좌상태가 대사중이거나 캐시가 널이 아니라면, 계좌업데이트 중으로 반환
            return cached.copy(
                syncStatus = AccountSyncStatus.RECONCILING,
                refreshing = true,
                refreshFailed = false,
                message = "계좌 정보를 업데이트하고 있습니다.",
            )
        }

        if (account.syncStatus == AccountSyncStatus.STALE && cached != null) { // 계좌정보가 오래된 상태(STALE)라면, 새로고침 불가로 반환
            return cached.copy(
                syncStatus = AccountSyncStatus.STALE,
                refreshing = false,
                refreshFailed = true,
                message = "지금은 계좌 정보를 새로고침할 수 없습니다.",
            )
        }
        val detail = buildDetail(account)
        writeCacheDetail(detail)
        return detail
    }

    override fun getRefreshAccountDetail(command: AccountDetailCommand): AccountDetailResult {
        val account = accountRepository.findById(command.accountId).orElseThrow {
            IllegalArgumentException("계좌를 찾을 수 없습니다.")
        }

        require(account.userId == command.userId) { "사용자의 계좌가 아닙니다." }
        val accountId = account.id ?: throw IllegalStateException("계좌 ID가 없습니다.")
        val requestedCursor = account.snapshotCursorAt?.toInstant(ZoneOffset.UTC) ?: EPOCH
        val beforeLastEventAppliedAt = account.lastEventAppliedAt // Asset DB 조회 당시 계좌의 이벤트 반영시각

        /*
         * Redis와 Asset DB는 조회용 복제본이므로 Refresh의 기준값으로 사용하지 않는다.
         * Banking을 거쳐 Mock Banking의 현재 잔액·상태·cursor 이후 거래내역을 DB 트랜잭션 밖에서 조회한다.
         */
        val accountSnapshot = bankingGateway.getAccountSnapshot(
            accountId,
            requestedCursor,
        )
        require(accountSnapshot.accountId == accountId) { "요청한 계좌와 Banking 응답 계좌가 다릅니다." }
        require(command.userId == accountSnapshot.principalId) { "사용자의 계좌가 아닙니다." }

        /*
         * 원격 조회 중 Kafka 이벤트가 먼저 반영됐다면 방금 받은 Snapshot은 이미 오래된 값일 수 있어서
         * 새 트랜잭션에서 계좌를 다시 잠그고 이벤트 시각과 cursor를 재검증한 뒤 한 번에 반영
         */
        transferApplicationService.refreshProjectionFromSnapshot(
            accountId = accountId,
            principalId = command.userId,
            observedLastEventAppliedAt = beforeLastEventAppliedAt,
            snapshot = accountSnapshot,
        )

        val savedAccountInfo = transactionTemplate.execute {
            val refreshed = accountRepository.findById(accountId).orElseThrow {
                IllegalArgumentException("갱신된 계좌를 찾을 수 없습니다.")
            }
            buildDetail(refreshed)
        } ?: throw IllegalStateException("계좌 Refresh 결과를 만들 수 없습니다.")

        // DB 반영이 끝난뒤 Redis cache 교체
        writeCacheDetail(savedAccountInfo)

        return savedAccountInfo
    }

    @Transactional(readOnly = true)
    override fun getTranDetail(command: AccountTransactionCommand): AccountTransactionItem {
        val tranItem = transactionHistoryRepository.findOwnedTransaction(command.transactionId,command.accountId,command.userId)
        return tranItem?.toItem() ?: throw IllegalArgumentException("거래를 찾을 수 없습니다.")
    }

    @Transactional(readOnly = true)
    override fun getTranList(command: AccountTransactionListCommand): AccountTransactionScrollResult {
        // 소유권 검증
        val account = accountRepository.findById(command.accountId).orElseThrow {
            IllegalArgumentException("잘못된 계좌입니다.")
        }
        require(account.userId == command.userId) { "사용자의 계좌가 아닙니다." }

        return loadTransactionPage(command.accountId, command.lastTransactionId)
    }


    // 계좌 내 거래내역 정보 최신화
    private fun buildDetail(account: Account): AccountDetailResult {
        val accountId = account.id ?: throw IllegalStateException("계좌 ID가 없습니다.")
        val transactionPage = loadTransactionPage(accountId, null)

        return AccountDetailResult(
            accountId = accountId,
            displayAccountNumber = "ACCOUNT-$accountId",
            maskedAccountNumber = "ACCOUNT-$accountId",
            balance = account.balance,
            syncStatus = account.syncStatus,
            refreshing = account.syncStatus == AccountSyncStatus.RECONCILING,
            refreshFailed = account.syncStatus == AccountSyncStatus.STALE,
            message = account.syncStatus.toMessage(),
            lastSyncedAt = account.lastSyncedAt,
            transactionHistory = transactionPage.transactions,
            nextCursor = transactionPage.nextCursor,
            hasNext = transactionPage.hasNext,
        )
    }

    /**
     * 다음 페이지 존재 여부를 확인하기 위해 화면에 보여줄 50건보다 한 건 더 조회한다.
     * 51번째 거래는 응답에서 제외하고, 다음 요청에 사용할 커서는 50번째 거래 ID로 만든다.
     */
    private fun loadTransactionPage(
        accountId: Long,
        lastTransactionId: Long?,
    ): AccountTransactionScrollResult {
        val histories = transactionHistoryRepository.findVisibleTransactionsByCursor(
            accountId = accountId,
            lastTransactionId = lastTransactionId,
            pageable = PageRequest.of(0, TRANSACTION_PAGE_SIZE + 1),
        )
        val hasNext = histories.size > TRANSACTION_PAGE_SIZE
        val transactions = histories.take(TRANSACTION_PAGE_SIZE).map { it.toItem() }

        return AccountTransactionScrollResult(
            transactions = transactions,
            nextCursor = if (hasNext) transactions.last().transactionId else null,
            hasNext = hasNext,
        )
    }

    private fun TransactionHistory.toItem(): AccountTransactionItem {
        return AccountTransactionItem(
            transactionId = id ?: throw IllegalStateException("거래 ID가 없습니다."),
            publicTransactionId = publicTransferId,
            type = type,
            amount = amount,
            targetAccountId = targetToken,
            status = status,
            merchantName = merchantName,
            categoryCode = categoryCode,
            createdAt = externalOccurredAt ?: createdAt,
        )
    }

    private fun readCachedDetail(accountId: Long): AccountDetailResult? {
        return redisTemplate.opsForValue().get(cacheKey(accountId))
            ?.let {
                runCatching { objectMapper.readValue(it, AccountDetailResult::class.java) }
                    .getOrNull()
            }
    }

    private fun writeCacheDetail(detail: AccountDetailResult) {
        redisTemplate.opsForValue().set(
            cacheKey(detail.accountId),
            objectMapper.writeValueAsString(detail),
            1,
            TimeUnit.DAYS,
        )
    }

    private fun AccountSyncStatus.toMessage(): String? {
        return when (this) {
            AccountSyncStatus.SYNCED -> null
            AccountSyncStatus.RECONCILING -> "계좌 정보를 업데이트하고 있습니다."
            AccountSyncStatus.STALE -> "지금은 계좌 정보를 새로고침할 수 없습니다."
            AccountSyncStatus.SYNC_REQUIRED -> "계좌 정보를 최신 상태로 확인해야 합니다."
        }
    }

    private fun cacheKey(accountId: Long): String = "asset:account-detail:$accountId"

    private companion object {
        const val TRANSACTION_PAGE_SIZE = 50
    }
}
