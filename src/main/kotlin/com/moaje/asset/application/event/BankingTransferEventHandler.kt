package com.moaje.asset.application.event

import com.moaje.asset.application.transfer.AssetTransferService
import com.moaje.asset.application.transfer.CompleteTransferCommand
import com.moaje.asset.application.transfer.FailTransferCommand
import org.springframework.stereotype.Component

@Component
class BankingTransferEventHandler(
    private val assetTransferService: AssetTransferService,
) {
    /**
     * Banking 서비스의 송금 성공 이벤트를 Asset 원장 확정 처리로 연결합니다.
     */
    fun handleTransferCompleted(
        transferId: Long,
        externalTransactionId: String,
    ) {
        assetTransferService.complete(
            CompleteTransferCommand(
                transferId = transferId,
                externalTransactionId = externalTransactionId,
            ),
        )
    }

    /**
     * Banking 서비스의 송금 실패 이벤트를 Asset 예약 차감 복원 처리로 연결합니다.
     */
    fun handleTransferFailed(
        transferId: Long,
        reason: String,
    ) {
        assetTransferService.fail(
            FailTransferCommand(
                transferId = transferId,
                reason = reason,
            ),
        )
    }
}

