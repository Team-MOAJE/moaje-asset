package com.moaje.asset.account.application.usecase

import com.moaje.asset.account.domain.AccountSyncStatus
import com.moaje.asset.transfer.domain.TransactionCategory
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

// 계좌 내 단건 거래내역 조회용 dto
data class AccountTransactionCommand(
    val userId: String,
    val accountId: Long,
    val transactionId: Long,
)

// 거래내역 무한 스크롤 조회용 dto. 최초 요청은 lastTransactionId를 null로 전달한다.
data class AccountTransactionListCommand(
    val userId: String,
    val accountId: Long,
    val lastTransactionId: Long? = null,
)

data class AccountTransactionScrollResult(
    val transactions: List<AccountTransactionItem>,
    val nextCursor: Long?,
    val hasNext: Boolean,
)


// 사용자에게 보여질 조회용 계좌Info(계좌기본정보 + 거래내역List)
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
    val nextCursor: Long? = null,
    val hasNext: Boolean = false,
)


// 사용자에게 보여질 조회용 단건 거래내역
data class AccountTransactionItem(
    val transactionId: Long,
    val publicTransactionId: String,
    val type: TransactionType,
    val amount: BigDecimal,
    val targetAccountId: String?,
    val status: TransactionStatus,
    val merchantName: String?,
    val categoryCode: TransactionCategory,
    val createdAt: LocalDateTime,
)
