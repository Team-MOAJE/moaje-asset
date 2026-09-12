package com.moaje.asset.account.application.gateway

import java.math.BigDecimal
import java.time.Instant

enum class BankingProjectionTransferStatus {
    REQUESTED,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    UNKNOWN,
    REVERSED,
}

data class BankingTransferProjectionState(
    val transferId: Long,
    val principalId: String,
    val accountId: Long,
    val amount: BigDecimal,
    val currency: String,
    val status: BankingProjectionTransferStatus,
    val externalTransactionId: String?,
    val externalReversalTransactionId: String?,
    val failureCode: String?,
    val failureReason: String?,
    val reversalReason: String?,
)

interface BankingTransferProjectionGateway {
    fun getTransferStates(accountId: Long, transferIds: Collection<Long>): List<BankingTransferProjectionState>
}

data class BankingAccountProjectionSnapshot(
    val accountId: Long,
    val principalId: String,
    val balance: BigDecimal,
    val currency: String,
    val accountStatus: String,
    val asOf: Instant,
    val transactions: List<BankingExternalProjectionTransaction>,
)

data class BankingExternalProjectionTransaction(
    val externalTransactionId: String,
    val type: String,
    val amount: BigDecimal,
    val currency: String,
    val occurredAt: Instant,
    val completedAt: Instant? = null,
)

interface BankingAccountProjectionGateway {
    fun getAccountSnapshot(accountId: Long, cursor: Instant): BankingAccountProjectionSnapshot
}
