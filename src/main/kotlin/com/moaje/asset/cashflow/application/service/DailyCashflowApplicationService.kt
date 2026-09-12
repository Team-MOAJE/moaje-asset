package com.moaje.asset.cashflow.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.moaje.asset.account.persistence.AccountRepository
import com.moaje.asset.account.domain.AccountStatus
import com.moaje.asset.cashflow.persistence.DailyCashflowSnapshotRepository
import com.moaje.asset.cashflow.application.usecase.CalculateDailyCashflowUseCase
import com.moaje.asset.cashflow.application.usecase.DailyCashflowCommand
import com.moaje.asset.cashflow.application.usecase.DailyCashflowResult
import com.moaje.asset.cashflow.domain.DailyCashflowSnapshot
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.TimeUnit

@Service
class DailyCashflowApplicationService(
    private val accountRepository: AccountRepository,
    private val dailyCashflowSnapshotRepository: DailyCashflowSnapshotRepository,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) : CalculateDailyCashflowUseCase {
    @Transactional
    override fun calculate(command: DailyCashflowCommand): DailyCashflowResult {
        require(command.daysUntilNextPayday > 0) { "다음 수입일까지 남은 일수는 0보다 커야 합니다." }

        val cacheKey = cacheKey(command.userId)
        if (!command.forceRefresh) {
            val cached = redisTemplate.opsForValue().get(cacheKey)
                ?.let { readCachedDailyCashflow(cacheKey, it) }
            if (cached != null && cached.snapshotDate == command.snapshotDate) {
                return cached
            }
        }

        val currentBalance = accountRepository.findByUserId(command.userId)
            .asSequence()
            .filter { it.status == AccountStatus.ACTIVE }
            .fold(BigDecimal.ZERO) { acc, account -> acc.add(account.balance) }

        val dailyLimit = calculateDailyLimit(
            currentBalance = currentBalance,
            expectedIncome = command.expectedIncome,
            fixedExpenses = command.fixedExpenses,
            eventBuffer = command.eventBuffer,
            daysUntilNextPayday = command.daysUntilNextPayday,
        )

        val snapshot = dailyCashflowSnapshotRepository.findById(command.userId)
            .orElse(
                DailyCashflowSnapshot(
                    userId = command.userId,
                    calculatedLimit = dailyLimit,
                    studyBuffer = command.eventBuffer,
                    snapshotDate = command.snapshotDate,
                ),
            )

        snapshot.update(
            calculatedLimit = dailyLimit,
            studyBuffer = command.eventBuffer,
            snapshotDate = command.snapshotDate,
        )
        dailyCashflowSnapshotRepository.save(snapshot)

        val result = DailyCashflowResult(
            userId = command.userId,
            currentBalance = currentBalance,
            expectedIncome = command.expectedIncome,
            fixedExpenses = command.fixedExpenses,
            eventBuffer = command.eventBuffer,
            daysUntilNextPayday = command.daysUntilNextPayday,
            dailyLimit = dailyLimit,
            snapshotDate = command.snapshotDate,
        )

        redisTemplate.opsForValue().set(cacheKey, objectMapper.writeValueAsString(result), 1, TimeUnit.DAYS)
        return result
    }

    private fun calculateDailyLimit(
        currentBalance: BigDecimal,
        expectedIncome: BigDecimal,
        fixedExpenses: BigDecimal,
        eventBuffer: BigDecimal,
        daysUntilNextPayday: Int,
    ): BigDecimal {
        val numerator = currentBalance
            .add(expectedIncome)
            .subtract(fixedExpenses)
            .subtract(eventBuffer)

        return numerator.divide(
            BigDecimal(daysUntilNextPayday),
            CASHFLOW_SCALE,
            RoundingMode.DOWN,
        )
    }

    private fun cacheKey(userId: String): String = "asset:daily-cashflow:$userId"

    private fun readCachedDailyCashflow(cacheKey: String, cachedJson: String): DailyCashflowResult? {
        return runCatching {
            objectMapper.readValue(cachedJson, DailyCashflowResult::class.java)
        }.getOrElse {
            redisTemplate.delete(cacheKey)
            null
        }
    }

    companion object {
        private const val CASHFLOW_SCALE = 4
    }
}

