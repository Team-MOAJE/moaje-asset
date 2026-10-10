package com.moaje.asset.transfer.application.service

import com.moaje.asset.transfer.application.model.ApplyBankingTransferReversedCommand
import com.moaje.asset.transfer.application.model.ApplyBankingTransferFailedCommand
import com.moaje.asset.transfer.application.model.ApplyBankingTransferCompletedCommand

/** Banking 거래 결과를 반영하는 업무를 선언한다. 실제 처리는 TransferServiceImpl이 담당한다. */
interface TransferService {
    fun applyBankingTransferCompleted(command: ApplyBankingTransferCompletedCommand)

    fun applyBankingTransferFailed(command: ApplyBankingTransferFailedCommand)

    fun applyBankingTransferReversed(command: ApplyBankingTransferReversedCommand)
}


