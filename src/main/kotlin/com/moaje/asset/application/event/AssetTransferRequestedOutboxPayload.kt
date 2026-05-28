package com.moaje.asset.application.event

import java.math.BigDecimal
import java.time.Instant

data class AssetTransferRequestedOutboxPayload(
    val eventId: String,
    val transferId: Long,
    val assetTransactionId: Long,
    val userId: Long,
    val ci: String,
    val userName: String,
    val phoneNumber: String,
    val withdrawalAccountId: String,
    val depositBankCode: String,
    val depositAccountNumber: String,
    val amount: BigDecimal,
    val currency: String = "KRW",
    val occurredAt: Instant,
)
