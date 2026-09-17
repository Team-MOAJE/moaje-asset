package com.moaje.asset.cashflow.persistence

import com.moaje.asset.cashflow.domain.MonthlyCashflowAggregate
import com.moaje.asset.cashflow.domain.MonthlyCategoryCashflow
import com.moaje.asset.transfer.domain.TransactionCategory
import org.springframework.data.jpa.repository.JpaRepository

interface MonthlyCashflowAggregateRepository : JpaRepository<MonthlyCashflowAggregate, Long> {
    fun findByUserIdAndYearAndMonth(userId: String, year: Int, month: Int): MonthlyCashflowAggregate?
}

interface MonthlyCategoryCashflowRepository : JpaRepository<MonthlyCategoryCashflow, Long> {
    fun findByUserIdAndYearAndMonthOrderByCategoryAsc(userId: String, year: Int, month: Int): List<MonthlyCategoryCashflow>
    fun findByUserIdAndYearAndMonthAndCategory(
        userId: String,
        year: Int,
        month: Int,
        category: TransactionCategory,
    ): MonthlyCategoryCashflow?
}
