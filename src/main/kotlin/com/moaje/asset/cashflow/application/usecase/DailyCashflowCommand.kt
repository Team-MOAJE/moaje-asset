package com.moaje.asset.cashflow.application.usecase

import java.math.BigDecimal
import java.time.LocalDate

data class DailyCashflowCommand(
    val userId: String,
    val expectedIncome: BigDecimal,
    val fixedExpenses: BigDecimal,
    val eventBuffer: BigDecimal,
    val daysUntilNextPayday: Int,
    val snapshotDate: LocalDate = LocalDate.now(),
    val forceRefresh: Boolean = false,
)


