package com.moaje.asset.cashflow.application.model

import java.math.BigDecimal
import java.time.LocalDateTime

data class MonthlyCashflowOutboxPayload(
    val eventId: String,
    val userId: String,
    val yearMonth: String,
    val revision: Long,
    val incomeAmount: BigDecimal,
    val expenseAmount: BigDecimal,
    val transferAmount: BigDecimal,
    val calculatedAt: LocalDateTime,
)

data class CategoryCashflowOutboxItem(val category: String, val amount: BigDecimal)

data class CategoryCashflowOutboxPayload(
    val eventId: String,
    val userId: String,
    val yearMonth: String,
    val revision: Long,
    val categories: List<CategoryCashflowOutboxItem>,
    val calculatedAt: LocalDateTime,
)
