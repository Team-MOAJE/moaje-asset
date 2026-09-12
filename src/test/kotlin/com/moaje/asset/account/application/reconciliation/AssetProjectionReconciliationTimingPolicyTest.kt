package com.moaje.asset.account.application.reconciliation

import com.moaje.asset.account.integration.banking.BankingGrpcClientProperties
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Asset Projection 대사 시간 설정")
class AssetProjectionReconciliationTimingPolicyTest {
    @Test
    @DisplayName("lease가 두 gRPC timeout 합과 안전 여유보다 길면 설정을 허용한다")
    fun acceptsSafeLeaseDuration() {
        // given
        val banking = BankingGrpcClientProperties().apply { projectionReconciliationTimeoutMs = 5_000 }
        val reconciliation = AssetProjectionReconciliationProperties().apply {
            leaseDurationMs = 30_000
            leaseSafetyMarginMs = 5_000
        }

        // when & then
        assertThatCode { AssetProjectionReconciliationTimingPolicy(banking, reconciliation) }
            .doesNotThrowAnyException()
    }

    @Test
    @DisplayName("lease가 정상 gRPC 대기 구간보다 짧으면 시작 단계에서 차단한다")
    fun rejectsLeaseThatCanExpireDuringGrpcCall() {
        // given: 최악 10초인 두 순차 조회와 5초 안전 여유에 비해 lease가 12초뿐이다.
        val banking = BankingGrpcClientProperties().apply { projectionReconciliationTimeoutMs = 5_000 }
        val reconciliation = AssetProjectionReconciliationProperties().apply {
            leaseDurationMs = 12_000
            leaseSafetyMarginMs = 5_000
        }

        // when & then
        assertThatThrownBy { AssetProjectionReconciliationTimingPolicy(banking, reconciliation) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("합보다 커야 합니다")
    }
}
