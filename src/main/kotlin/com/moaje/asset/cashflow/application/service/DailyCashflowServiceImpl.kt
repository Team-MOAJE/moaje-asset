package com.moaje.asset.cashflow.application.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.moaje.asset.account.application.service.AccountBalanceQueryService
import com.moaje.asset.account.persistence.AccountRepository
import com.moaje.asset.cashflow.persistence.DailyCashflowSnapshotRepository
import com.moaje.asset.cashflow.application.model.DailyCashflowCommand
import com.moaje.asset.cashflow.application.model.DailyCashflowResult
import com.moaje.asset.cashflow.domain.DailyCashflowSnapshot
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.TimeUnit

@Service
class DailyCashflowServiceImpl(
    private val dailyCashflowSnapshotRepository: DailyCashflowSnapshotRepository,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
    private val accountBalanceQueryService: AccountBalanceQueryService,
    private val transactionTemplate: TransactionTemplate,
) : DailyCashflowService {

    override fun calculate(command: DailyCashflowCommand): DailyCashflowResult {
        require(command.daysUntilNextPayday > 0) { "다음 수입일까지 남은 일수는 0보다 커야 합니다." }

        // 캐시보다 먼저 사용자가 가진 계좌의 상태를 검증하고, current_balance를 가져온다.
        val currentBalance = accountBalanceQueryService.getCurrentBalance(userId = command.userId)

        val cacheKey = cacheKey(command.userId)
        if (!command.forceRefresh) {
            val cached = redisTemplate.opsForValue().get(cacheKey)
                ?.let { readCachedDailyCashflow(cacheKey, it) }

            // 캐시가 재사용 가능한지 check 해서 sync가 맞으면 cache 반환
            val reusable = isUsableCache(cached, command,currentBalance)
            if (cached != null && reusable) {
                return cached
            }
        }
        // cache 값과 sync 불일치하는 경우, 다시 생성하여 set
        val dailyLimit = calculateDailyLimit(
            currentBalance = currentBalance,
            expectedIncome = command.expectedIncome,
            fixedExpenses = command.fixedExpenses,
            eventBuffer = command.eventBuffer,
            daysUntilNextPayday = command.daysUntilNextPayday,
        )

        transactionTemplate.executeWithoutResult {
            val snapshot = dailyCashflowSnapshotRepository.findById(command.userId)
                .orElse(
                    DailyCashflowSnapshot(
                        userId = command.userId,
                        calculatedLimit = dailyLimit,
                        studyBuffer = command.eventBuffer,
                        snapshotDate = command.snapshotDate,
                    ),
                )

            snapshot!!.update(
                calculatedLimit = dailyLimit,
                studyBuffer = command.eventBuffer,
                snapshotDate = command.snapshotDate,
            )
            dailyCashflowSnapshotRepository.save(snapshot)
        }

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

    private fun isUsableCache(cached: DailyCashflowResult?, command: DailyCashflowCommand, currentBalance: BigDecimal): Boolean {
        return cached != null &&
               cached.userId == command.userId &&
               cached.currentBalance.compareTo(currentBalance) == 0 &&
               cached.expectedIncome.compareTo(command.expectedIncome) == 0 &&
               cached.fixedExpenses.compareTo(command.fixedExpenses) == 0 &&
               cached.eventBuffer.compareTo(command.eventBuffer) == 0 &&
               cached.daysUntilNextPayday == command.daysUntilNextPayday &&
               cached.snapshotDate == command.snapshotDate
    }

    companion object {
        private const val CASHFLOW_SCALE = 4
    }
}

