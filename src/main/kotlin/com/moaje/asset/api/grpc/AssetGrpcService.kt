package com.moaje.asset.api.grpc

import com.moaje.asset.account.application.service.AccountBalanceQueryService
import com.moaje.asset.cashflow.application.service.DailyCashflowService
import com.moaje.asset.cashflow.application.model.DailyCashflowCommand
import com.moaje.asset.cashflow.exception.AccountException
import com.moaje.common.Money as ProtoMoney
import com.moaje.grpc.asset.AssetServiceGrpc
import com.moaje.grpc.asset.GetCurrentBalanceRequest
import com.moaje.grpc.asset.GetCurrentBalanceResponse
import com.moaje.grpc.asset.GetDailyCashflowRequest
import com.moaje.grpc.asset.GetDailyCashflowResponse
import io.grpc.Status
import io.grpc.stub.StreamObserver
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate

@Component
class AssetGrpcService(
    private val dailyCashflowService: DailyCashflowService,
    private val accountBalanceQueryService: AccountBalanceQueryService
) : AssetServiceGrpc.AssetServiceImplBase() {
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

    override fun getCurrentBalance(
        request: GetCurrentBalanceRequest,
        responseObserver: StreamObserver<GetCurrentBalanceResponse>
    ) {
        handle(responseObserver) {
            val balance = accountBalanceQueryService.getCurrentBalance(request.userId)

            GetCurrentBalanceResponse.newBuilder()
                .setUserId(request.userId)
                .setCurrentBalance(balance.toProtoMoney())
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
            // 요청 값은 정상이지만 계좌연결 및 활성계좌 상태가 계산 조건을 만족하지못함.
            // work는 이를 정상 잔액 0원과 구분해서 처리.
            is AccountException -> Status.FAILED_PRECONDITION.withDescription(message)
            is IllegalArgumentException -> Status.INVALID_ARGUMENT.withDescription(message)
            is IllegalStateException -> Status.FAILED_PRECONDITION.withDescription(message)
            else -> Status.INTERNAL.withDescription(message)
        }.withCause(this)
    }
}

