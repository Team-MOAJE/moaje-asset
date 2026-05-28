package com.moaje.asset.application.transfer

import java.math.BigDecimal
import java.time.Instant

data class ApplyExternalTransactionCommand(
    val userId: Long,
    val accountId: Long,
    val accountToken: String,
    val externalTransactionId: String,
    val type: String,
    val amount: BigDecimal,
    val targetToken: String?,
    val occurredAt: Instant,
)
