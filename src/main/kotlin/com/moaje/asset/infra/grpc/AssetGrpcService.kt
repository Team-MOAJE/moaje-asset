package com.moaje.asset.infra.grpc

import com.moaje.asset.application.cashflow.DailyCashflowCommand
import com.moaje.asset.application.cashflow.DailyCashflowService
import com.moaje.asset.application.grpc.AssetTransferGrpcFacade
import com.moaje.asset.application.transfer.ApplyExternalTransactionCommand
import com.moaje.asset.application.transfer.CompleteTransferCommand
import com.moaje.asset.application.transfer.FailTransferCommand
import com.moaje.asset.application.transfer.ReserveTransferCommand
import com.moaje.common.Money as ProtoMoney
import com.moaje.grpc.asset.ApplyExternalTransactionRequest
import com.moaje.grpc.asset.AssetServiceGrpc
import com.moaje.grpc.asset.CompleteTransferRequest
import com.moaje.grpc.asset.FailTransferRequest
import com.moaje.grpc.asset.GetDailyCashflowRequest
import com.moaje.grpc.asset.GetDailyCashflowResponse
import com.moaje.grpc.asset.ReserveTransferRequest
import com.moaje.grpc.asset.ReserveTransferResponse
import com.moaje.grpc.asset.TransferStateResponse
import io.grpc.Status
import io.grpc.stub.StreamObserver
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

