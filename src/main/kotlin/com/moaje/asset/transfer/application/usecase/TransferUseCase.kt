package com.moaje.asset.transfer.application.usecase

interface TransferUseCase {
    fun applyBankingTransferCompleted(command: ApplyBankingTransferCompletedCommand)

    fun applyBankingTransferFailed(command: ApplyBankingTransferFailedCommand)

    fun applyBankingTransferReversed(command: ApplyBankingTransferReversedCommand)
}


