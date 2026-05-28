package com.moaje.asset.application.account

import java.time.Instant

data class RefreshAccountTransactionsCommand(
    val userId: Long,
    val accountToken: String,
    val cursor: Instant,
)

data class RefreshAccountTransactionsResult(
    val accountToken: String,
    val syncedCount: Int,
)