@Component
class AssetGrpcService(
    private val assetTransferGrpcFacade: AssetTransferGrpcFacade,
    private val dailyCashflowService: DailyCashflowService,
) : AssetServiceGrpc.AssetServiceImplBase() {
    /**
     * Banking 서비스의 송금 가승인 gRPC 요청을 받아 잔액 예약과 outbox 기록을 수행합니다.
     */
    override fun reserveTransfer(
        request: ReserveTransferRequest,
        responseObserver: StreamObserver<ReserveTransferResponse>,
    ) {
        handle(responseObserver) {
            val result = assetTransferGrpcFacade.reserveTransfer(
                ReserveTransferCommand(
                    userId = request.userId,
                    accountId = request.accountId,
                    targetToken = request.targetToken,
                    amount = BigDecimal.valueOf(request.amount.amount),
                    idempotencyKey = request.idempotency.key,
                    ci = request.ci,
                    userName = request.userName,
                    phoneNumber = request.phoneNumber,
                    withdrawalAccountId = request.withdrawalAccountId,
                    depositBankCode = request.depositBankCode,
                    depositAccountNumber = request.depositAccountNumber,
                ),
            )

            ReserveTransferResponse.newBuilder()
                .setTransferId(result.transferId)
                .setPublicTransferId(result.publicTransferId)
                .setAssetTransactionId(result.assetTransactionId)
                .build()
        }
    }

    /**
     * 송금 성공 확정 gRPC 요청을 받아 거래 상태를 SUCCESS로 변경합니다.
     */
    override fun completeTransfer(
        request: CompleteTransferRequest,
        responseObserver: StreamObserver<TransferStateResponse>,
    ) {
        handle(responseObserver) {
            assetTransferGrpcFacade.completeTransfer(
                CompleteTransferCommand(
                    transferId = request.transferId,
                    externalTransactionId = request.externalTransactionId,
                ),
            )
            TransferStateResponse.newBuilder()
                .setSuccess(true)
                .setMessage("송금 성공 처리가 완료되었습니다.")
                .build()
        }
    }

    /**
     * 송금 실패 gRPC 요청을 받아 예약 차감액 복원과 FAILED 상태 변경을 수행합니다.
     */
    override fun failTransfer(
        request: FailTransferRequest,
        responseObserver: StreamObserver<TransferStateResponse>,
    ) {
        handle(responseObserver) {
            assetTransferGrpcFacade.failTransfer(
                FailTransferCommand(
                    transferId = request.transferId,
                    reason = request.reason,
                ),
            )
            TransferStateResponse.newBuilder()
                .setSuccess(true)
                .setMessage("송금 실패 보상 처리가 완료되었습니다.")
                .build()
        }
    }

    /**
     * 외부 금융망에서 조회된 신규 거래 내역을 Asset 원장에 멱등하게 반영합니다.
     */
    override fun applyExternalTransaction(
        request: ApplyExternalTransactionRequest,
        responseObserver: StreamObserver<TransferStateResponse>,
    ) {
        handle(responseObserver) {
            assetTransferGrpcFacade.applyExternalTransaction(
                ApplyExternalTransactionCommand(
                    userId = request.userId,
                    accountId = request.accountId,
                    accountToken = request.accountToken,
                    externalTransactionId = request.externalTransactionId,
                    type = request.type,
                    amount = BigDecimal.valueOf(request.amount.amount),
                    targetToken = request.targetToken.takeIf { it.isNotBlank() },
                    occurredAt = Instant.parse(request.occurredAt),
                ),
            )

            TransferStateResponse.newBuilder()
                .setSuccess(true)
                .setMessage("외부 금융망 거래 내역 반영이 완료되었습니다.")
                .build()
        }
    }

    /**
     * 메인 화면에서 사용할 Daily 가용 생활비 산출 요청을 gRPC로 처리합니다.
     */
    override fun getDailyCashflow(
        request: GetDailyCashflowRequest,
        responseObserver: StreamObserver<GetDailyCashflowResponse>,
    ) {
        handle(responseObserver) {
            val result = dailyCashflowService.calculate(
                DailyCashflowCommand(
                    userId = request.userId,
                    expectedIncome = BigDecimal.valueOf(request.expectedIncome.amount),
                    fixedExpenses = BigDecimal.valueOf(request.fixedExpenses.amount),
                    eventBuffer = BigDecimal.valueOf(request.eventBuffer.amount),
                    daysUntilNextPayday = request.daysUntilNextPayday,
                    snapshotDate = request.snapshotDate.takeIf { it.isNotBlank() }
                        ?.let(LocalDate::parse)
                        ?: LocalDate.now(),
                    forceRefresh = request.forceRefresh,
                ),
            )

            GetDailyCashflowResponse.newBuilder()
                .setUserId(result.userId)
                .setCurrentBalance(result.currentBalance.toProtoMoney())
                .setExpectedIncome(result.expectedIncome.toProtoMoney())
                .setFixedExpenses(result.fixedExpenses.toProtoMoney())
                .setEventBuffer(result.eventBuffer.toProtoMoney())
                .setDaysUntilNextPayday(result.daysUntilNextPayday)
                .setDailyLimit(result.dailyLimit.toProtoMoney())
                .setSnapshotDate(result.snapshotDate.toString())
                .build()
        }
    }

    /**
     * Asset 내부 금액 타입을 protobuf Money 메시지로 변환합니다.
     */
    private fun BigDecimal.toProtoMoney(currency: String = "KRW"): ProtoMoney {
        return ProtoMoney.newBuilder()
            .setAmount(toLong())
            .setCurrency(currency)
            .build()
    }

    /**
     * gRPC 처리 공통 예외 변환과 응답 완료 처리를 담당합니다.
     */
    private fun <T> handle(
        responseObserver: StreamObserver<T>,
        block: () -> T,
    ) {
        runCatching(block)
            .onSuccess { response ->
                responseObserver.onNext(response)
                responseObserver.onCompleted()
            }
            .onFailure { exception ->
                responseObserver.onError(exception.toGrpcStatus().asRuntimeException())
            }
    }

    /**
     * 도메인 예외를 gRPC 상태 코드로 변환합니다.
     */
    private fun Throwable.toGrpcStatus(): Status {
        return when (this) {
            is IllegalArgumentException -> Status.INVALID_ARGUMENT.withDescription(message)
            is IllegalStateException -> Status.FAILED_PRECONDITION.withDescription(message)
            else -> Status.INTERNAL.withDescription(message)
        }.withCause(this)
    }
}
