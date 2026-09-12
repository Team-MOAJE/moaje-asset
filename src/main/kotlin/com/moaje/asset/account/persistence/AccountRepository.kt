package com.moaje.asset.account.persistence

import com.moaje.asset.account.domain.Account
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.Modifying
import java.time.LocalDateTime

interface AccountRepository : JpaRepository<Account, Long> {
    fun findByUserId(userId: String): List<Account>

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    fun findByIdForUpdate(@Param("id") id: Long): Account?

    @Query(
        """
        select a.id from Account a
        where (
            a.syncStatus in (
                com.moaje.asset.account.domain.AccountSyncStatus.SYNC_REQUIRED,
                com.moaje.asset.account.domain.AccountSyncStatus.STALE
            )
            or (a.syncStatus = com.moaje.asset.account.domain.AccountSyncStatus.SYNCED
                and (a.lastSyncedAt is null or a.lastSyncedAt <= :auditBefore))
        )
          and a.syncRetryExhausted = false
          and (a.nextSyncAt is null or a.nextSyncAt <= :now)
          and (a.syncLockedUntil is null or a.syncLockedUntil <= :now)
        order by a.updatedAt asc
        """,
    )
    fun findProjectionReconciliationCandidateIds(
        @Param("now") now: LocalDateTime,
        @Param("auditBefore") auditBefore: LocalDateTime,
        pageable: Pageable,
    ): List<Long>

    /**
     * 후보 조회와 실제 선점 사이에는 다른 인스턴스가 끼어들 수 있으므로 조건부 update를 최종 방어선으로 사용한다.
     * lease 만료 시 다시 선점할 수 있어 Worker 종료가 계좌를 영구 RECONCILING 상태로 남기지 않는다.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(
        """
        update Account a
        set a.syncLockedAt = :now,
            a.syncLockedUntil = :lockedUntil,
            a.syncLockOwner = :lockOwner
        where a.id = :accountId
          and (
            a.syncStatus in (
              com.moaje.asset.account.domain.AccountSyncStatus.SYNC_REQUIRED,
              com.moaje.asset.account.domain.AccountSyncStatus.STALE
            )
            or (a.syncStatus = com.moaje.asset.account.domain.AccountSyncStatus.SYNCED
                and (a.lastSyncedAt is null or a.lastSyncedAt <= :auditBefore))
          )
          and a.syncRetryExhausted = false
          and (a.nextSyncAt is null or a.nextSyncAt <= :now)
          and (a.syncLockedUntil is null or a.syncLockedUntil <= :now)
        """,
    )
    fun claimProjectionReconciliation(
        @Param("accountId") accountId: Long,
        @Param("now") now: LocalDateTime,
        @Param("auditBefore") auditBefore: LocalDateTime,
        @Param("lockedUntil") lockedUntil: LocalDateTime,
        @Param("lockOwner") lockOwner: String,
    ): Int

    @Query(
        """
        select count(a) from Account a
        where a.syncStatus in (
            com.moaje.asset.account.domain.AccountSyncStatus.SYNC_REQUIRED,
            com.moaje.asset.account.domain.AccountSyncStatus.STALE
        )
          and a.syncRetryExhausted = false
        """,
    )
    fun countProjectionReconciliationPending(): Long

    fun countBySyncRetryExhaustedTrue(): Long

    @Query("select count(a) from Account a where a.syncLockedUntil is not null and a.syncLockedUntil > :now")
    fun countProjectionReconciliationLocked(@Param("now") now: LocalDateTime): Long
}


