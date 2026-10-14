package com.moaje.asset.account.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.moaje.asset.account.application.query.BankingAccountSnapshotQuery
import com.moaje.asset.account.application.query.BankingAccountNumberQuery
import com.moaje.asset.account.exception.AccountNumberUnavailableException
import org.springframework.http.HttpStatus
import com.moaje.asset.account.application.model.AccountDetailCommand
import com.moaje.asset.account.application.model.AccountListItem
import com.moaje.asset.account.application.model.AccountDetailResult
import com.moaje.asset.account.application.model.AccountTransactionCommand
import com.moaje.asset.account.application.model.AccountTransactionItem
import com.moaje.asset.account.application.model.AccountTransactionListCommand
import com.moaje.asset.account.application.model.AccountTransactionScrollResult
import com.moaje.asset.account.domain.Account
import com.moaje.asset.account.domain.AccountSyncStatus
import com.moaje.asset.account.exception.AccountAuthFailedException
import com.moaje.asset.account.exception.AccountRefreshFailedException
import com.moaje.asset.account.exception.InvalidAccountException
import com.moaje.asset.account.exception.NotFoundAccountTransferHistory
import com.moaje.asset.account.persistence.AccountRepository
import com.moaje.asset.transfer.application.service.TransferServiceImpl
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
class AccountDetailServiceImpl(
    private val accountRepository: AccountRepository,
    private val transactionHistoryRepository: TransactionHistoryRepository,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val bankingAccountSnapshotQuery: BankingAccountSnapshotQuery,
    private val transferServiceImpl: TransferServiceImpl,
    private val transactionTemplate: TransactionTemplate,
    private val bankingAccountNumberQuery: BankingAccountNumberQuery,
) : AccountDetailService {

    @Transactional(readOnly = true)
    override fun getAccounts(userId: String): List<AccountListItem> {
        // 요청 본문의 사용자 ID가 아니라 검증된 Principal로 본인 계좌만 조회한다.
        return accountRepository.findByUserId(userId).map { account ->
            AccountListItem(
                accountId = account.id ?: throw InvalidAccountException("계좌 ID가 없습니다."),
                bankCode = account.bankCode,
                bankName = account.bankName,
                balance = account.balance,
                status = account.status,
                maskedAccountNumber = account.maskedAccountNumber,
            )
        }
    }

    /**
     * 소유권을 확인한 조회 모델만 캐시하고, 전체 번호는 원격 확인 후 응답에만 붙인다.
     * 번호를 조회하는 동안 DB 트랜잭션을 열어 두거나 Redis에 원번호를 저장하지 않는다.
     */
    override fun getDetail(command: AccountDetailCommand): AccountDetailResult {
        requireOwnedAccount(command)
        val cached = readCachedDetail(command.accountId)
        val detail = transactionTemplate.execute {
            val account = requireOwnedAccount(command)
            if (cached != null && account.syncStatus in setOf(AccountSyncStatus.RECONCILING, AccountSyncStatus.STALE)) {
                cached.copy(
                    accountNumber = null,
                    syncStatus = account.syncStatus,
                    bankCode = account.bankCode,
                    bankName = account.bankName,
                    refreshing = account.syncStatus == AccountSyncStatus.RECONCILING,
                    refreshFailed = account.syncStatus == AccountSyncStatus.STALE,
                    message = account.syncStatus.toMessage(),
                )
            } else {
                buildDetail(account)
            }
        } ?: throw AccountRefreshFailedException("계좌 상세를 만들 수 없습니다.")
        writeCacheDetail(detail)
        return attachOwnedAccountNumber(detail, command)
    }

    private fun requireOwnedAccount(command: AccountDetailCommand): Account {
        val account = accountRepository.findById(command.accountId).orElseThrow { InvalidAccountException("계좌를 찾을 수 없습니다.") }
        if (account.userId != command.userId) {
            throw AccountAuthFailedException("본인 계좌가 아닙니다.", HttpStatus.BAD_REQUEST)
        }
        return account
    }

    private fun attachOwnedAccountNumber(detail: AccountDetailResult, command: AccountDetailCommand): AccountDetailResult {
        val number = bankingAccountNumberQuery.getOwnedAccountNumber(command.accountId, command.userId)
        if (!number.matches(Regex("[0-9]{14}"))) throw AccountNumberUnavailableException()
        return detail.copy(accountNumber = number)
    }

    override fun getRefreshAccountDetail(command: AccountDetailCommand): AccountDetailResult {
        val account = requireOwnedAccount(command)
        val accountId = account.id ?: throw InvalidAccountException("계좌 ID가 없습니다.")
        val requestedCursor = account.snapshotCursorAt?.toInstant(ZoneOffset.UTC) ?: EPOCH
        val beforeLastEventAppliedAt = account.lastEventAppliedAt // Asset DB 조회 당시 계좌의 이벤트 반영시각

        /*
         * Redis와 Asset DB는 조회용 복제본이므로 Refresh의 기준값으로 사용하지 않는다.
         * Banking을 거쳐 Mock Banking의 현재 잔액·상태·cursor 이후 거래내역을 DB 트랜잭션 밖에서 조회한다.
         */
        val accountSnapshot = bankingAccountSnapshotQuery.getAccountSnapshot(
            accountId,
            requestedCursor,
        )
        require(accountSnapshot.accountId == accountId) { throw InvalidAccountException("요청한 계좌와 Banking 응답 계좌가 다릅니다.") }
        require(command.userId == accountSnapshot.principalId) { throw AccountAuthFailedException("사용자의 계좌가 아닙니다.") }

        /*
         * 원격 조회 중 Kafka 이벤트가 먼저 반영됐다면 방금 받은 Snapshot은 이미 오래된 값일 수 있어서
         * 새 트랜잭션에서 계좌를 다시 잠그고 이벤트 시각과 cursor를 재검증한 뒤 한 번에 반영
         */
        transferServiceImpl.refreshProjectionFromSnapshot(
            accountId = accountId,
            principalId = command.userId,
            observedLastEventAppliedAt = beforeLastEventAppliedAt,
            snapshot = accountSnapshot,
        )

        val savedAccountInfo = transactionTemplate.execute {
            val refreshed = accountRepository.findById(accountId).orElseThrow {
                InvalidAccountException("갱신된 계좌를 찾을 수 없습니다.")
            }
            buildDetail(refreshed)
        } ?: throw AccountRefreshFailedException("계좌 Refresh 결과를 만들 수 없습니다.")

        // DB 반영이 끝난뒤 Redis cache 교체
        writeCacheDetail(savedAccountInfo)

        return attachOwnedAccountNumber(savedAccountInfo, command)
    }

    @Transactional(readOnly = true)
    override fun getTranDetail(command: AccountTransactionCommand): AccountTransactionItem {
        val tranItem = transactionHistoryRepository.findOwnedTransaction(command.transactionId,command.accountId,command.userId)
        return tranItem?.toItem() ?: throw NotFoundAccountTransferHistory("거래를 찾을 수 없습니다.")
    }

    @Transactional(readOnly = true)
    override fun getTranList(command: AccountTransactionListCommand): AccountTransactionScrollResult {
        // 소유권 검증
        val account = accountRepository.findById(command.accountId).orElseThrow {
            AccountAuthFailedException("잘못된 계좌입니다.")
        }
        require(account.userId == command.userId) { throw AccountAuthFailedException("사용자의 계좌가 아닙니다.") }

        return loadTransactionPage(command.accountId, command.lastTransactionId)
    }


    // 계좌 내 거래내역 정보 최신화
    private fun buildDetail(account: Account): AccountDetailResult {
        val accountId = account.id ?: throw InvalidAccountException("계좌 ID가 없습니다.")
        val transactionPage = loadTransactionPage(accountId, null)

        return AccountDetailResult(
            accountId = accountId,
            bankCode = account.bankCode,
            bankName = account.bankName,
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
            transactionId = id ?: throw InvalidAccountException("거래 ID가 없습니다."),
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
            objectMapper.writeValueAsString(detail.copy(accountNumber = null)),
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
