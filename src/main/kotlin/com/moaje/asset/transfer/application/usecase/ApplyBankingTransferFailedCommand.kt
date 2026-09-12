package com.moaje.asset.transfer.application.usecase

import java.math.BigDecimal
import java.time.Instant

data class ApplyBankingTransferFailedCommand(
    val eventId: String,
    val bankingTransferId: Long,
    val userId: String,
    val accountId: Long,
    val amount: BigDecimal,
    val reason: String,
    val failureStatus: String,
    val occurredAt: Instant,
)
