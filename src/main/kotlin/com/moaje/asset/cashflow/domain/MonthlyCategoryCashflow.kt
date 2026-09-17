package com.moaje.asset.cashflow.domain

import com.moaje.asset.common.id.TsidGeneratedValue
import com.moaje.asset.transfer.domain.TransactionCategory
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.math.BigDecimal

@Entity
@Table(
    name = "monthly_category_cashflow",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_monthly_category_user_period_category",
            columnNames = ["user_id", "aggregate_year", "aggregate_month", "category_code"],
        ),
    ],
)
class MonthlyCategoryCashflow(
    @Id
    @TsidGeneratedValue
    @Column(name = "id", nullable = false)
    var id: Long? = null,
    @Column(name = "user_id", nullable = false, length = 100)
    var userId: String,
    @Column(name = "aggregate_year", nullable = false)
    var year: Int,
    @Column(name = "aggregate_month", nullable = false)
    var month: Int,
    @Enumerated(EnumType.STRING)
    @Column(name = "category_code", nullable = false, length = 50)
    var category: TransactionCategory,
    @Column(name = "amount", nullable = false, precision = 18, scale = 4)
    var amount: BigDecimal = BigDecimal.ZERO,
) {
    fun replaceAmount(amount: BigDecimal) {
        this.amount = amount
    }
}
