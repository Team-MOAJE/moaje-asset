package com.moaje.asset.account.application.reconciliation

import com.moaje.asset.account.persistence.AccountRepository
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import java.time.LocalDateTime

@Component
class AssetProjectionReconciliationMetrics(
    private val accountRepository: AccountRepository,
    meterRegistry: MeterRegistry,
) {
    private val claimed = Counter.builder("asset.reconciliation.projection.claimed").register(meterRegistry)
    private val resolved = Counter.builder("asset.reconciliation.projection.resolved").register(meterRegistry)
    private val failed = Counter.builder("asset.reconciliation.projection.failed").register(meterRegistry)
    private val repairedEffects = Counter.builder("asset.reconciliation.projection.repaired.effects").register(meterRegistry)
    private val retryExhausted = Counter.builder("asset.reconciliation.projection.retry.exhausted.total").register(meterRegistry)

    init {
        // 계좌 ID를 tag로 사용하면 계좌 수만큼 시계열이 늘어나므로 운영 backlog의 전체 개수만 노출한다.
        Gauge.builder("asset.reconciliation.projection.pending", accountRepository) { it.countProjectionReconciliationPending().toDouble() }
            .register(meterRegistry)
        Gauge.builder("asset.reconciliation.projection.locked", accountRepository) {
            it.countProjectionReconciliationLocked(LocalDateTime.now()).toDouble()
        }.register(meterRegistry)
        Gauge.builder("asset.reconciliation.projection.retry.exhausted", accountRepository) {
            it.countBySyncRetryExhaustedTrue().toDouble()
        }.register(meterRegistry)
    }

    fun recordClaimed() = claimed.increment()

    fun recordResolved(effectCount: Int) {
        resolved.increment()
        repairedEffects.increment(effectCount.toDouble())
    }

    fun recordFailed(exhausted: Boolean) {
        failed.increment()
        if (exhausted) retryExhausted.increment()
    }
}
