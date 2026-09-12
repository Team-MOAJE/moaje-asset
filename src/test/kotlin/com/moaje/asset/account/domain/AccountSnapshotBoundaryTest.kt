package com.moaje.asset.account.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class AccountSnapshotBoundaryTest {
    @Test
    @DisplayName("정상 이벤트는 정기 감사 시각과 cursor를 당기거나 소진된 재시도를 열지 않는다")
    fun eventDoesNotPretendToBeFullAudit() {
        // given
        val before = LocalDateTime.of(2026, 1, 1, 0, 0)
        val account = Account(userId = "test-user", lastSyncedAt = before, snapshotCursorAt = before,
            syncStatus = AccountSyncStatus.SYNC_REQUIRED, syncRetryExhausted = true,
            syncLockOwner = "old-worker", syncLockedUntil = before.plusMinutes(1))
        // when
        account.recordEventApplied(before.plusSeconds(10))
        // then
        assertThat(account.lastSyncedAt).isEqualTo(before)
        assertThat(account.snapshotCursorAt).isEqualTo(before)
        assertThat(account.syncRetryExhausted).isTrue()
        assertThat(account.syncStatus).isEqualTo(AccountSyncStatus.SYNC_REQUIRED)
        assertThat(account.syncLockOwner).isNull()
    }

    @Test
    @DisplayName("다른 Worker가 없어도 만료 시각 이후의 lease는 반영 권한이 없다")
    fun expiredLeaseCannotCommit() {
        // given
        val until = LocalDateTime.of(2026, 1, 1, 0, 1)
        val account = Account(userId = "test-user", syncLockOwner = "worker", syncLockedUntil = until)
        // when / then: sleep 없이 경계 시각을 직접 주입한다.
        assertThat(account.hasValidSyncLease("worker", until.minusNanos(1))).isTrue()
        assertThat(account.hasValidSyncLease("worker", until)).isFalse()
        assertThat(account.hasValidSyncLease("worker", until.plusSeconds(1))).isFalse()
    }
}
