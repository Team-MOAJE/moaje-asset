package com.moaje.asset.transfer.persistence

import com.moaje.asset.transfer.domain.TransactionHistory
import com.moaje.asset.transfer.domain.TransactionStatus
import com.moaje.asset.transfer.domain.BalanceChangeType
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface TransactionHistoryRepository : JpaRepository<TransactionHistory, Long> {
    fun findByAccountId(accountId: Long): List<TransactionHistory>

    fun findByAccountIdAndReconciliationRequiredTrueOrderByCreatedAtAsc(accountId: Long): List<TransactionHistory>

    fun findByBankingTransferIdAndAccountId(bankingTransferId: Long, accountId: Long): List<TransactionHistory>

    fun findByAccountIdAndVisibleToUserTrueOrderByCreatedAtDesc(accountId: Long): List<TransactionHistory>

    fun existsByAccountIdAndReconciliationRequiredTrue(accountId: Long): Boolean

    fun findByIdempotencyKey(idempotencyKey: String): TransactionHistory?

    fun findByPublicTransferId(publicTransferId: String): TransactionHistory?

    fun existsByExternalTransactionId(externalTransactionId: String): Boolean

    fun findByExternalTransactionId(externalTransactionId: String): TransactionHistory?

    fun existsByBankingTransferIdAndAccountIdAndBalanceChangeType(
        bankingTransferId: Long,
        accountId: Long,
        balanceChangeType: BalanceChangeType,
    ): Boolean

    fun findByBankingTransferIdAndAccountIdAndBalanceChangeType(
        bankingTransferId: Long,
        accountId: Long,
        balanceChangeType: BalanceChangeType,
    ): TransactionHistory?

    fun findByStatus(status: TransactionStatus): List<TransactionHistory>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TransactionHistory t where t.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): TransactionHistory?
}


