package com.moaje.asset.application.cashflow

import java.math.BigDecimal
import java.time.LocalDate

data class DailyCashflowResult(
    val userId: Long,
    val currentBalance: BigDecimal,
    val expectedIncome: BigDecimal,
    val fixedExpenses: BigDecimal,
    val eventBuffer: BigDecimal,
    val daysUntilNextPayday: Int,
    val dailyLimit: BigDecimal,
    val snapshotDate: LocalDate,
)

