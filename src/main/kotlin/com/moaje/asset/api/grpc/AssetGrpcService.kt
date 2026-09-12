package com.moaje.asset.api.grpc

import com.moaje.asset.cashflow.application.usecase.CalculateDailyCashflowUseCase
import com.moaje.asset.cashflow.application.usecase.DailyCashflowCommand
import com.moaje.common.Money as ProtoMoney
import com.moaje.grpc.asset.AssetServiceGrpc
import com.moaje.grpc.asset.GetDailyCashflowRequest
import com.moaje.grpc.asset.GetDailyCashflowResponse
import io.grpc.Status
import io.grpc.stub.StreamObserver
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate

@Component
class AssetGrpcService(
    private val calculateDailyCashflowUseCase: CalculateDailyCashflowUseCase,
) : AssetServiceGrpc.AssetServiceImplBase() {
    override fun getDailyCashflow(
        request: GetDailyCashflowRequest,
        responseObserver: StreamObserver<GetDailyCashflowResponse>,
    ) {
        handle(responseObserver) {
            val result = calculateDailyCashflowUseCase.calculate(
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

    private fun BigDecimal.toProtoMoney(currency: String = "KRW"): ProtoMoney {
        return ProtoMoney.newBuilder()
            .setAmount(toLong())
            .setCurrency(currency)
            .build()
    }

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

    private fun Throwable.toGrpcStatus(): Status {
        return when (this) {
            is IllegalArgumentException -> Status.INVALID_ARGUMENT.withDescription(message)
            is IllegalStateException -> Status.FAILED_PRECONDITION.withDescription(message)
            else -> Status.INTERNAL.withDescription(message)
        }.withCause(this)
    }
}

