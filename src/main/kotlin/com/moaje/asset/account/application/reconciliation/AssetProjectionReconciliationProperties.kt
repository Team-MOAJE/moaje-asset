package com.moaje.asset.account.application.reconciliation

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties(prefix = "moaje.asset.reconciliation.projection")
class AssetProjectionReconciliationProperties {
    var enabled: Boolean = false
    var fixedDelayMs: Long = 5_000
    var initialDelayMs: Long = 30_000
    var batchSize: Int = 20
    var leaseDurationMs: Long = 30_000
    var leaseSafetyMarginMs: Long = 5_000
    // 오류 표식이 없어도 ATM 등 외부 거래는 발생할 수 있다. 기본 30초마다 계정계와 비교한다.
    // 실제 반영 지연에는 후보 대기와 통신 시간이 더해지므로 30초 완료를 보장하는 수치는 아니다.
    var auditIntervalMs: Long = 30_000
    var maxAttempts: Int = 10
    var baseBackoffMs: Long = 5_000
    var maxBackoffMs: Long = 300_000
}
