package com.moaje.asset.transfer.application.service

import com.moaje.asset.transfer.application.model.ApplyBankingTransferCompletedCommand
import com.moaje.asset.transfer.application.model.ApplyBankingTransferFailedCommand
import com.moaje.asset.transfer.application.model.ApplyBankingTransferReversedCommand
import org.springframework.stereotype.Component

@Component
class BankingTransferEventHandler(
    private val transferService: TransferService,
) {
    /**
     * Banking 서비스의 송금 성공 이벤트를 Asset projection 갱신으로 연결합니다.
     */
    fun handleTransferCompleted(command: ApplyBankingTransferCompletedCommand) {
        transferService.applyBankingTransferCompleted(command)
    }

    /**
     * Banking 서비스의 송금 실패 이벤트를 Asset projection 갱신으로 연결합니다.
     */
    fun handleTransferFailed(command: ApplyBankingTransferFailedCommand) {
        transferService.applyBankingTransferFailed(command)
    }

    /**
     * 이미 외부 계정계에서 끝난 보상 결과를 Asset의 반대 금융 효과로 연결합니다.
     * 이 호출은 보상을 승인하거나 실행하지 않으며, Source of Truth의 확정 결과만 Projection에 반영합니다.
     */
    fun handleTransferReversed(command: ApplyBankingTransferReversedCommand) {
        transferService.applyBankingTransferReversed(command)
    }
}
