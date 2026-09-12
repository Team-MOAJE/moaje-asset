package com.moaje.asset.transfer.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.moaje.asset.account.domain.Account
import com.moaje.asset.account.persistence.AccountRepository
import com.moaje.asset.common.id.IdGenerator
import com.moaje.asset.outbox.persistence.TransactionalOutboxRepository
import com.moaje.asset.transfer.application.usecase.ApplyBankingTransferCompletedCommand
import com.moaje.asset.transfer.application.usecase.ApplyBankingTransferFailedCommand
import com.moaje.asset.transfer.application.usecase.ApplyBankingTransferReversedCommand
import com.moaje.asset.account.domain.AccountSyncStatus
import com.moaje.asset.transfer.domain.BalanceChangeType
import com.moaje.asset.transfer.domain.TransactionStatus
import com.moaje.asset.transfer.persistence.ProcessedEventRepository
import com.moaje.asset.transfer.persistence.TransactionHistoryRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.math.BigDecimal
import java.time.Instant

@DataJpaTest
class TransferApplicationServiceIdempotencyJpaTest(
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val transactionHistoryRepository: TransactionHistoryRepository,
    @Autowired private val processedEventRepository: ProcessedEventRepository,
    @Autowired private val transactionalOutboxRepository: TransactionalOutboxRepository,
) {
    private lateinit var service: TransferApplicationService

    @BeforeEach
    fun setUp() {
        service = TransferApplicationService(
            accountRepository = accountRepository,
            transactionHistoryRepository = transactionHistoryRepository,
            processedEventRepository = processedEventRepository,
            transactionalOutboxRepository = transactionalOutboxRepository,
            idGenerator = IdGenerator(),
            objectMapper = ObjectMapper()
                .registerKotlinModule()
                .registerModule(JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS),
        )
    }

    @Test
    @DisplayName("동일 eventId 성공 이벤트를 두 번 소비해도 처리 이력과 거래내역은 한 건이고 잔액은 한 번만 감소한다")
    fun sameEventIdIsProcessedOnce() {
        // given
        val account = saveAccount(balance = "10000.0000")
        val command = completedCommand(eventId = "event-1", transferId = 100L, accountId = account.id!!)

        // when
        service.applyBankingTransferCompleted(command)
        service.applyBankingTransferCompleted(command)

        // then
        val savedAccount = accountRepository.findById(account.id!!).orElseThrow()
        assertThat(processedEventRepository.count()).isEqualTo(1)
        assertThat(transactionHistoryRepository.count()).isEqualTo(1)
        assertThat(savedAccount.balance).isEqualByComparingTo("9000.0000")
    }

    @Test
    @DisplayName("같은 금융 효과가 다른 eventId로 재발행되어도 이벤트는 각각 기록하고 잔액 변경은 한 번만 반영한다")
    fun sameBusinessEffectWithDifferentEventIdIsAppliedOnce() {
        // given
        val account = saveAccount(balance = "10000.0000")
        val first = completedCommand(eventId = "event-1", transferId = 100L, accountId = account.id!!)
        val replayed = completedCommand(eventId = "event-2", transferId = 100L, accountId = account.id!!)

        // when
        service.applyBankingTransferCompleted(first)
        service.applyBankingTransferCompleted(replayed)

        // then
        val savedAccount = accountRepository.findById(account.id!!).orElseThrow()
        val histories = transactionHistoryRepository.findByAccountId(account.id!!)
        assertThat(processedEventRepository.count()).isEqualTo(2)
        assertThat(histories).hasSize(1)
        assertThat(histories.single().balanceChangeType).isEqualTo(BalanceChangeType.TRANSFER_OUT)
        assertThat(savedAccount.balance).isEqualByComparingTo("9000.0000")
    }

    @Test
    @DisplayName("실패 이벤트 중복 소비는 잔액을 변경하지 않고 실패 거래를 중복 생성하지 않는다")
    fun duplicatedFailedEventDoesNotChangeBalanceOrCreateDuplicatedFailureHistory() {
        // given
        val account = saveAccount(balance = "10000.0000")
        val first = failedCommand(eventId = "event-1", transferId = 101L, accountId = account.id!!)
        val replayed = failedCommand(eventId = "event-2", transferId = 101L, accountId = account.id!!)

        // when
        service.applyBankingTransferFailed(first)
        service.applyBankingTransferFailed(replayed)

        // then
        val savedAccount = accountRepository.findById(account.id!!).orElseThrow()
        val histories = transactionHistoryRepository.findByAccountId(account.id!!)
        assertThat(processedEventRepository.count()).isEqualTo(2)
        assertThat(histories).hasSize(1)
        assertThat(histories.single().status).isEqualTo(TransactionStatus.FAILED)
        assertThat(savedAccount.balance).isEqualByComparingTo("10000.0000")
    }

    @Test
    @DisplayName("같은 transferId라도 계좌별 balanceChangeType이 다르면 서로 다른 정상 금융 효과로 저장할 수 있다")
    fun sameTransferIdWithDifferentAccountAndBalanceChangeTypeCanCoexist() {
        // given
        val source = saveAccount(userId = "user-1", balance = "10000.0000")
        val destination = saveAccount(userId = "user-2", balance = "3000.0000")

        // when
        service.applyBankingTransferCompleted(completedCommand(eventId = "event-1", transferId = 102L, accountId = source.id!!))
        transactionHistoryRepository.saveAndFlush(
            com.moaje.asset.transfer.domain.TransactionHistory(
                accountId = destination.id!!,
                bankingTransferId = 102L,
                balanceChangeType = BalanceChangeType.TRANSFER_IN,
                publicTransferId = "PUBLIC-IN-102",
                type = com.moaje.asset.transfer.domain.TransactionType.TRANSFER,
                amount = BigDecimal("1000.0000"),
                status = TransactionStatus.SUCCESS,
                idempotencyKey = "banking-effect:102:${destination.id}:TRANSFER_IN",
            ),
        )

        // then
        assertThat(transactionHistoryRepository.findByAccountId(source.id!!)).hasSize(1)
        assertThat(transactionHistoryRepository.findByAccountId(destination.id!!)).hasSize(1)
    }

    @Test
    @DisplayName("송금 완료 후 보상 이벤트를 소비하면 원 출금 이력을 보존하고 잔액을 원상 복구한다")
    fun completedThenReversedRestoresProjectionWithSeparateHistory() {
        // given: Asset 잔액 10,000원에서 1,000원 송금 완료 이벤트가 먼저 도착한다.
        val account = saveAccount(balance = "10000.0000")
        service.applyBankingTransferCompleted(completedCommand("completed-1", 200L, account.id!!))

        // when: Mock Banking에서 이미 확정된 보상 이벤트를 소비한다.
        service.applyBankingTransferReversed(reversedCommand("reversed-1", 200L, account.id!!))

        // then: 원거래를 지우지 않고 REVERSAL_IN을 추가해 최종 잔액만 원래 값으로 돌아온다.
        val savedAccount = accountRepository.findById(account.id!!).orElseThrow()
        val histories = transactionHistoryRepository.findByAccountId(account.id!!)
        assertThat(savedAccount.balance).isEqualByComparingTo("10000.0000")
        assertThat(histories.map { it.balanceChangeType })
            .containsExactlyInAnyOrder(BalanceChangeType.TRANSFER_OUT, BalanceChangeType.REVERSAL_IN)
        assertThat(histories).allMatch { it.status == TransactionStatus.SUCCESS }
    }

    @Test
    @DisplayName("같은 보상 효과가 다른 eventId로 재발행되어도 잔액은 한 번만 복구된다")
    fun sameReversalEffectWithDifferentEventIdIsAppliedOnce() {
        // given: 원송금이 한 번 반영된 계좌가 있다.
        val account = saveAccount(balance = "10000.0000")
        service.applyBankingTransferCompleted(completedCommand("completed-1", 201L, account.id!!))

        // when: 같은 보상 금융 효과가 서로 다른 eventId로 두 번 전달된다.
        service.applyBankingTransferReversed(reversedCommand("reversed-1", 201L, account.id!!))
        service.applyBankingTransferReversed(reversedCommand("reversed-2", 201L, account.id!!))

        // then: eventId는 모두 처리 기록되지만 REVERSAL_IN과 잔액 증가는 한 번뿐이다.
        val savedAccount = accountRepository.findById(account.id!!).orElseThrow()
        val histories = transactionHistoryRepository.findByAccountId(account.id!!)
        assertThat(savedAccount.balance).isEqualByComparingTo("10000.0000")
        assertThat(histories.count { it.balanceChangeType == BalanceChangeType.REVERSAL_IN }).isEqualTo(1)
        assertThat(processedEventRepository.count()).isEqualTo(3)
    }

    @Test
    @DisplayName("보상 이벤트가 송금 완료보다 먼저 도착해도 선입금하지 않고 최종 잔액을 복구한다")
    fun reversedBeforeCompletedWaitsWithoutInflatingBalance() {
        // given: 서로 다른 Kafka Topic의 순서가 뒤집혀 보상 이벤트가 먼저 도착한다.
        val account = saveAccount(balance = "10000.0000")

        // when: 보상을 먼저 소비한 뒤 원송금 완료를 소비한다.
        service.applyBankingTransferReversed(reversedCommand("reversed-1", 202L, account.id!!))

        // then: 원 출금이 없을 때는 잔액을 올리지 않고 PENDING 보상과 SYNC_REQUIRED만 남긴다.
        val waitingAccount = accountRepository.findById(account.id!!).orElseThrow()
        val waitingReversal = transactionHistoryRepository.findByBankingTransferIdAndAccountIdAndBalanceChangeType(
            202L,
            account.id!!,
            BalanceChangeType.REVERSAL_IN,
        )!!
        assertThat(waitingAccount.balance).isEqualByComparingTo("10000.0000")
        assertThat(waitingAccount.syncStatus).isEqualTo(AccountSyncStatus.SYNC_REQUIRED)
        assertThat(waitingReversal.status).isEqualTo(TransactionStatus.PENDING)
        assertThat(waitingReversal.reconciliationRequired).isTrue()

        service.applyBankingTransferCompleted(completedCommand("completed-1", 202L, account.id!!))

        // then: 완료 이벤트 트랜잭션에서 출금과 대기 보상을 함께 적용해 외부 최종 결과와 같은 잔액이 된다.
        val completedAccount = accountRepository.findById(account.id!!).orElseThrow()
        val histories = transactionHistoryRepository.findByAccountId(account.id!!)
        assertThat(completedAccount.balance).isEqualByComparingTo("10000.0000")
        // 이벤트 두 건의 일치만으로 ATM 등 모든 외부 거래까지 검증했다고 판단하지 않는다.
        assertThat(completedAccount.syncStatus).isEqualTo(AccountSyncStatus.SYNC_REQUIRED)
        assertThat(histories).hasSize(2)
        assertThat(histories).allMatch { it.status == TransactionStatus.SUCCESS }
        assertThat(histories).allMatch { !it.reconciliationRequired }
        // Work는 계정계 원시각 확인 후 발행하며 반전은 발행 대상이 아니다.
        assertThat(transactionalOutboxRepository.count()).isZero()
    }

    private fun saveAccount(
        userId: String = "user-1",
        balance: String,
    ): Account {
        return accountRepository.saveAndFlush(
            Account(
                userId = userId,
                balance = BigDecimal(balance),
            ),
        )
    }

    private fun completedCommand(
        eventId: String,
        transferId: Long,
        accountId: Long,
    ): ApplyBankingTransferCompletedCommand {
        return ApplyBankingTransferCompletedCommand(
            eventId = eventId,
            bankingTransferId = transferId,
            userId = "user-1",
            accountId = accountId,
            amount = BigDecimal("1000.0000"),
            externalTransactionId = "EXT-$transferId",
            occurredAt = Instant.parse("2026-08-21T00:00:00Z"),
        )
    }

    private fun failedCommand(
        eventId: String,
        transferId: Long,
        accountId: Long,
    ): ApplyBankingTransferFailedCommand {
        return ApplyBankingTransferFailedCommand(
            eventId = eventId,
            bankingTransferId = transferId,
            userId = "user-1",
            accountId = accountId,
            amount = BigDecimal("1000.0000"),
            reason = "failed",
            failureStatus = "FAILED",
            occurredAt = Instant.parse("2026-08-21T00:00:00Z"),
        )
    }

    private fun reversedCommand(
        eventId: String,
        transferId: Long,
        accountId: Long,
    ): ApplyBankingTransferReversedCommand {
        return ApplyBankingTransferReversedCommand(
            eventId = eventId,
            bankingTransferId = transferId,
            userId = "user-1",
            accountId = accountId,
            amount = BigDecimal("1000.0000"),
            originalExternalTransactionId = "EXT-$transferId",
            externalReversalTransactionId = "REV-$transferId",
            reason = "이미 완료된 외부 보상 거래",
            occurredAt = Instant.parse("2026-09-07T00:00:00Z"),
        )
    }
}
