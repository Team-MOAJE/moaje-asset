package com.moaje.asset.cashflow.application.model

import com.moaje.asset.transfer.domain.TransactionCategory
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.YearMonth

data class MonthlyCashflowResult(
    val userId: String,
    val yearMonth: YearMonth,
    val incomeAmount: BigDecimal,
    val expenseAmount: BigDecimal,
    val transferAmount: BigDecimal,
    val revision: Long,
    val calculatedAt: LocalDateTime,
)

data class CategoryCashflowItem(
    val category: TransactionCategory,
    val amount: BigDecimal,
)

data class CategoryCashflowResult(
    val userId: String,
    val yearMonth: YearMonth,
    val revision: Long,
    val categories: List<CategoryCashflowItem>,
    val calculatedAt: LocalDateTime,
)
