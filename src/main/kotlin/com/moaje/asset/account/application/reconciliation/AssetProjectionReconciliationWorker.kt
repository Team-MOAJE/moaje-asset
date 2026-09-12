package com.moaje.asset.account.application.reconciliation

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class AssetProjectionReconciliationWorker(
    private val service: AssetProjectionReconciliationService,
    private val properties: AssetProjectionReconciliationProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${moaje.asset.reconciliation.projection.fixed-delay-ms:60000}",
        initialDelayString = "\${moaje.asset.reconciliation.projection.initial-delay-ms:30000}",
    )
    fun reconcile() {
        if (!properties.enabled) return
        val result = service.reconcileBatch()
        if (result.scanned > 0) {
            log.info(
                "Asset projection reconciliation finished. scanned={}, claimed={}, resolved={}, failed={}, repairedEffects={}",
                result.scanned,
                result.claimed,
                result.resolved,
                result.failed,
                result.repairedEffects,
            )
        }
    }
}
