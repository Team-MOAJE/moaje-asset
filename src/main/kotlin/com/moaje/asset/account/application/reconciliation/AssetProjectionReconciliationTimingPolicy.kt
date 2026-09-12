package com.moaje.asset.account.application.reconciliation

import com.moaje.asset.account.integration.banking.BankingGrpcClientProperties
import org.springframework.stereotype.Component

@Component
class AssetProjectionReconciliationTimingPolicy(
    bankingProperties: BankingGrpcClientProperties,
    reconciliationProperties: AssetProjectionReconciliationProperties,
) {
    init {
        require(bankingProperties.projectionReconciliationTimeoutMs > 0) {
            "moaje.asset.banking.projection-reconciliation-timeout-ms는 0보다 커야 합니다."
        }
        require(reconciliationProperties.leaseSafetyMarginMs >= 0) {
            "moaje.asset.reconciliation.projection.lease-safety-margin-ms는 0 이상이어야 합니다."
        }

        val totalGrpcDeadline = Math.multiplyExact(
            bankingProperties.projectionReconciliationTimeoutMs,
            MAX_SEQUENTIAL_GRPC_CALLS,
        )
        val minimumExclusiveLease = Math.addExact(
            totalGrpcDeadline,
            reconciliationProperties.leaseSafetyMarginMs,
        )
        /**
         * Snapshot과 거래 상태 조회는 최악의 경우 각각 deadline까지 순차 대기하므로 lease는 두 deadline 합보다 길어야 한다.
         * 여기에 결과 검증·DB 반영 여유를 더해, 정상 Worker가 살아 있는 동안 다른 인스턴스가 같은 계좌를 선점하지 않게 한다.
         */
        require(reconciliationProperties.leaseDurationMs > minimumExclusiveLease) {
            "projection lease-duration-ms(${reconciliationProperties.leaseDurationMs})는 " +
                "순차 gRPC timeout 합($totalGrpcDeadline)과 " +
                "safety margin(${reconciliationProperties.leaseSafetyMarginMs})의 합보다 커야 합니다."
        }
        require(reconciliationProperties.maxAttempts > 0) { "projection max-attempts는 0보다 커야 합니다." }
        require(reconciliationProperties.batchSize > 0) { "projection batch-size는 0보다 커야 합니다." }
    }

    companion object {
        private const val MAX_SEQUENTIAL_GRPC_CALLS = 2L
    }
}
