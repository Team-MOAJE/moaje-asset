package com.moaje.asset.domain.cashflow

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.UpdateTimestamp
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(name = "daily_cashflow_snapshot")
class DailyCashflowSnapshot(
    @Id
    @Column(name = "user_id", nullable = false)
    var userId: Long,

    @Column(name = "calculated_limit", nullable = false, precision = 18, scale = 4)
    var calculatedLimit: BigDecimal = BigDecimal.ZERO,

    @Column(name = "study_buffer", nullable = false, precision = 18, scale = 4)
    var studyBuffer: BigDecimal = BigDecimal.ZERO,

    @Column(name = "snapshot_date", nullable = false)
    var snapshotDate: LocalDate = LocalDate.now(),

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    fun update(
        calculatedLimit: BigDecimal,
        studyBuffer: BigDecimal,
        snapshotDate: LocalDate,
    ) {
        this.calculatedLimit = calculatedLimit
        this.studyBuffer = studyBuffer
        this.snapshotDate = snapshotDate
    }
}

