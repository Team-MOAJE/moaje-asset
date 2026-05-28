package com.moaje.asset.application.transfer

import java.math.BigDecimal

data class ReserveTransferCommand(
    val userId: Long,
    val accountId: Long,
    val targetToken: String,
    val amount: BigDecimal,
    val idempotencyKey: String,
    val ci: String,
    val userName: String,
    val phoneNumber: String,
    val withdrawalAccountId: String,
    val depositBankCode: String,
    val depositAccountNumber: String,
)

