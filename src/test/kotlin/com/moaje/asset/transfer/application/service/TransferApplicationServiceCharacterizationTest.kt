package com.moaje.asset.transfer.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.moaje.asset.account.domain.Account
import com.moaje.asset.account.domain.AccountSyncStatus
import com.moaje.asset.account.persistence.AccountRepository
import com.moaje.asset.common.id.IdGenerator
import com.moaje.asset.outbox.domain.TransactionalOutbox
import com.moaje.asset.outbox.persistence.TransactionalOutboxRepository
import com.moaje.asset.transfer.application.usecase.ApplyBankingTransferCompletedCommand
import com.moaje.asset.transfer.application.usecase.ApplyBankingTransferFailedCommand
import com.moaje.asset.transfer.domain.BalanceChangeType
import com.moaje.asset.transfer.domain.ProcessedEvent
import com.moaje.asset.transfer.domain.TransactionHistory
import com.moaje.asset.transfer.domain.TransactionStatus
import com.moaje.asset.transfer.persistence.ProcessedEventRepository
import com.moaje.asset.transfer.persistence.TransactionHistoryRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import java.math.BigDecimal
import java.time.Instant

class TransferApplicationServiceCharacterizationTest {
    private val accountRepository = Mockito.mock(AccountRepository::class.java)
    private val transactionHistoryRepository = Mockito.mock(TransactionHistoryRepository::class.java)
    private val processedEventRepository = Mockito.mock(ProcessedEventRepository::class.java)
    private val transactionalOutboxRepository = Mockito.mock(TransactionalOutboxRepository::class.java)
    private val service = TransferApplicationService(
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

    @Test
    @DisplayName("동일 eventId 성공 이벤트 중복 전달은 처리 이력 기준으로 한 번만 차감한다")
    fun duplicateCompletedEventIsIgnoredByExternalTransactionId() {
        val account = account(balance = "10000.0000")
        Mockito.`when`(processedEventRepository.existsById("event-1"))
            .thenReturn(false)
            .thenReturn(false)
            .thenReturn(true)
        Mockito.`when`(accountRepository.findByIdForUpdate(1L))
            .thenReturn(account)
        Mockito.`when`(
            transactionHistoryRepository.existsByBankingTransferIdAndAccountIdAndBalanceChangeType(
                100L,
                1L,
                BalanceChangeType.TRANSFER_OUT,
            ),
        ).thenReturn(false)
        Mockito.`when`(transactionHistoryRepository.save(Mockito.any(TransactionHistory::class.java)))
            .thenAnswer { (it.arguments[0] as TransactionHistory).apply { id = 10L } }
        Mockito.`when`(processedEventRepository.save(Mockito.any(ProcessedEvent::class.java)))
            .thenAnswer { it.arguments[0] }
        Mockito.`when`(transactionalOutboxRepository.save(Mockito.any(TransactionalOutbox::class.java)))
            .thenAnswer { it.arguments[0] }

        service.applyBankingTransferCompleted(completedCommand(eventId = "event-1", externalTransactionId = "EXT-1"))
        service.applyBankingTransferCompleted(completedCommand(eventId = "event-1", externalTransactionId = "EXT-1"))

        assertThat(account.balance).isEqualByComparingTo("9000.0000")
        Mockito.verify(accountRepository, Mockito.times(1)).findByIdForUpdate(1L)
        Mockito.verify(transactionHistoryRepository, Mockito.times(1)).save(Mockito.any(TransactionHistory::class.java))
        Mockito.verify(processedEventRepository, Mockito.times(1)).save(Mockito.any(ProcessedEvent::class.java))
    }

    @Test
    @DisplayName("같은 금융 효과가 다른 eventId로 재발행되면 eventId는 기록하고 잔액은 중복 반영하지 않는다")
    fun sameCompletedTransferWithDifferentEventIdIsIgnoredWhenExternalTransactionIdMatches() {
        val account = account(balance = "10000.0000")
        Mockito.`when`(processedEventRepository.existsById(Mockito.anyString()))
            .thenReturn(false)
        Mockito.`when`(accountRepository.findByIdForUpdate(1L))
            .thenReturn(account)
        Mockito.`when`(
            transactionHistoryRepository.existsByBankingTransferIdAndAccountIdAndBalanceChangeType(
                100L,
                1L,
                BalanceChangeType.TRANSFER_OUT,
            ),
        )
            .thenReturn(false)
            .thenReturn(true)
        Mockito.`when`(transactionHistoryRepository.save(Mockito.any(TransactionHistory::class.java)))
            .thenAnswer { (it.arguments[0] as TransactionHistory).apply { id = 10L } }
        Mockito.`when`(processedEventRepository.save(Mockito.any(ProcessedEvent::class.java)))
            .thenAnswer { it.arguments[0] }
        Mockito.`when`(transactionalOutboxRepository.save(Mockito.any(TransactionalOutbox::class.java)))
            .thenAnswer { it.arguments[0] }

        service.applyBankingTransferCompleted(completedCommand(eventId = "event-1", externalTransactionId = "EXT-1"))
        service.applyBankingTransferCompleted(completedCommand(eventId = "event-2", externalTransactionId = "EXT-1"))

        assertThat(account.balance).isEqualByComparingTo("9000.0000")
        Mockito.verify(transactionHistoryRepository, Mockito.times(1)).save(Mockito.any(TransactionHistory::class.java))
        Mockito.verify(processedEventRepository, Mockito.times(2)).save(Mockito.any(ProcessedEvent::class.java))
    }

    @Test
    @DisplayName("실패 이벤트는 잔액을 바꾸지 않고 같은 거래 실패 row를 중복 생성하지 않는다")
    fun sameFailedTransferWithDifferentEventIdCanCreateDuplicatedFailureRows() {
        val account = account(balance = "10000.0000")
        val existing = TransactionHistory(
            accountId = 1L,
            bankingTransferId = 100L,
            publicTransferId = "PUBLIC-FAILED",
            type = com.moaje.asset.transfer.domain.TransactionType.TRANSFER,
            amount = BigDecimal("1000.0000"),
            status = TransactionStatus.FAILED,
            idempotencyKey = "banking-failed:100:1",
        )
        Mockito.`when`(processedEventRepository.existsById(Mockito.anyString()))
            .thenReturn(false)
        Mockito.`when`(transactionHistoryRepository.findByIdempotencyKey(Mockito.anyString()))
            .thenReturn(null)
            .thenReturn(null)
            .thenReturn(existing)
        Mockito.`when`(accountRepository.findByIdForUpdate(1L))
            .thenReturn(account)
        Mockito.`when`(transactionHistoryRepository.save(Mockito.any(TransactionHistory::class.java)))
            .thenAnswer { (it.arguments[0] as TransactionHistory).apply { id = 10L } }
        Mockito.`when`(processedEventRepository.save(Mockito.any(ProcessedEvent::class.java)))
            .thenAnswer { it.arguments[0] }

        service.applyBankingTransferFailed(failedCommand(eventId = "event-1", failureStatus = "FAILED"))
        service.applyBankingTransferFailed(failedCommand(eventId = "event-2", failureStatus = "FAILED"))

        assertThat(account.balance).isEqualByComparingTo("10000.0000")
        Mockito.verify(transactionHistoryRepository, Mockito.times(1)).save(Mockito.any(TransactionHistory::class.java))
        Mockito.verify(processedEventRepository, Mockito.times(2)).save(Mockito.any(ProcessedEvent::class.java))
    }

    @Test
    @DisplayName("현재 TIMEOUT 실패 이벤트는 UNKNOWN 거래와 SYNC_REQUIRED 계좌 상태로 수렴한다")
    fun timeoutFailedEventMarksUnknownAndSyncRequired() {
        val account = account(balance = "10000.0000")
        var savedTransaction: TransactionHistory? = null
        Mockito.`when`(processedEventRepository.existsById("event-1"))
            .thenReturn(false)
            .thenReturn(false)
        Mockito.`when`(transactionHistoryRepository.findByIdempotencyKey(Mockito.anyString()))
            .thenReturn(null)
        Mockito.`when`(accountRepository.findByIdForUpdate(1L))
            .thenReturn(account)
        Mockito.`when`(transactionHistoryRepository.save(Mockito.any(TransactionHistory::class.java)))
            .thenAnswer {
                savedTransaction = (it.arguments[0] as TransactionHistory).apply { id = 10L }
                savedTransaction
            }
        Mockito.`when`(processedEventRepository.save(Mockito.any(ProcessedEvent::class.java)))
            .thenAnswer { it.arguments[0] }

        service.applyBankingTransferFailed(failedCommand(eventId = "event-1", failureStatus = "TIMEOUT"))

        assertThat(savedTransaction?.status).isEqualTo(TransactionStatus.UNKNOWN)
        assertThat(savedTransaction?.reconciliationRequired).isTrue()
        assertThat(account.syncStatus).isEqualTo(AccountSyncStatus.SYNC_REQUIRED)
    }

    private fun completedCommand(
        eventId: String,
        externalTransactionId: String,
    ): ApplyBankingTransferCompletedCommand {
        return ApplyBankingTransferCompletedCommand(
            eventId = eventId,
            bankingTransferId = 100L,
            userId = "user-1",
            accountId = 1L,
            amount = BigDecimal("1000.0000"),
            externalTransactionId = externalTransactionId,
            occurredAt = Instant.parse("2026-05-27T00:00:00Z"),
        )
    }

    private fun failedCommand(
        eventId: String,
        failureStatus: String,
    ): ApplyBankingTransferFailedCommand {
        return ApplyBankingTransferFailedCommand(
            eventId = eventId,
            bankingTransferId = 100L,
            userId = "user-1",
            accountId = 1L,
            amount = BigDecimal("1000.0000"),
            reason = failureStatus,
            failureStatus = failureStatus,
            occurredAt = Instant.parse("2026-05-27T00:00:00Z"),
        )
    }

    private fun account(balance: String): Account {
        return Account(
            id = 1L,
            userId = "user-1",
            balance = BigDecimal(balance),
        )
    }
}
