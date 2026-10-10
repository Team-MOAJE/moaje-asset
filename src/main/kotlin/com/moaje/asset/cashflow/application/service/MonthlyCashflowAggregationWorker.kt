package com.moaje.asset.cashflow.application.service

import com.moaje.asset.account.persistence.AccountRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.YearMonth

@Component
class MonthlyCashflowAggregationWorker(
    private val accountRepository: AccountRepository,
    private val aggregationService: MonthlyCashflowServiceImpl,
    private val clock: Clock = Clock.systemDefaultZone(),
) {
    /** 매월 1일에는 직전 월의 최초 집계를 만들며, 거래가 없는 사용자도 0원 결과를 만든다. */
    @Scheduled(cron = "\${moaje.asset.cashflow.monthly-cron:0 10 0 1 * *}", zone = "Asia/Seoul")
    fun aggregatePreviousMonth() = aggregate(YearMonth.now(clock).minusMonths(1))

    /**
     * Snapshot으로 늦게 들어온 전월 거래를 찾기 위해 전월을 매일 다시 계산한다.
     * 금액이 같으면 아무 이벤트도 만들지 않고, 달라진 경우에만 revision을 올린다.
     */
    @Scheduled(cron = "\${moaje.asset.cashflow.revision-cron:0 20 2 * * *}", zone = "Asia/Seoul")
    fun revisePreviousMonth() = aggregate(YearMonth.now(clock).minusMonths(1))

    internal fun aggregate(yearMonth: YearMonth) {
        accountRepository.findDistinctUserIds().forEach { aggregationService.aggregate(it, yearMonth) }
    }
}
