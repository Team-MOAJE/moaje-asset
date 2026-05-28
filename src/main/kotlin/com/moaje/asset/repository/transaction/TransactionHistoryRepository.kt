package com.moaje.asset.repository.transaction

import com.moaje.asset.domain.transaction.TransactionHistory
import com.moaje.asset.domain.transaction.TransactionStatus
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TransactionHistoryRepository : JpaRepository<TransactionHistory, Long> {
    fun findByAccountId(accountId: Long): List<TransactionHistory>

    fun findByIdempotencyKey(idempotencyKey: String): TransactionHistory?

    fun findByPublicTransferId(publicTransferId: String): TransactionHistory?

    fun existsByExternalTransactionId(externalTransactionId: String): Boolean

    fun findByStatus(status: TransactionStatus): List<TransactionHistory>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TransactionHistory t where t.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): TransactionHistory?
}

