package com.moaje.asset.cashflow.application.service

import com.moaje.asset.cashflow.application.model.MonthlyCashflowOutboxPayload
import com.moaje.asset.cashflow.application.model.CategoryCashflowOutboxItem
import com.moaje.asset.cashflow.application.model.CategoryCashflowOutboxPayload
import com.fasterxml.jackson.databind.ObjectMapper
import com.moaje.asset.cashflow.application.model.CategoryCashflowItem
import com.moaje.asset.cashflow.application.model.CategoryCashflowResult
import com.moaje.asset.cashflow.application.model.MonthlyCashflowResult
import com.moaje.asset.cashflow.domain.MonthlyCashflowAggregate
import com.moaje.asset.cashflow.domain.MonthlyCategoryCashflow
import com.moaje.asset.cashflow.persistence.MonthlyCashflowAggregateRepository
import com.moaje.asset.cashflow.persistence.MonthlyCategoryCashflowRepository
import com.moaje.asset.outbox.domain.TransactionalOutbox
import com.moaje.asset.outbox.persistence.TransactionalOutboxRepository
import com.moaje.asset.transfer.domain.TransactionCategory
import com.moaje.asset.transfer.persistence.TransactionHistoryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.UUID

@Service
class MonthlyCashflowServiceImpl(
    private val transactionHistoryRepository: TransactionHistoryRepository,
    private val aggregateRepository: MonthlyCashflowAggregateRepository,
    private val categoryRepository: MonthlyCategoryCashflowRepository,
    private val outboxRepository: TransactionalOutboxRepository,
    private val objectMapper: ObjectMapper,
) : MonthlyCashflowService {

    @Transactional
    override fun aggregate(userId: String, yearMonth: YearMonth): MonthlyCashflowResult {
        val start = yearMonth.atDay(1).atStartOfDay()
        val end = yearMonth.plusMonths(1).atDay(1).atStartOfDay()
        val amounts = TransactionCategory.entries.associateWith { BigDecimal.ZERO }.toMutableMap()
        transactionHistoryRepository.sumSuccessfulTransactionsByCategory(userId, start, end)
            .forEach { amounts[it.categoryCode] = it.totalAmount }

        val income = amounts.getValue(TransactionCategory.INCOME)
        val transfer = amounts.getValue(TransactionCategory.TRANSFER)
        val expense = amounts
            .filterKeys { it != TransactionCategory.INCOME && it != TransactionCategory.TRANSFER }
            .values.fold(BigDecimal.ZERO, BigDecimal::add)
        val hash = contentHash(amounts)
        val now = LocalDateTime.now()

        val existing = aggregateRepository.findByUserIdAndYearAndMonth(userId, yearMonth.year, yearMonth.monthValue)
        val aggregate: MonthlyCashflowAggregate
        val changed: Boolean
        if (existing == null) {
            aggregate = aggregateRepository.saveAndFlush(
                MonthlyCashflowAggregate(
                    userId = userId,
                    year = yearMonth.year,
                    month = yearMonth.monthValue,
                    incomeAmount = income,
                    expenseAmount = expense,
                    transferAmount = transfer,
                    contentHash = hash,
                    calculatedAt = now,
                ),
            )
            changed = true
        } else {
            aggregate = existing
            changed = aggregate.revise(income, expense, transfer, hash, now)
        }

        if (changed) {
            replaceCategoryAmounts(userId, yearMonth, amounts)
            saveOutboxes(aggregate, yearMonth, amounts)
        }
        return aggregate.toResult()
    }

    @Transactional
    override fun getMonthly(userId: String, yearMonth: YearMonth): MonthlyCashflowResult {
        val aggregate = aggregateRepository.findByUserIdAndYearAndMonth(userId, yearMonth.year, yearMonth.monthValue)
            ?: return aggregate(userId, yearMonth)
        return aggregate.toResult()
    }

    @Transactional
    override fun getCategories(userId: String, yearMonth: YearMonth): CategoryCashflowResult {
        val aggregate = aggregateRepository.findByUserIdAndYearAndMonth(userId, yearMonth.year, yearMonth.monthValue)
            ?: run {
                aggregate(userId, yearMonth)
                aggregateRepository.findByUserIdAndYearAndMonth(userId, yearMonth.year, yearMonth.monthValue)!!
            }
        val stored = categoryRepository.findByUserIdAndYearAndMonthOrderByCategoryAsc(
            userId,
            yearMonth.year,
            yearMonth.monthValue,
        ).associate { it.category to it.amount }
        return CategoryCashflowResult(
            userId = userId,
            yearMonth = yearMonth,
            revision = aggregate.revision,
            categories = TransactionCategory.entries.map { CategoryCashflowItem(it, stored[it] ?: BigDecimal.ZERO) },
            calculatedAt = aggregate.calculatedAt,
        )
    }

    private fun replaceCategoryAmounts(
        userId: String,
        yearMonth: YearMonth,
        amounts: Map<TransactionCategory, BigDecimal>,
    ) {
        amounts.forEach { (category, amount) ->
            val row = categoryRepository.findByUserIdAndYearAndMonthAndCategory(
                userId,
                yearMonth.year,
                yearMonth.monthValue,
                category,
            ) ?: MonthlyCategoryCashflow(
                userId = userId,
                year = yearMonth.year,
                month = yearMonth.monthValue,
                category = category,
            )
            row.replaceAmount(amount)
            categoryRepository.save(row)
        }
    }

    private fun saveOutboxes(
        aggregate: MonthlyCashflowAggregate,
        yearMonth: YearMonth,
        amounts: Map<TransactionCategory, BigDecimal>,
    ) {
        val aggregateId = requireNotNull(aggregate.id)
        val monthlyEvent = MonthlyCashflowOutboxPayload(
            eventId = UUID.randomUUID().toString(),
            userId = aggregate.userId,
            yearMonth = yearMonth.toString(),
            revision = aggregate.revision,
            incomeAmount = aggregate.incomeAmount,
            expenseAmount = aggregate.expenseAmount,
            transferAmount = aggregate.transferAmount,
            calculatedAt = aggregate.calculatedAt,
        )
        val categoryEvent = CategoryCashflowOutboxPayload(
            eventId = UUID.randomUUID().toString(),
            userId = aggregate.userId,
            yearMonth = yearMonth.toString(),
            revision = aggregate.revision,
            categories = amounts.entries.sortedBy { it.key.name }
                .map { CategoryCashflowOutboxItem(it.key.name, it.value) },
            calculatedAt = aggregate.calculatedAt,
        )
        outboxRepository.save(
            TransactionalOutbox(
                aggregateType = MONTHLY_CASHFLOW_AGGREGATED,
                aggregateId = aggregateId,
                payload = objectMapper.writeValueAsString(monthlyEvent),
                deduplicationKey = "monthly:${aggregate.userId}:$yearMonth:r${aggregate.revision}",
            ),
        )
        outboxRepository.save(
            TransactionalOutbox(
                aggregateType = CATEGORY_CASHFLOW_AGGREGATED,
                aggregateId = aggregateId,
                payload = objectMapper.writeValueAsString(categoryEvent),
                deduplicationKey = "category:${aggregate.userId}:$yearMonth:r${aggregate.revision}",
            ),
        )
    }

    private fun contentHash(amounts: Map<TransactionCategory, BigDecimal>): String {
        val canonical = TransactionCategory.entries.joinToString("|") { category ->
            "$category=${amounts.getValue(category).stripTrailingZeros().toPlainString()}"
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun MonthlyCashflowAggregate.toResult() = MonthlyCashflowResult(
        userId = userId,
        yearMonth = YearMonth.of(year, month),
        incomeAmount = incomeAmount,
        expenseAmount = expenseAmount,
        transferAmount = transferAmount,
        revision = revision,
        calculatedAt = calculatedAt,
    )

    companion object {
        const val MONTHLY_CASHFLOW_AGGREGATED = "MONTHLY_CASHFLOW_AGGREGATED"
        const val CATEGORY_CASHFLOW_AGGREGATED = "CATEGORY_CASHFLOW_AGGREGATED"
    }
}
