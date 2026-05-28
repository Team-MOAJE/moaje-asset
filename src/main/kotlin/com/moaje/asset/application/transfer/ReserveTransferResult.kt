package com.moaje.asset.application.transfer

data class ReserveTransferResult(
    val transferId: Long,
    val publicTransferId: String,
    val assetTransactionId: Long,
)

