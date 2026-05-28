package com.moaje.asset.application.transfer

data class CompleteTransferCommand(
    val transferId: Long,
    val externalTransactionId: String,
)

