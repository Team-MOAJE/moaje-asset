package com.moaje.asset.repository

import com.moaje.asset.domain.account.Account
import com.moaje.asset.domain.cashflow.DailyCashflowSnapshot
import com.moaje.asset.domain.outbox.TransactionalOutbox
import com.moaje.asset.domain.transaction.TransactionHistory
import com.moaje.asset.domain.transaction.TransactionStatus
import com.moaje.asset.domain.transaction.TransactionType
import com.moaje.asset.repository.account.AccountRepository
import com.moaje.asset.repository.cashflow.DailyCashflowSnapshotRepository
import com.moaje.asset.repository.outbox.TransactionalOutboxRepository
import com.moaje.asset.repository.transaction.TransactionHistoryRepository
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
    @DisplayName("怨꾩쥖 ?먯옣????ν븯怨??ъ슜??ID濡?議고쉶?쒕떎")
    fun saveAccountAndFindByUserId() {
        val saved = accountRepository.save(
            Account(
                userId = 1L,
                accountToken = "account-token-1",
                balance = BigDecimal("10000.0000"),
            ),
        )

        val accounts = accountRepository.findByUserId(1L)

        assertNotNull(saved.id)
        assertEquals(1, accounts.size)
        assertEquals("account-token-1", accounts.first().accountToken)
    }

    @Test
    @DisplayName("嫄곕옒 ?댁뿭????ν븯怨?硫깅벑???ㅻ줈 議고쉶?쒕떎")
    fun saveTransactionHistoryAndFindByIdempotencyKey() {
        val account = accountRepository.save(
            Account(
                userId = 1L,
                accountToken = "account-token-2",
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
    @DisplayName("諛쒗뻾?섏? ?딆? ?꾩썐諛뺤뒪 ?대깽?몃? ?ㅻ옒???쒖꽌濡?議고쉶?쒕떎")
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
    @DisplayName("媛???앺솢鍮??ㅻ깄?룹쓣 ??ν븯怨??ъ슜?먯? ?좎쭨濡?議고쉶?쒕떎")
    fun saveDailyCashflowSnapshotAndFindByUserIdAndSnapshotDate() {
        val snapshotDate = LocalDate.of(2026, 5, 27)
        dailyCashflowSnapshotRepository.save(
            DailyCashflowSnapshot(
                userId = 1L,
                calculatedLimit = BigDecimal("12000.0000"),
                studyBuffer = BigDecimal("30000.0000"),
                snapshotDate = snapshotDate,
            ),
        )

        val snapshot = dailyCashflowSnapshotRepository.findByUserIdAndSnapshotDate(1L, snapshotDate)

        assertEquals(BigDecimal("12000.0000"), snapshot?.calculatedLimit)
    }
}

