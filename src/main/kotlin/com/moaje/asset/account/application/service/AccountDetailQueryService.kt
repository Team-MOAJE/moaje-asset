package com.moaje.asset.account.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.moaje.asset.account.persistence.AccountRepository
import com.moaje.asset.account.application.usecase.AccountDetailCommand
import com.moaje.asset.account.application.usecase.AccountDetailResult
import com.moaje.asset.account.application.usecase.AccountTransactionItem
import com.moaje.asset.account.application.usecase.GetAccountDetailUseCase
import com.moaje.asset.account.domain.Account
import com.moaje.asset.account.domain.AccountSyncStatus
import com.moaje.asset.transfer.persistence.TransactionHistoryRepository
import com.moaje.asset.transfer.domain.TransactionHistory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.TimeUnit

@Service
class AccountDetailQueryService(
    private val accountRepository: AccountRepository,
    private val transactionHistoryRepository: TransactionHistoryRepository,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) : GetAccountDetailUseCase {

    @Transactional(readOnly = true)
    override fun getDetail(command: AccountDetailCommand): AccountDetailResult {
        val account = accountRepository.findById(command.accountId).orElse(null)
            ?: throw IllegalArgumentException("계좌를 찾을 수 없습니다.")

        require(account.userId == command.userId) { "사용자의 계좌가 아닙니다." }

        val cached = readCachedDetail(command.accountId)
        if (account.syncStatus == AccountSyncStatus.RECONCILING && cached != null) {
            return cached.copy(
                syncStatus = AccountSyncStatus.RECONCILING,
                refreshing = true,
                refreshFailed = false,
                message = "계좌 정보를 업데이트하고 있습니다.",
            )
        }

        if (account.syncStatus == AccountSyncStatus.STALE && cached != null) {
            return cached.copy(
                syncStatus = AccountSyncStatus.STALE,
                refreshing = false,
                refreshFailed = true,
                message = "지금은 계좌 정보를 새로고침할 수 없습니다.",
            )
        }

        val detail = buildDetail(account)
        cacheDetail(detail)
        return detail
    }

    private fun buildDetail(account: Account): AccountDetailResult {
        val accountId = account.id ?: throw IllegalStateException("계좌 ID가 없습니다.")
        val histories = transactionHistoryRepository
            .findByAccountIdAndVisibleToUserTrueOrderByCreatedAtDesc(accountId)
            .map { it.toItem() }

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
            transactionHistory = histories,
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
            createdAt = createdAt,
        )
    }

    private fun readCachedDetail(accountId: Long): AccountDetailResult? {
        return redisTemplate.opsForValue().get(cacheKey(accountId))
            ?.let {
                runCatching { objectMapper.readValue(it, AccountDetailResult::class.java) }
                    .getOrNull()
            }
    }

    private fun cacheDetail(detail: AccountDetailResult) {
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
}

