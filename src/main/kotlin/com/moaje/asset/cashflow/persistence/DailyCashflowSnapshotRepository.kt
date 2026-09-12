package com.moaje.asset.cashflow.persistence

import com.moaje.asset.cashflow.domain.DailyCashflowSnapshot
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

interface DailyCashflowSnapshotRepository : JpaRepository<DailyCashflowSnapshot, String> {
    fun findByUserIdAndSnapshotDate(
        userId: String,
        snapshotDate: LocalDate,
    ): DailyCashflowSnapshot?
}


