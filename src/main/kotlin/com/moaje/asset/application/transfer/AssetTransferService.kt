package com.moaje.asset.application.transfer

interface AssetTransferService {
    fun reserve(command: ReserveTransferCommand): ReserveTransferResult

    fun complete(command: CompleteTransferCommand)

    fun fail(command: FailTransferCommand)

    fun applyExternalTransaction(command: ApplyExternalTransactionCommand)
}

