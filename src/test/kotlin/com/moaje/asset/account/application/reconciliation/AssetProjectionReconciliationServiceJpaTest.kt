package com.moaje.asset.account.application.reconciliation

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.moaje.asset.account.application.model.BankingProjectionTransferStatus
import com.moaje.asset.account.application.query.BankingAccountSnapshotQuery
import com.moaje.asset.account.application.model.BankingAccountProjectionSnapshot
import com.moaje.asset.account.application.model.BankingExternalProjectionTransaction
import com.moaje.asset.account.application.query.BankingTransferQuery
import com.moaje.asset.account.application.model.BankingTransferProjectionState
import com.moaje.asset.account.domain.Account
import com.moaje.asset.account.domain.AccountSyncStatus
import com.moaje.asset.account.persistence.AccountRepository
import com.moaje.asset.common.id.IdGenerator
import com.moaje.asset.outbox.persistence.TransactionalOutboxRepository
import com.moaje.asset.transfer.application.service.TransactionCategoryClassifier
import com.moaje.asset.transfer.application.service.TransferServiceImpl
import com.moaje.asset.transfer.domain.BalanceChangeType
import com.moaje.asset.transfer.domain.TransactionHistory
import com.moaje.asset.transfer.domain.TransactionStatus
import com.moaje.asset.transfer.domain.TransactionType
import com.moaje.asset.transfer.persistence.ProcessedEventRepository
import com.moaje.asset.transfer.persistence.TransactionHistoryRepository
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import org.springframework.data.domain.PageRequest
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.Instant

