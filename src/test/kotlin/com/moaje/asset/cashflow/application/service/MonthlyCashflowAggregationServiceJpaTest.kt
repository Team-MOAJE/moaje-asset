package com.moaje.asset.cashflow.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.moaje.asset.account.domain.Account
import com.moaje.asset.account.persistence.AccountRepository
import com.moaje.asset.cashflow.persistence.MonthlyCashflowAggregateRepository
import com.moaje.asset.cashflow.persistence.MonthlyCategoryCashflowRepository
import com.moaje.asset.outbox.persistence.TransactionalOutboxRepository
import com.moaje.asset.transfer.domain.TransactionCategory
import com.moaje.asset.transfer.domain.TransactionHistory
import com.moaje.asset.transfer.domain.TransactionStatus
import com.moaje.asset.transfer.domain.TransactionType
import com.moaje.asset.transfer.persistence.TransactionHistoryRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.YearMonth

@DataJpaTest
@DisplayName("월별 Cashflow 집계")
class MonthlyCashflowAggregationServiceJpaTest(
    @Autowired private val accountRepository: AccountRepository,
    @Autowired private val transactionRepository: TransactionHistoryRepository,
    @Autowired private val aggregateRepository: MonthlyCashflowAggregateRepository,
    @Autowired private val categoryRepository: MonthlyCategoryCashflowRepository,
    @Autowired private val outboxRepository: TransactionalOutboxRepository,
) {
    private lateinit var service: MonthlyCashflowAggregationService

    @BeforeEach
    fun setUp() {
        service = MonthlyCashflowAggregationService(
            transactionRepository,
            aggregateRepository,
            categoryRepository,
            outboxRepository,
            ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule()),
        )
    }

    @Test
    @DisplayName("수입과 소비를 합산하되 개인 간 송금은 소비에서 제외한다")
    fun aggregatesIncomeExpenseAndTransferSeparately() {
        // given
        val account = accountRepository.save(Account(userId = "1001"))
        save(account.id!!, "income", TransactionType.DEPOSIT, TransactionCategory.INCOME, "100000", 3)
        save(account.id!!, "cafe", TransactionType.WITHDRAW, TransactionCategory.CAFE, "4500", 5)
        save(account.id!!, "transfer", TransactionType.TRANSFER, TransactionCategory.TRANSFER, "20000", 8)

        // when
        val result = service.aggregate("1001", YearMonth.of(2026, 8))

        // then
        assertThat(result.incomeAmount).isEqualByComparingTo("100000")
        assertThat(result.expenseAmount).isEqualByComparingTo("4500")
        assertThat(result.transferAmount).isEqualByComparingTo("20000")
        assertThat(result.revision).isEqualTo(1)
        assertThat(outboxRepository.count()).isEqualTo(2)
    }

    @Test
    @DisplayName("거래가 없는 사용자도 모든 카테고리가 0원인 집계와 이벤트를 만든다")
    fun createsZeroAggregateForUserWithoutTransactions() {
        // given
        accountRepository.save(Account(userId = "1002"))

        // when
        val result = service.aggregate("1002", YearMonth.of(2026, 8))
        val categories = service.getCategories("1002", YearMonth.of(2026, 8))

        // then
        assertThat(result.incomeAmount).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(result.expenseAmount).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(categories.categories).hasSize(TransactionCategory.entries.size)
        assertThat(categories.categories).allMatch { it.amount.compareTo(BigDecimal.ZERO) == 0 }
        assertThat(outboxRepository.count()).isEqualTo(2)
    }

    @Test
    @DisplayName("같은 결과는 중복 발행하지 않고 늦은 전월 거래가 추가되면 revision을 올린다")
    fun revisesOnlyWhenCalculatedContentChanges() {
        // given
        val account = accountRepository.save(Account(userId = "1003"))
        save(account.id!!, "food-1", TransactionType.WITHDRAW, TransactionCategory.FOOD, "10000", 10)
        val period = YearMonth.of(2026, 8)

        // when: 같은 장부를 두 번 계산한다.
        service.aggregate("1003", period)
        val unchanged = service.aggregate("1003", period)

        // then: revision과 outbox는 증가하지 않는다.
        assertThat(unchanged.revision).isEqualTo(1)
        assertThat(outboxRepository.count()).isEqualTo(2)

        // when: Snapshot으로 전월 거래가 늦게 복구된 상황이다.
        save(account.id!!, "food-2", TransactionType.WITHDRAW, TransactionCategory.FOOD, "7000", 20)
        val revised = service.aggregate("1003", period)

        // then: 전월 결과를 교정하는 revision 2 이벤트가 한 쌍 추가된다.
        assertThat(revised.revision).isEqualTo(2)
        assertThat(revised.expenseAmount).isEqualByComparingTo("17000")
        assertThat(outboxRepository.count()).isEqualTo(4)
    }

    private fun save(
        accountId: Long,
        key: String,
        type: TransactionType,
        category: TransactionCategory,
        amount: String,
        day: Int,
    ) {
        transactionRepository.save(
            TransactionHistory(
                accountId = accountId,
                publicTransferId = "PUBLIC-$key",
                type = type,
                amount = BigDecimal(amount),
                status = TransactionStatus.SUCCESS,
                idempotencyKey = "IDEMPOTENCY-$key",
                categoryCode = category,
                externalOccurredAt = LocalDateTime.of(2026, 8, day, 12, 0),
            ),
        )
    }
}
