package com.moaje.asset.transfer.persistence

import com.moaje.asset.transfer.domain.TransactionHistory
import com.moaje.asset.transfer.domain.TransactionStatus
import com.moaje.asset.transfer.domain.BalanceChangeType
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.math.BigDecimal
import java.time.LocalDateTime

interface MonthlyCategoryTotal {
    val categoryCode: com.moaje.asset.transfer.domain.TransactionCategory
    val totalAmount: BigDecimal
}

interface TransactionHistoryRepository : JpaRepository<TransactionHistory, Long> {
    fun findByAccountId(accountId: Long): List<TransactionHistory>

    fun findByAccountIdAndReconciliationRequiredTrueOrderByCreatedAtAsc(accountId: Long): List<TransactionHistory>

    fun findByBankingTransferIdAndAccountId(bankingTransferId: Long, accountId: Long): List<TransactionHistory>

    fun findByAccountIdAndVisibleToUserTrueOrderByCreatedAtDesc(accountId: Long): List<TransactionHistory>

    @Query(
        """
    select t
    from TransactionHistory t
    where t.accountId = :accountId
      and t.visibleToUser = true
      and (:lastTransactionId is null or t.id < :lastTransactionId)
    order by t.id desc
    """,
    )
    fun findVisibleTransactionsByCursor(
        @Param("accountId") accountId: Long,
        @Param("lastTransactionId") lastTransactionId: Long?,
        pageable: Pageable,
    ): List<TransactionHistory>

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

    /**
     * 실제 금융거래 발생 시각을 우선 사용해 월 경계에 늦게 복구된 거래도 원래 월에 포함한다.
     * 실패·대기·반전 거래와 화면 비노출 기록은 사용자의 소비 통계에서 제외한다.
     */
    @Query(
        """
        select t.categoryCode as categoryCode, sum(t.amount) as totalAmount
        from TransactionHistory t, Account a
        where a.id = t.accountId
          and a.userId = :userId
          and t.status = com.moaje.asset.transfer.domain.TransactionStatus.SUCCESS
          and t.visibleToUser = true
          and t.type <> com.moaje.asset.transfer.domain.TransactionType.REVERSAL
          and coalesce(t.externalOccurredAt, t.createdAt) >= :start
          and coalesce(t.externalOccurredAt, t.createdAt) < :end
        group by t.categoryCode
        """,
    )
    fun sumSuccessfulTransactionsByCategory(
        @Param("userId") userId: String,
        @Param("start") start: LocalDateTime,
        @Param("end") end: LocalDateTime,
    ): List<MonthlyCategoryTotal>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TransactionHistory t where t.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): TransactionHistory?


    @Query(
        """
            select t from TransactionHistory t
            where t.id = :transactionId and t.accountId = :accountId
            and t.visibleToUser = true
            and exists (
                select a.id from Account a
                where a.id = t.accountId and a.userId = :userId
            )
        """,
    )
    fun findOwnedTransaction(transactionId: Long, accountId: Long, userId: String): TransactionHistory?

}

