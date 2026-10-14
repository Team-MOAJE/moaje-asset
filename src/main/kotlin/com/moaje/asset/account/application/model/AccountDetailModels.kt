package com.moaje.asset.account.application.model

import com.moaje.asset.account.domain.AccountSyncStatus
import com.moaje.asset.account.domain.AccountStatus
import com.moaje.asset.transfer.domain.TransactionCategory
import com.moaje.asset.transfer.domain.TransactionStatus
import com.moaje.asset.transfer.domain.TransactionType
import java.math.BigDecimal
import java.time.LocalDateTime
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer
import io.swagger.v3.oas.annotations.media.Schema

/**
 * Controller가 계좌 조회를 요청할 때 전달하는 값과 Service가 돌려주는 결과입니다.
 */

data class AccountDetailCommand(
    val userId: String,
    val accountId: Long,
)

data class AccountListItem(
    // DB와 내부 연산은 Long을 유지하고, 앱에 보내는 ID만 문자열로 표현한다.
    @get:JsonSerialize(using = ToStringSerializer::class)
    @get:Schema(type = "string", example = "123456789012345678")
    val accountId: Long,
    val bankCode: String?,
    val bankName: String?,
    val balance: BigDecimal,
    val status: AccountStatus,
    val maskedAccountNumber: String? = null,
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
    @get:JsonSerialize(using = ToStringSerializer::class)
    @get:Schema(type = "string", nullable = true, example = "123456789012345678")
    val nextCursor: Long?,
    val hasNext: Boolean,
)


// 사용자에게 보여질 조회용 계좌Info(계좌기본정보 + 거래내역List)
data class AccountDetailResult(
    @get:JsonSerialize(using = ToStringSerializer::class)
    @get:Schema(type = "string", example = "123456789012345678")
    val accountId: Long,
    val bankCode: String? = null,
    val bankName: String? = null,
    @get:Schema(description = "본인 소유권 확인 후 제공하는 14자리 전체 계좌번호", example = "12345678901234")
    val accountNumber: String? = null,
    val balance: BigDecimal,
    val syncStatus: AccountSyncStatus,
    val refreshing: Boolean,
    val refreshFailed: Boolean,
    val message: String? = null,
    val lastSyncedAt: LocalDateTime?,
    val transactionHistory: List<AccountTransactionItem>,
    @get:JsonSerialize(using = ToStringSerializer::class)
    @get:Schema(type = "string", nullable = true, example = "123456789012345678")
    val nextCursor: Long? = null,
    val hasNext: Boolean = false,
)


// 사용자에게 보여질 조회용 단건 거래내역
data class AccountTransactionItem(
    @get:JsonSerialize(using = ToStringSerializer::class)
    @get:Schema(type = "string", example = "123456789012345678")
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
