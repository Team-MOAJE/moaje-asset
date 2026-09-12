package com.moaje.asset.transfer.application.usecase

import java.math.BigDecimal
import java.time.Instant

data class ApplyBankingTransferReversedCommand(
    val eventId: String,
    val bankingTransferId: Long,
    val userId: String,
    val accountId: Long,
    val amount: BigDecimal,
    val originalExternalTransactionId: String?,
    val externalReversalTransactionId: String?,
    val reason: String?,
    val occurredAt: Instant,
)
