package com.moaje.asset.repository.cashflow

import com.moaje.asset.domain.cashflow.DailyCashflowSnapshot
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface DailyCashflowSnapshotRepository : JpaRepository<DailyCashflowSnapshot, Long> {
    fun findByUserIdAndSnapshotDate(
        userId: Long,
        snapshotDate: LocalDate,
    ): DailyCashflowSnapshot?
}

