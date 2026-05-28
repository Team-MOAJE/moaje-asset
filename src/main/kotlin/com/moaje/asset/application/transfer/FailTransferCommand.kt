package com.moaje.asset.application.transfer

data class FailTransferCommand(
    val transferId: Long,
    val reason: String,
)

