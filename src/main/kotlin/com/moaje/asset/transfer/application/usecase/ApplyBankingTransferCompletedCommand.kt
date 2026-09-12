package com.moaje.asset.transfer.application.usecase

import java.math.BigDecimal
import java.time.Instant

data class ApplyBankingTransferCompletedCommand(
    val eventId: String,
    val bankingTransferId: Long,
    val userId: String,
    val accountId: Long,
    val amount: BigDecimal,
    val externalTransactionId: String,
    val occurredAt: Instant,
)
