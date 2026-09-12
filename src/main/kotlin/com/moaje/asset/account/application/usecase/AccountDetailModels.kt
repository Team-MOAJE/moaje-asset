package com.moaje.asset.account.application.usecase

import com.moaje.asset.account.domain.AccountSyncStatus
import com.moaje.asset.transfer.domain.TransactionStatus
import com.moaje.asset.transfer.domain.TransactionType
import java.math.BigDecimal
import java.time.LocalDateTime

/**
 * 외부 입력 어댑터에서 Asset 애플리케이션 기능을 호출할 때 사용하는 데이터입니다.
 */
data class AccountDetailCommand(
    val userId: String,
    val accountId: Long,
)

data class AccountDetailResult(
    val accountId: Long,
    val bankCode: String? = null,
    val bankName: String? = null,
    val displayAccountNumber: String,
    val maskedAccountNumber: String,
    val balance: BigDecimal,
    val syncStatus: AccountSyncStatus,
    val refreshing: Boolean,
    val refreshFailed: Boolean,
    val message: String? = null,
    val lastSyncedAt: LocalDateTime?,
    val transactionHistory: List<AccountTransactionItem>,
)

data class AccountTransactionItem(
    val transactionId: Long,
    val publicTransactionId: String,
    val type: TransactionType,
    val amount: BigDecimal,
    val targetAccountId: String?,
    val status: TransactionStatus,
    val createdAt: LocalDateTime,
)

