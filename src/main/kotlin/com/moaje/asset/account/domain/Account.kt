package com.moaje.asset.account.domain

import com.moaje.asset.common.id.TsidGeneratorHolder
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.CreationTimestamp
import org.hibernate.annotations.UpdateTimestamp
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDateTime

@Entity
@Table(
    name = "account",
    indexes = [
        Index(name = "idx_account_user_id", columnList = "user_id"),
    ],
)
class Account(
    @Id
    @Column(name = "id", nullable = false)
    var id: Long? = TsidGeneratorHolder.nextLong(),

    @Column(name = "user_id", nullable = false, length = 100)
    var userId: String,

    @Column(name = "balance", nullable = false, precision = 18, scale = 4)
    var balance: BigDecimal = BigDecimal.ZERO,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    var status: AccountStatus = AccountStatus.ACTIVE,

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false, length = 20, columnDefinition = "varchar(20) default 'SYNCED'")
    var syncStatus: AccountSyncStatus = AccountSyncStatus.SYNCED,

    @Column(name = "last_synced_at")
    var lastSyncedAt: LocalDateTime? = null,

    @Column(name = "snapshot_cursor_at")
    var snapshotCursorAt: LocalDateTime? = null,

    @Column(name = "last_event_applied_at")
    var lastEventAppliedAt: LocalDateTime? = null,

    @Column(name = "last_sync_failed_at")
    var lastSyncFailedAt: LocalDateTime? = null,

    @Column(name = "sync_attempt_count", nullable = false)
    var syncAttemptCount: Int = 0,

    @Column(name = "sync_retry_exhausted", nullable = false)
    var syncRetryExhausted: Boolean = false,

    @Column(name = "next_sync_at")
    var nextSyncAt: LocalDateTime? = null,

    @Column(name = "sync_locked_at")
    var syncLockedAt: LocalDateTime? = null,

    @Column(name = "sync_locked_until")
    var syncLockedUntil: LocalDateTime? = null,

    @Column(name = "sync_lock_owner", length = 120)
    var syncLockOwner: String? = null,

    @Column(name = "last_sync_failure_reason", length = 500)
    var lastSyncFailureReason: String? = null,

    @CreationTimestamp
    @Column(name = "created_at", nullable = false)
    var createdAt: LocalDateTime = LocalDateTime.now(),

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
) {
    fun requireTransferable() {
        require(status == AccountStatus.ACTIVE) { "활성 계좌만 거래할 수 있습니다." }
    }

    fun applyCredit(amount: BigDecimal) {
        requireTransferable()
        require(amount > BigDecimal.ZERO) { "입금 금액은 0보다 커야 합니다." }
        balance = balance.add(amount)
    }

    fun applyDebit(amount: BigDecimal) {
        requireTransferable()
        require(amount > BigDecimal.ZERO) { "출금 금액은 0보다 커야 합니다." }
        balance = balance.subtract(amount)
    }

    /**
     * Snapshot 대사는 증감값을 다시 재생하지 않고 계정계가 확정한 잔액으로 Projection을 교체한다.
     * 이 방식은 일부 Kafka 이벤트가 빠졌거나 중복 이력이 있어도 최종 잔액이 누적 오차에 끌려가지 않게 한다.
     */
    fun replaceProjection(balance: BigDecimal, externalStatus: String) {
        require(balance >= BigDecimal.ZERO) { "계정계 Snapshot 잔액은 음수일 수 없습니다." }
        this.balance = balance
        status = when (externalStatus.uppercase()) {
            "ACTIVE" -> AccountStatus.ACTIVE
            "CANCELED", "CLOSED" -> AccountStatus.CLOSED
            else -> throw IllegalArgumentException("지원하지 않는 계정계 계좌 상태입니다. status=$externalStatus")
        }
    }

    fun markReconciling() {
        syncStatus = AccountSyncStatus.RECONCILING
    }

    fun markSynced(syncedAt: LocalDateTime = LocalDateTime.now()) {
        syncStatus = AccountSyncStatus.SYNCED
        lastSyncedAt = syncedAt
        lastSyncFailedAt = null
        lastSyncFailureReason = null
        nextSyncAt = null
        syncRetryExhausted = false
        clearSyncLease()
    }

    /**
     * 이벤트 한 건을 받았다고 외부 입출금까지 모두 확인한 것은 아니다. 정기 조회 시각은 그대로 둔다.
     * 조회 중 새 이벤트가 잔액을 바꾸면 진행 중 lease를 취소해, 그보다 오래된 스냅샷의 덮어쓰기를 막는다.
     */
    fun recordEventApplied(now: LocalDateTime = LocalDateTime.now()) {
        lastEventAppliedAt = now
        clearSyncLease()
    }

    fun markStale(failedAt: LocalDateTime = LocalDateTime.now()) {
        syncStatus = AccountSyncStatus.STALE
        lastSyncFailedAt = failedAt
    }

    fun markSyncRequired() {
        if (syncStatus != AccountSyncStatus.SYNC_REQUIRED) {
            syncAttemptCount = 0
            syncRetryExhausted = false
            nextSyncAt = null
        }
        syncStatus = AccountSyncStatus.SYNC_REQUIRED
    }

    /**
     * DLT는 늦게 도착한 과거 이벤트일 수 있어 기존 cursor 이후만 조회하면 문제 거래를 놓칠 수 있다.
     * 마지막 동기화 시각을 비워 다음 Snapshot을 EPOCH부터 재생하되, 소진된 자동 재시도는 운영자 승인 없이 다시 열지 않는다.
     */
    fun markFullSyncRequired() {
        if (!syncRetryExhausted) {
            syncAttemptCount = 0
            nextSyncAt = null
        }
        lastSyncedAt = null
        snapshotCursorAt = null
        syncStatus = AccountSyncStatus.SYNC_REQUIRED
        clearSyncLease()
    }

    fun isSyncLockedBy(owner: String): Boolean = syncLockOwner == owner

    fun hasValidSyncLease(owner: String, now: LocalDateTime): Boolean =
        isSyncLockedBy(owner) && syncLockedUntil?.isAfter(now) == true

    /**
     * 조회 실패를 금융거래 실패로 바꾸지 않고 Projection 복구 작업의 운영 상태에만 기록한다.
     * 제한된 지수 백오프는 장애 중 Banking을 계속 두드리는 부하를 줄이고, 소진 상태는 운영 개입 지점을 만든다.
     */
    fun recordSyncFailure(
        reason: String,
        now: LocalDateTime,
        maxAttempts: Int,
        baseBackoffMillis: Long,
        maxBackoffMillis: Long,
    ) {
        syncStatus = AccountSyncStatus.SYNC_REQUIRED
        syncAttemptCount += 1
        lastSyncFailedAt = now
        lastSyncFailureReason = reason.take(500)
        if (syncAttemptCount >= maxAttempts.coerceAtLeast(1)) {
            syncRetryExhausted = true
            nextSyncAt = null
        } else {
            syncRetryExhausted = false
            nextSyncAt = now.plus(Duration.ofMillis(calculateBackoff(baseBackoffMillis, maxBackoffMillis)))
        }
        clearSyncLease()
    }

    fun recordSyncResolved(now: LocalDateTime, cursor: LocalDateTime = now) {
        snapshotCursorAt = cursor
        syncAttemptCount = 0
        markSynced(now)
    }

    private fun clearSyncLease() {
        syncLockedAt = null
        syncLockedUntil = null
        syncLockOwner = null
    }

    private fun calculateBackoff(baseBackoffMillis: Long, maxBackoffMillis: Long): Long {
        val base = baseBackoffMillis.coerceAtLeast(1)
        val max = maxBackoffMillis.coerceAtLeast(base)
        var delay = base
        repeat((syncAttemptCount - 1).coerceAtLeast(0)) {
            delay = (delay * 2).coerceAtMost(max)
        }
        return delay.coerceAtMost(max)
    }
}

