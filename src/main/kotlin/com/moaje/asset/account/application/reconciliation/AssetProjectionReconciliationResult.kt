package com.moaje.asset.account.application.reconciliation

data class AssetProjectionReconciliationResult(
    val scanned: Int,
    val claimed: Int,
    val resolved: Int,
    val failed: Int,
    val repairedEffects: Int,
)

data class ProjectionRepairResult(
    val repairedEffects: Int,
    val remainingTransfers: Int,
    val retryExhausted: Boolean,
)
