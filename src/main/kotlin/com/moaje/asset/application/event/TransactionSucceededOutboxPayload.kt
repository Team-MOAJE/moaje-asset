package com.moaje.asset.application.event

import java.math.BigDecimal
import java.time.Instant

data class TransactionSucceededOutboxPayload(
    val eventId: String,
    val transactionId: Long,
    val publicTransactionId: String,
    val userId: Long,
    val accountId: Long,
    val accountToken: String,
    val externalTransactionId: String?,
    val transactionType: String,
    val amount: BigDecimal,
    val currency: String = "KRW",
    val targetToken: String?,
    val balanceAfterTransaction: BigDecimal,
    val succeededAt: Instant,
    val occurredAt: Instant,
)
