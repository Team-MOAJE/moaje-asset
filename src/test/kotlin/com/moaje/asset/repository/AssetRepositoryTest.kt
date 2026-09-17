package com.moaje.asset.repository

import com.moaje.asset.account.persistence.AccountRepository
import com.moaje.asset.account.domain.Account
import com.moaje.asset.cashflow.persistence.DailyCashflowSnapshotRepository
import com.moaje.asset.cashflow.domain.DailyCashflowSnapshot
import com.moaje.asset.outbox.persistence.TransactionalOutboxRepository
import com.moaje.asset.outbox.domain.TransactionalOutbox
import com.moaje.asset.transfer.persistence.TransactionHistoryRepository
import com.moaje.asset.transfer.domain.TransactionCategory
import com.moaje.asset.transfer.domain.TransactionHistory
import com.moaje.asset.transfer.domain.TransactionStatus
import com.moaje.asset.transfer.domain.TransactionType
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DataJpaTest
class AssetRepositoryTest(
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val transactionHistoryRepository: TransactionHistoryRepository,
    @Autowired private val transactionalOutboxRepository: TransactionalOutboxRepository,
    @Autowired private val dailyCashflowSnapshotRepository: DailyCashflowSnapshotRepository,
) {
    @Test
    @DisplayName("userId로 계좌 정보를 저장하고 조회한다")
    fun saveAccountAndFindByUserId() {
        val saved = accountRepository.save(
            Account(
                userId = "1",
                balance = BigDecimal("10000.0000"),
            ),
        )

        val accounts = accountRepository.findByUserId("1")

        assertNotNull(saved.id)
        assertEquals(1, accounts.size)
        assertEquals(saved.id, accounts.first().id)
    }

    @Test
    @DisplayName("거래 내역을 저장하고 멱등키로 조회한다")
    fun saveTransactionHistoryAndFindByIdempotencyKey() {
        val account = accountRepository.save(
            Account(
                userId = "1",
                balance = BigDecimal("10000.0000"),
            ),
        )

        val saved = transactionHistoryRepository.save(
            TransactionHistory(
                accountId = account.id ?: error("account id is required"),
                publicTransferId = "MOAJE-BNK-20260527-0123456789ABC",
                type = TransactionType.TRANSFER,
                amount = BigDecimal("5000.0000"),
                targetToken = "target-token",
                status = TransactionStatus.PENDING,
                idempotencyKey = "idem-1",
            ),
        )

        val found = transactionHistoryRepository.findByIdempotencyKey("idem-1")

        assertNotNull(saved.id)
        assertEquals(saved.id, found?.id)
    }

    @Test
    @DisplayName("거래내역의 가맹점명과 카테고리를 저장하고 조회한다")
    fun saveTransactionMerchantAndCategory() {
        // given: 소비 집계에 사용할 가맹점과 Asset 분류 결과가 확정된 거래다.
        val saved = transactionHistoryRepository.saveAndFlush(
            TransactionHistory(
                accountId = 1L,
                publicTransferId = "MOAJE-BNK-CATEGORY-TEST",
                type = TransactionType.WITHDRAW,
                amount = BigDecimal("6500.0000"),
                status = TransactionStatus.SUCCESS,
                idempotencyKey = "category-idem-1",
                merchantName = "모아제 카페",
                categoryCode = TransactionCategory.CAFE,
            ),
        )

        // when
        val found = transactionHistoryRepository.findById(saved.id!!).orElseThrow()

        // then: 두 필드가 DB 컬럼과 Enum 문자열에 올바르게 매핑되어야 한다.
        assertEquals("모아제 카페", found.merchantName)
        assertEquals(TransactionCategory.CAFE, found.categoryCode)
    }

    @Test
    @DisplayName("발행되지 않은 outbox 이벤트를 오래된 순서로 조회한다")
    fun findUnpublishedOutboxEvents() {
        transactionalOutboxRepository.save(
            TransactionalOutbox(
                aggregateType = "KFTC_TRANSFER_REQUEST",
                aggregateId = 1L,
                payload = "{}",
            ),
        )

        val events = transactionalOutboxRepository.findTop100ByPublishedFalseOrderByCreatedAtAsc()

        assertEquals(1, events.size)
        assertTrue(!events.first().published)
    }

    @Test
    @DisplayName("일일 생활비 스냅샷을 저장하고 사용자와 날짜로 조회한다")
    fun saveDailyCashflowSnapshotAndFindByUserIdAndSnapshotDate() {
        val snapshotDate = LocalDate.of(2026, 5, 27)
        dailyCashflowSnapshotRepository.save(
            DailyCashflowSnapshot(
                userId = "1",
                calculatedLimit = BigDecimal("12000.0000"),
                studyBuffer = BigDecimal("30000.0000"),
                snapshotDate = snapshotDate,
            ),
        )

        val snapshot = dailyCashflowSnapshotRepository.findByUserIdAndSnapshotDate("1", snapshotDate)

        assertEquals(BigDecimal("12000.0000"), snapshot?.calculatedLimit)
    }
}

