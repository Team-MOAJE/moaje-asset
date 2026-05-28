package com.moaje.asset.application.grpc

import com.moaje.asset.application.transfer.AssetTransferService
import com.moaje.asset.application.transfer.ApplyExternalTransactionCommand
import com.moaje.asset.application.transfer.CompleteTransferCommand
import com.moaje.asset.application.transfer.FailTransferCommand
import com.moaje.asset.application.transfer.ReserveTransferCommand
import com.moaje.asset.application.transfer.ReserveTransferResult
import org.springframework.stereotype.Component

@Component
class AssetTransferGrpcFacade(
    private val assetTransferService: AssetTransferService,
) {
    /**
     * gRPC로 전달된 송금 가승인 요청을 Asset 애플리케이션 서비스로 위임합니다.
     */
    fun reserveTransfer(command: ReserveTransferCommand): ReserveTransferResult {
        return assetTransferService.reserve(command)
    }

    /**
     * gRPC로 전달된 송금 성공 확정 요청을 Asset 애플리케이션 서비스로 위임합니다.
     */
    fun completeTransfer(command: CompleteTransferCommand) {
        assetTransferService.complete(command)
    }

    /**
     * gRPC로 전달된 송금 실패 보상 요청을 Asset 애플리케이션 서비스로 위임합니다.
     */
    fun failTransfer(command: FailTransferCommand) {
        assetTransferService.fail(command)
    }

    /**
     * gRPC로 전달된 외부 금융망 신규 거래 내역을 Asset 애플리케이션 서비스로 위임합니다.
     */
    fun applyExternalTransaction(command: ApplyExternalTransactionCommand) {
        assetTransferService.applyExternalTransaction(command)
    }
}