@DataJpaTest
@DisplayName("Asset Projection 자동 대사")
class AssetProjectionReconciliationServiceJpaTest(
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val transactionHistoryRepository: TransactionHistoryRepository,
    @Autowired private val processedEventRepository: ProcessedEventRepository,
    @Autowired private val outboxRepository: TransactionalOutboxRepository,
    @Autowired transactionManager: PlatformTransactionManager,
) {
    private val bankingQuery = FakeBankingQuery()
    private val properties = AssetProjectionReconciliationProperties().apply {
        batchSize = 10
        leaseDurationMs = 30_000
        maxAttempts = 3
        baseBackoffMs = 1_000
        maxBackoffMs = 10_000
    }
    private val meterRegistry = SimpleMeterRegistry()
    private val transactionTemplate = TransactionTemplate(transactionManager)
    private lateinit var service: AssetProjectionReconciliationService
    private lateinit var transferService: TransferServiceImpl

    @BeforeEach
    fun setUp() {
        transferService = TransferServiceImpl(
            accountRepository,
            transactionHistoryRepository,
            processedEventRepository,
            outboxRepository,
            TransactionCategoryClassifier(),
            IdGenerator(),
            ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS),
        )
        service = AssetProjectionReconciliationService(
            accountRepository,
            transactionHistoryRepository,
            bankingQuery,
            bankingQuery,
            transferService,
            properties,
            AssetProjectionReconciliationMetrics(accountRepository, meterRegistry),
            transactionTemplate,
        )
        bankingQuery.clear()
    }

    @Nested
    @DisplayName("계정계 Snapshot 복구")
    inner class AccountSnapshotRecovery {
        @Test
        @DisplayName("반전 대기 중 스냅샷이 먼저 두 효과를 확인하면 늦은 완료 이벤트가 다시 입금하지 않는다")
        fun snapshotConfirmsPendingReversalWithoutDoubleCredit() {
            // given: Reversed 이벤트가 먼저 와서 PENDING 이력만 있는 상태다.
            val account = saveSyncRequiredAccount("10000")
            transferService.applyBankingTransferReversed(
                com.moaje.asset.transfer.application.model.ApplyBankingTransferReversedCommand(
                    eventId = "reverse-first", bankingTransferId = 303L, userId = "user-1", accountId = account.id!!,
                    amount = BigDecimal("1000"), originalExternalTransactionId = "EXT-303",
                    externalReversalTransactionId = "REV-303", reason = null, occurredAt = Instant.now(),
                ),
            )
            bankingQuery.respond(state(account.id!!, 303L, BankingProjectionTransferStatus.UNKNOWN))
            bankingQuery.respondSnapshot(account.id!!, "10000", listOf(
                externalTransaction("EXT-303", "TRANSFER_OUT", "1000"),
                externalTransaction("REV-303", "REVERSAL_IN", "1000"),
            ))
            service.reconcileBatch()
            // when: 스냅샷 이후 원 성공 이벤트가 도착한다.
            transferService.applyBankingTransferCompleted(
                com.moaje.asset.transfer.application.model.ApplyBankingTransferCompletedCommand(
                    "complete-late", 303L, "user-1", account.id!!, BigDecimal("1000"), "EXT-303", Instant.now(),
                ),
            )
            // then
            assertThat(accountRepository.findById(account.id!!).orElseThrow().balance).isEqualByComparingTo("10000")
            assertThat(transactionHistoryRepository.count()).isEqualTo(2)
            assertThat(outboxRepository.count()).isEqualTo(1)
        }

        @Test
        @DisplayName("스냅샷으로 먼저 가져온 출금에 늦은 Completed가 도착해도 다시 차감하지 않는다")
        fun snapshotBeforeEventLinksWithoutSecondDebit() {
            // given: 계정계에는 출금이 끝났지만 Kafka 전달이 늦어 정기 조회가 먼저 반영했다.
            val account = saveSyncRequiredAccount("10000")
            bankingQuery.respondSnapshot(account.id!!, "9000", listOf(externalTransaction("EXT-301", "TRANSFER_OUT", "1000")))
            service.reconcileBatch()
            // when: 같은 거래가 새로운 eventId로도 다시 도착한다.
            repeat(2) { index -> transferService.applyBankingTransferCompleted(
                com.moaje.asset.transfer.application.model.ApplyBankingTransferCompletedCommand(
                    "late-$index", 301L, "user-1", account.id!!, BigDecimal("1000"), "EXT-301", Instant.now(),
                ),
            ) }
            // then: 기존 외부 이력에 transferId만 연결하고 잔액·Work Outbox는 그대로다.
            assertThat(accountRepository.findById(account.id!!).orElseThrow().balance).isEqualByComparingTo("9000")
            val histories = transactionHistoryRepository.findByAccountId(account.id!!)
            assertThat(histories).hasSize(1)
            assertThat(histories.single().bankingTransferId).isEqualTo(301L)
            assertThat(outboxRepository.count()).isEqualTo(1)
            assertThat(processedEventRepository.count()).isEqualTo(2)
        }

        @Test
        @DisplayName("조회 중 정상 이벤트가 도착하면 오래된 스냅샷을 폐기하고 다음 조회를 기다린다")
        fun eventInvalidatesInFlightSnapshot() {
            // given: Worker는 10,000원 스냅샷을 읽었지만 반환 직전에 1,000원 출금 이벤트가 반영된다.
            val account = saveSyncRequiredAccount("10000")
            bankingQuery.respondSnapshot(account.id!!, "10000", emptyList())
            bankingQuery.afterSnapshot = {
                transferService.applyBankingTransferCompleted(
                    com.moaje.asset.transfer.application.model.ApplyBankingTransferCompletedCommand(
                        "during-query", 302L, "user-1", account.id!!, BigDecimal("1000"), "EXT-302", Instant.now(),
                    ),
                )
            }
            // when
            val result = service.reconcileBatch()
            // then: 오래된 응답은 9,000원을 10,000원으로 덮어쓰지 못하며 cursor도 이동하지 않는다.
            val saved = accountRepository.findById(account.id!!).orElseThrow()
            assertThat(result.resolved).isZero()
            assertThat(saved.balance).isEqualByComparingTo("9000")
            assertThat(saved.snapshotCursorAt).isNull()
            assertThat(saved.syncStatus).isEqualTo(AccountSyncStatus.SYNC_REQUIRED)
            assertThat(saved.syncAttemptCount).isZero()
        }

        @Test
        @DisplayName("Work 성공 이벤트는 원 발생 시각과 완료 시각을 보존하고 대사 시각을 따로 기록한다")
        fun workPreservesSourceTimesAndExcludesReversal() {
            // given: 어제 발생한 외부 입금과 반전 내역을 오늘 대사로 확인했다.
            val account = saveSyncRequiredAccount("10000")
            val occurred = Instant.parse("2026-01-01T00:00:00Z")
            bankingQuery.respondSnapshot(account.id!!, "11000", listOf(
                externalTransaction("old-deposit", "DEPOSIT", "1000").copy(completedAt = occurred.plusSeconds(2)),
                externalTransaction("old-reversal", "REVERSAL_IN", "500"),
            ))
            // when
            service.reconcileBatch()
            // then: 반전은 조회 이력에 남지만 Work 이벤트는 입금 한 건뿐이다.
            val payload = ObjectMapper().readTree(outboxRepository.findAll().single().payload)
            assertThat(payload["occurredAt"].asText()).isEqualTo(occurred.toString())
            assertThat(payload["succeededAt"].asText()).isEqualTo(occurred.plusSeconds(2).toString())
            assertThat(payload["recoveredAt"].isNull).isFalse()
            assertThat(payload["timestampSource"].asText()).isEqualTo("CORE_BANKING")
            assertThat(transactionHistoryRepository.count()).isEqualTo(2)
        }

        @Test
        @DisplayName("오래된 SYNCED 계좌도 정기 Snapshot 감사 대상으로 다시 선점한다")
        fun auditsOldSyncedAccount() {
            // given: 오류 표식은 없지만 마지막 검증 시각이 감사 주기보다 오래된 계좌다.
            val account = accountRepository.saveAndFlush(
                Account(
                    userId = "user-1",
                    balance = BigDecimal("10000.0000"),
                    lastSyncedAt = LocalDateTime.now().minusHours(7),
                ),
            )
            bankingQuery.respondSnapshot(account.id!!, "11000.0000", emptyList())

            // when
            val result = service.reconcileBatch()

            // then: DLT에 계좌 식별자가 없어도 정기 감사가 장기 잔액 드리프트를 찾아낸다.
            assertThat(result.claimed).isEqualTo(1)
            assertThat(accountRepository.findById(account.id!!).orElseThrow().balance)
                .isEqualByComparingTo("11000.0000")
        }

        @Test
        @DisplayName("transferId가 없어도 실제 잔액과 외부 입출금 이력을 함께 복구한다")
        fun repairsBalanceDriftAndExternalHistories() {
            // given: Kafka로 알 수 없는 외부 입금이 발생해 Asset 잔액과 이력이 모두 뒤처져 있다.
            val account = saveSyncRequiredAccount("10000.0000")
            bankingQuery.respondSnapshot(
                accountId = account.id!!,
                balance = "12500.0000",
                transactions = listOf(externalTransaction("EXT-DEPOSIT-1", "DEPOSIT", "2500.0000")),
            )

            // when
            val result = service.reconcileBatch()

            // then: 증감 추측이 아니라 계정계 절대 잔액을 사용하고 누락 감사 이력을 한 건 생성한다.
            val saved = accountRepository.findById(account.id!!).orElseThrow()
            val histories = transactionHistoryRepository.findByAccountId(account.id!!)
            assertThat(result.resolved).isEqualTo(1)
            assertThat(saved.balance).isEqualByComparingTo("12500.0000")
            assertThat(saved.syncStatus).isEqualTo(AccountSyncStatus.SYNCED)
            assertThat(histories).hasSize(1)
            assertThat(histories.single().externalTransactionId).isEqualTo("EXT-DEPOSIT-1")
            assertThat(histories.single().type).isEqualTo(TransactionType.DEPOSIT)
        }

        @Test
        @DisplayName("포함형 cursor가 같은 외부 거래를 다시 돌려줘도 거래내역은 한 번만 저장한다")
        fun inclusiveCursorReplayIsIdempotent() {
            // given
            val account = saveSyncRequiredAccount("10000.0000")
            val external = externalTransaction("EXT-WITHDRAW-1", "WITHDRAWAL", "1000.0000")
            bankingQuery.respondSnapshot(account.id!!, "9000.0000", listOf(external))
            service.reconcileBatch()
            accountRepository.findById(account.id!!).orElseThrow().apply { markSyncRequired() }

            // when: 경계 시각의 레코드를 포함해 반환하는 cursor 정책으로 같은 외부 거래가 다시 도착한다.
            bankingQuery.respondSnapshot(account.id!!, "9000.0000", listOf(external))
            service.reconcileBatch()

            // then: externalTransactionId Unique 의미 키가 중복 이력과 중복 Outbox 생성을 막는다.
            assertThat(accountRepository.findById(account.id!!).orElseThrow().balance).isEqualByComparingTo("9000.0000")
            assertThat(transactionHistoryRepository.findByAccountId(account.id!!)).hasSize(1)
            assertThat(outboxRepository.count()).isEqualTo(1)
        }
    }

    @Nested
    @DisplayName("확정 결과 복구")
    inner class ConfirmedResultRecovery {
        @Test
        @DisplayName("UNKNOWN 송금이 SUCCEEDED로 확정되면 출금을 한 번 반영하고 계좌를 SYNCED로 만든다")
        fun repairsSucceededTransferOnce() {
            // given: Timeout 이벤트로 잔액은 아직 차감되지 않았고 UNKNOWN placeholder만 남아 있다.
            val account = saveSyncRequiredAccount("10000.0000")
            saveUnknown(account.id!!, 101L)
            bankingQuery.respond(state(account.id!!, 101L, BankingProjectionTransferStatus.SUCCEEDED))

            // when
            val result = service.reconcileBatch()

            // then: Banking 확정 결과를 한 번만 반영하고 같은 DB 트랜잭션에서 대사 상태를 정리한다.
            val savedAccount = accountRepository.findById(account.id!!).orElseThrow()
            val histories = transactionHistoryRepository.findByAccountId(account.id!!)
            assertThat(result.resolved).isEqualTo(1)
            assertThat(result.repairedEffects).isEqualTo(1)
            assertThat(bankingQuery.calls).isEqualTo(1)
            assertThat(savedAccount.balance).isEqualByComparingTo("9000.0000")
            assertThat(savedAccount.syncStatus).isEqualTo(AccountSyncStatus.SYNCED)
            assertThat(savedAccount.syncLockedUntil).isNull()
            assertThat(histories).hasSize(1)
            assertThat(histories.single().balanceChangeType).isEqualTo(BalanceChangeType.TRANSFER_OUT)
            assertThat(histories.single().status).isEqualTo(TransactionStatus.SUCCESS)
            assertThat(outboxRepository.count()).isEqualTo(1)
        }

        @Test
        @DisplayName("UNKNOWN 송금이 FAILED로 확정되면 잔액을 바꾸지 않고 실패 이력만 확정한다")
        fun confirmsFailedTransferWithoutBalanceChange() {
            // given
            val account = saveSyncRequiredAccount("10000.0000")
            saveUnknown(account.id!!, 102L)
            bankingQuery.respond(state(account.id!!, 102L, BankingProjectionTransferStatus.FAILED))

            // when
            service.reconcileBatch()

            // then
            val savedAccount = accountRepository.findById(account.id!!).orElseThrow()
            val history = transactionHistoryRepository.findByAccountId(account.id!!).single()
            assertThat(savedAccount.balance).isEqualByComparingTo("10000.0000")
            assertThat(savedAccount.syncStatus).isEqualTo(AccountSyncStatus.SYNCED)
            assertThat(history.status).isEqualTo(TransactionStatus.FAILED)
            assertThat(history.reconciliationRequired).isFalse()
            assertThat(outboxRepository.count()).isZero()
        }

        @Test
        @DisplayName("REVERSED가 먼저 도착한 계좌는 원 출금과 보상 입금을 복원해 순효과를 0으로 만든다")
        fun rebuildsOriginalAndReversalEffects() {
            // given: Reversed 이벤트만 먼저 소비되어 PENDING REVERSAL_IN이 남아 있다.
            val account = saveSyncRequiredAccount("10000.0000")
            transactionHistoryRepository.saveAndFlush(
                TransactionHistory(
                    accountId = account.id!!,
                    bankingTransferId = 103L,
                    balanceChangeType = BalanceChangeType.REVERSAL_IN,
                    publicTransferId = "REV-PUBLIC-103",
                    type = TransactionType.REVERSAL,
                    amount = BigDecimal("1000.0000"),
                    status = TransactionStatus.PENDING,
                    idempotencyKey = "banking-effect:103:${account.id}:REVERSAL_IN",
                    externalTransactionId = "REV-103",
                    reconciliationRequired = true,
                ),
            )
            bankingQuery.respond(state(account.id!!, 103L, BankingProjectionTransferStatus.REVERSED))

            // when
            service.reconcileBatch()

            // then: 두 감사 이력은 남지만 잔액은 원래 값이며 재실행할 대사 Row는 없다.
            val savedAccount = accountRepository.findById(account.id!!).orElseThrow()
            val histories = transactionHistoryRepository.findByAccountId(account.id!!)
            assertThat(savedAccount.balance).isEqualByComparingTo("10000.0000")
            assertThat(histories.mapNotNull { it.balanceChangeType })
                .containsExactlyInAnyOrder(BalanceChangeType.TRANSFER_OUT, BalanceChangeType.REVERSAL_IN)
            assertThat(histories).allMatch { it.status == TransactionStatus.SUCCESS && !it.reconciliationRequired }
            // 반전은 Work 분석 이벤트에서 제외하고 원 송금 성공만 보낸다.
            assertThat(outboxRepository.count()).isEqualTo(1)
        }
    }

    @Nested
    @DisplayName("재시도와 lease")
    inner class RetryAndLease {
        @Test
        @DisplayName("Banking 상태가 아직 UNKNOWN이면 잔액을 바꾸지 않고 다음 재시도 시각을 기록한다")
        fun keepsUnknownForRetry() {
            // given
            val account = saveSyncRequiredAccount("10000.0000")
            saveUnknown(account.id!!, 201L)
            bankingQuery.respond(state(account.id!!, 201L, BankingProjectionTransferStatus.UNKNOWN))

            // when
            val result = service.reconcileBatch()

            // then
            val saved = accountRepository.findById(account.id!!).orElseThrow()
            assertThat(result.failed).isEqualTo(1)
            assertThat(saved.balance).isEqualByComparingTo("10000.0000")
            assertThat(saved.syncStatus).isEqualTo(AccountSyncStatus.SYNC_REQUIRED)
            assertThat(saved.syncAttemptCount).isEqualTo(1)
            assertThat(saved.nextSyncAt).isNotNull()
            assertThat(saved.syncLockedUntil).isNull()
        }

        @Test
        @DisplayName("Asset와 Banking의 거래 금액이 다르면 잔액을 추측해서 고치지 않는다")
        fun amountMismatchDoesNotMutateBalance() {
            // given: Asset placeholder는 1,000원이지만 Banking Journal 응답은 2,000원이다.
            val account = saveSyncRequiredAccount("10000.0000")
            saveUnknown(account.id!!, 204L)
            bankingQuery.respond(
                state(account.id!!, 204L, BankingProjectionTransferStatus.SUCCEEDED)
                    .copy(amount = BigDecimal("2000.0000")),
            )

            // when
            service.reconcileBatch()

            // then: 불일치를 자동 보정하지 않고 원 Projection과 UNKNOWN 이력을 보존한다.
            val saved = accountRepository.findById(account.id!!).orElseThrow()
            val history = transactionHistoryRepository.findByAccountId(account.id!!).single()
            assertThat(saved.balance).isEqualByComparingTo("10000.0000")
            assertThat(saved.syncStatus).isEqualTo(AccountSyncStatus.SYNC_REQUIRED)
            assertThat(saved.syncAttemptCount).isEqualTo(1)
            assertThat(saved.lastSyncFailureReason).contains("금액이 일치하지 않습니다")
            assertThat(history.status).isEqualTo(TransactionStatus.UNKNOWN)
            assertThat(outboxRepository.count()).isZero()
        }

        @Test
        @DisplayName("살아 있는 lease가 있으면 다른 Worker가 Banking을 조회하지 않는다")
        fun activeLeasePreventsSecondClaim() {
            // given
            val account = saveSyncRequiredAccount("10000.0000").apply {
                syncLockedAt = LocalDateTime.now()
                syncLockedUntil = LocalDateTime.now().plusMinutes(1)
                syncLockOwner = "other-worker"
            }
            accountRepository.saveAndFlush(account)
            saveUnknown(account.id!!, 202L)

            // when
            val result = service.reconcileBatch()

            // then
            assertThat(result.scanned).isZero()
            assertThat(bankingQuery.calls).isZero()
        }

        @Test
        @DisplayName("만료된 lease는 새 Worker가 다시 선점해 복구한다")
        fun expiredLeaseCanBeRecovered() {
            // given: 이전 Worker가 종료되어 lease 시간이 이미 지났다.
            val account = saveSyncRequiredAccount("10000.0000").apply {
                syncLockedAt = LocalDateTime.now().minusMinutes(2)
                syncLockedUntil = LocalDateTime.now().minusMinutes(1)
                syncLockOwner = "dead-worker"
            }
            accountRepository.saveAndFlush(account)
            saveUnknown(account.id!!, 205L)
            bankingQuery.respond(state(account.id!!, 205L, BankingProjectionTransferStatus.FAILED))

            // when
            val result = service.reconcileBatch()

            // then
            val saved = accountRepository.findById(account.id!!).orElseThrow()
            assertThat(result.resolved).isEqualTo(1)
            assertThat(bankingQuery.calls).isEqualTo(1)
            assertThat(saved.syncStatus).isEqualTo(AccountSyncStatus.SYNCED)
            assertThat(saved.syncLockOwner).isNull()
        }

        @Test
        @DisplayName("조건부 update는 같은 후보에 대한 두 번째 lease 선점을 차단한다")
        fun conditionalClaimIsFinalConcurrencyGuard() {
            // given
            val account = saveSyncRequiredAccount("10000.0000")
            val now = LocalDateTime.now()

            // when: 두 Worker가 같은 후보 목록을 읽었다고 가정하고 같은 Row를 차례로 선점한다.
            val first = transactionTemplate.execute {
                accountRepository.claimProjectionReconciliation(account.id!!, now, now.minusHours(6), now.plusSeconds(30), "worker-a")
            }
            val second = transactionTemplate.execute {
                accountRepository.claimProjectionReconciliation(account.id!!, now, now.minusHours(6), now.plusSeconds(30), "worker-b")
            }

            // then: DB update 조건이 최종 방어선이 되어 한 Worker만 lease를 얻는다.
            assertThat(first).isEqualTo(1)
            assertThat(second).isZero()
        }

        @Test
        @DisplayName("최대 재시도 횟수를 소진하면 자동 대사 후보에서 제외된다")
        fun retryExhaustionStopsAutomaticAttempts() {
            // given
            properties.maxAttempts = 1
            val account = saveSyncRequiredAccount("10000.0000")
            saveUnknown(account.id!!, 203L)
            bankingQuery.failWith(IllegalStateException("Banking unavailable"))

            // when
            service.reconcileBatch()

            // then
            val saved = accountRepository.findById(account.id!!).orElseThrow()
            assertThat(saved.syncRetryExhausted).isTrue()
            assertThat(saved.nextSyncAt).isNull()
            assertThat(
                accountRepository.findProjectionReconciliationCandidateIds(
                    LocalDateTime.now(),
                    LocalDateTime.now().minusHours(6),
                    PageRequest.of(0, 10),
                ),
            ).doesNotContain(account.id)
            assertThat(meterRegistry.find("asset.reconciliation.projection.retry.exhausted.total").counter()!!.count())
                .isEqualTo(1.0)
        }
    }

    @Nested
    @DisplayName("사용자 계좌 Refresh")
    inner class ManualAccountRefresh {
        @Test
        @DisplayName("Banking Snapshot의 잔액·상태·새 거래내역을 하나의 Projection으로 반영한다")
        fun refreshAppliesWholeSnapshot() {
            // given: Asset에는 이전 잔액만 있고 Mock Banking Snapshot에는 새 출금이 포함되어 있다.
            val account = accountRepository.saveAndFlush(Account(userId = "user-1", balance = BigDecimal("10000")))
            val snapshot = BankingAccountProjectionSnapshot(
                accountId = account.id!!,
                principalId = "user-1",
                balance = BigDecimal("9000"),
                currency = "KRW",
                accountStatus = "ACTIVE",
                asOf = Instant.parse("2026-01-01T00:01:00Z"),
                transactions = listOf(externalTransaction("EXT-REFRESH-1", "WITHDRAWAL", "1000")),
                bankCode = "088",
                bankName = "테스트 은행",
            )

            // when
            transferService.refreshProjectionFromSnapshot(account.id!!, "user-1", account.lastEventAppliedAt, snapshot)

            // then: 잔액뿐 아니라 이력과 다음 증분 조회 cursor까지 함께 최신화되어야 한다.
            val refreshed = accountRepository.findById(account.id!!).orElseThrow()
            assertThat(refreshed.balance).isEqualByComparingTo("9000")
            assertThat(refreshed.bankCode).isEqualTo("088")
            assertThat(refreshed.bankName).isEqualTo("테스트 은행")
            assertThat(refreshed.syncStatus).isEqualTo(AccountSyncStatus.SYNCED)
            assertThat(refreshed.snapshotCursorAt).isEqualTo(LocalDateTime.ofInstant(snapshot.asOf, java.time.ZoneOffset.UTC))
            assertThat(transactionHistoryRepository.findByAccountId(account.id!!))
                .singleElement()
                .extracting<String> { it.externalTransactionId }
                .isEqualTo("EXT-REFRESH-1")

            // 구버전 Banking의 빈 은행 정보로 재조회해도 이미 확인한 값은 지우지 않는다.
            transferService.refreshProjectionFromSnapshot(
                account.id!!, "user-1", refreshed.lastEventAppliedAt,
                snapshot.copy(asOf = snapshot.asOf.plusSeconds(1), bankCode = null, bankName = null),
            )
            assertThat(accountRepository.findById(account.id!!).orElseThrow().bankCode).isEqualTo("088")
            assertThat(accountRepository.findById(account.id!!).orElseThrow().bankName).isEqualTo("테스트 은행")
        }

        @Test
        @DisplayName("Snapshot 조회 중 Kafka 이벤트가 반영되면 오래된 Refresh 결과를 저장하지 않는다")
        fun eventAppliedDuringRefreshRejectsStaleSnapshot() {
            // given: 원격 조회를 시작할 때는 이벤트가 없었지만, Snapshot 저장 전 새 이벤트가 반영되었다.
            val account = accountRepository.saveAndFlush(Account(userId = "user-1", balance = BigDecimal("10000")))
            val observedLastEventAppliedAt = account.lastEventAppliedAt
            account.applyDebit(BigDecimal("1000"))
            account.recordEventApplied()
            accountRepository.saveAndFlush(account)
            val staleSnapshot = BankingAccountProjectionSnapshot(
                accountId = account.id!!,
                principalId = "user-1",
                balance = BigDecimal("10000"),
                currency = "KRW",
                accountStatus = "ACTIVE",
                asOf = Instant.now(),
                transactions = emptyList(),
            )

            // when / then: 최신 9,000원을 과거 Snapshot의 10,000원으로 되돌리면 안 된다.
            assertThatThrownBy {
                transferService.refreshProjectionFromSnapshot(
                    account.id!!,
                    "user-1",
                    observedLastEventAppliedAt,
                    staleSnapshot,
                )
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("새 금융 이벤트")
            assertThat(accountRepository.findById(account.id!!).orElseThrow().balance).isEqualByComparingTo("9000")
        }
    }

    private fun saveSyncRequiredAccount(balance: String): Account {
        val account = Account(userId = "user-1", balance = BigDecimal(balance))
        account.markSyncRequired()
        return accountRepository.saveAndFlush(account)
    }

    private fun saveUnknown(accountId: Long, transferId: Long) {
        transactionHistoryRepository.saveAndFlush(
            TransactionHistory(
                accountId = accountId,
                bankingTransferId = transferId,
                publicTransferId = "UNKNOWN-$transferId",
                type = TransactionType.TRANSFER,
                amount = BigDecimal("1000.0000"),
                status = TransactionStatus.UNKNOWN,
                idempotencyKey = "banking-failed:$transferId:$accountId",
                visibleToUser = false,
                reconciliationRequired = true,
            ),
        )
    }

    private fun state(
        accountId: Long,
        transferId: Long,
        status: BankingProjectionTransferStatus,
    ) = BankingTransferProjectionState(
        transferId = transferId,
        principalId = "user-1",
        accountId = accountId,
        amount = BigDecimal("1000.0000"),
        currency = "KRW",
        status = status,
        externalTransactionId = "EXT-$transferId",
        externalReversalTransactionId = "REV-$transferId",
        failureCode = if (status == BankingProjectionTransferStatus.FAILED) "DECLINED" else null,
        failureReason = if (status == BankingProjectionTransferStatus.FAILED) "잔액 부족" else null,
        reversalReason = if (status == BankingProjectionTransferStatus.REVERSED) "외부 반전 완료" else null,
    )

    private fun externalTransaction(
        externalTransactionId: String,
        type: String,
        amount: String,
    ) = BankingExternalProjectionTransaction(
        externalTransactionId = externalTransactionId,
        type = type,
        amount = BigDecimal(amount),
        currency = "KRW",
        occurredAt = Instant.parse("2026-01-01T00:00:00Z"),
    )

    private class FakeBankingQuery : BankingTransferQuery, BankingAccountSnapshotQuery {
        var afterSnapshot: (() -> Unit)? = null
        private var response: List<BankingTransferProjectionState> = emptyList()
        private var snapshot: BankingAccountProjectionSnapshot? = null
        private var failure: RuntimeException? = null
        var calls: Int = 0
            private set

        fun respond(vararg states: BankingTransferProjectionState) {
            response = states.toList()
            failure = null
        }

        fun failWith(exception: RuntimeException) {
            failure = exception
        }

        fun respondSnapshot(
            accountId: Long,
            balance: String,
            transactions: List<BankingExternalProjectionTransaction>,
        ) {
            snapshot = BankingAccountProjectionSnapshot(
                accountId = accountId,
                principalId = "user-1",
                balance = BigDecimal(balance),
                currency = "KRW",
                accountStatus = "ACTIVE",
                asOf = Instant.parse("2026-01-01T00:01:00Z"),
                transactions = transactions,
            )
        }

        fun clear() {
            response = emptyList()
            failure = null
            calls = 0
            snapshot = null
            afterSnapshot = null
        }

        override fun getTransferStates(accountId: Long, transferIds: Collection<Long>): List<BankingTransferProjectionState> {
            calls += 1
            failure?.let { throw it }
            return response
        }

        override fun getAccountSnapshot(accountId: Long, cursor: Instant): BankingAccountProjectionSnapshot {
            failure?.let { throw it }
            afterSnapshot?.invoke()
            return snapshot ?: BankingAccountProjectionSnapshot(
                accountId = accountId,
                principalId = "user-1",
                balance = when (response.singleOrNull()?.status) {
                    BankingProjectionTransferStatus.SUCCEEDED -> BigDecimal("9000.0000")
                    else -> BigDecimal("10000.0000")
                },
                currency = "KRW",
                accountStatus = "ACTIVE",
                asOf = Instant.now(),
                transactions = response.filter { it.status == BankingProjectionTransferStatus.SUCCEEDED ||
                    it.status == BankingProjectionTransferStatus.REVERSED }.map { state ->
                    BankingExternalProjectionTransaction(state.externalTransactionId!!, "TRANSFER_OUT", state.amount,
                        "KRW", Instant.parse("2026-01-01T00:00:00Z"))
                },
            )
        }
    }
}
