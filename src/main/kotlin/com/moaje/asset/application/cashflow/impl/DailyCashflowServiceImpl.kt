package com.moaje.asset.application.cashflow.impl

import com.moaje.asset.application.cashflow.DailyCashflowCommand
import com.moaje.asset.application.cashflow.DailyCashflowResult
import com.moaje.asset.application.cashflow.DailyCashflowService
import com.moaje.asset.domain.account.AccountStatus
import com.moaje.asset.domain.cashflow.DailyCashflowSnapshot
import com.moaje.asset.repository.account.AccountRepository
import com.moaje.asset.repository.cashflow.DailyCashflowSnapshotRepository
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.TimeUnit

@Service
class DailyCashflowServiceImpl(
    private val accountRepository: AccountRepository,
    private val dailyCashflowSnapshotRepository: DailyCashflowSnapshotRepository,
    private val redisTemplate: RedisTemplate<String, Any>,
) : DailyCashflowService {
    @Transactional
    override fun calculate(command: DailyCashflowCommand): DailyCashflowResult {
        require(command.daysUntilNextPayday > 0) { "?ㅼ쓬 湲됱뿬?쇨퉴吏 ?⑥? ?쇱닔??1 ?댁긽?댁뼱???⑸땲??" }

        val cacheKey = cacheKey(command.userId)
        if (!command.forceRefresh) {
            val cached = redisTemplate.opsForValue().get(cacheKey) as? DailyCashflowResult
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

        redisTemplate.opsForValue().set(cacheKey, result, 1, TimeUnit.DAYS)
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

    private fun cacheKey(userId: Long): String = "asset:daily-cashflow:$userId"

    companion object {
        private const val CASHFLOW_SCALE = 4
    }
}

