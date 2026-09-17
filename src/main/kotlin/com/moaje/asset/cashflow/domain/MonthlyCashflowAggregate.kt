package com.moaje.asset.cashflow.domain

import com.moaje.asset.common.id.TsidGeneratedValue
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.math.BigDecimal
import java.time.LocalDateTime

@Entity
@Table(
    name = "monthly_cashflow_aggregate",
    uniqueConstraints = [
        UniqueConstraint(
            name = "uk_monthly_cashflow_user_period",
            columnNames = ["user_id", "aggregate_year", "aggregate_month"],
        ),
    ],
)
class MonthlyCashflowAggregate(
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
    @Column(name = "income_amount", nullable = false, precision = 18, scale = 4)
    var incomeAmount: BigDecimal = BigDecimal.ZERO,
    @Column(name = "expense_amount", nullable = false, precision = 18, scale = 4)
    var expenseAmount: BigDecimal = BigDecimal.ZERO,
    @Column(name = "transfer_amount", nullable = false, precision = 18, scale = 4)
    var transferAmount: BigDecimal = BigDecimal.ZERO,
    @Column(name = "revision", nullable = false)
    var revision: Long = 1,
    @Column(name = "content_hash", nullable = false, length = 64)
    var contentHash: String,
    @Column(name = "calculated_at", nullable = false)
    var calculatedAt: LocalDateTime,
) {
    /**
     * 늦게 복구된 거래로 계산 결과가 달라졌을 때만 revision을 올린다.
     * 같은 장부를 다시 계산한 것뿐이라면 Work에 같은 수정 이벤트를 반복해서 보내지 않는다.
     */
    fun revise(
        incomeAmount: BigDecimal,
        expenseAmount: BigDecimal,
        transferAmount: BigDecimal,
        contentHash: String,
        calculatedAt: LocalDateTime,
    ): Boolean {
        if (this.contentHash == contentHash) return false
        this.incomeAmount = incomeAmount
        this.expenseAmount = expenseAmount
        this.transferAmount = transferAmount
        this.contentHash = contentHash
        this.calculatedAt = calculatedAt
        revision += 1
        return true
    }
}
