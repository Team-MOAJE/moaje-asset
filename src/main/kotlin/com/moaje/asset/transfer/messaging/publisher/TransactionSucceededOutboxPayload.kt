package com.moaje.asset.transfer.messaging.publisher

import java.math.BigDecimal
import java.time.Instant

data class TransactionSucceededOutboxPayload(
    val eventId: String,
    val transactionId: Long,
    val publicTransactionId: String,
    val userId: String,
    val accountId: Long,
    val externalTransactionId: String?,
    val transactionType: String,
    val amount: BigDecimal,
    val currency: String = "KRW",
    val targetToken: String?,
    val balanceAfterTransaction: BigDecimal,
    val succeededAt: Instant?,
    val occurredAt: Instant,
    val recoveredAt: Instant? = null,
    val recordedAt: Instant? = null,
    val externalType: String? = null,
    val timestampSource: String = "LEGACY_UNVERIFIED",
    val snapshotAsOf: Instant? = null,
)

