package com.moaje.asset.controller.cashflow

import com.moaje.asset.application.cashflow.DailyCashflowCommand
import com.moaje.asset.application.cashflow.DailyCashflowResult
import com.moaje.asset.application.cashflow.DailyCashflowService
import jakarta.validation.Valid
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.LocalDate

@RestController
@RequestMapping("/api/v1/assets/cashflow")
class DailyCashflowController(
    private val dailyCashflowService: DailyCashflowService,
) {
    @PostMapping("/daily-limit")
    fun calculateDailyLimit(
        @Valid @RequestBody request: DailyCashflowRequest,
    ): DailyCashflowResult {
        return dailyCashflowService.calculate(request.toCommand())
    }
}

data class DailyCashflowRequest(
    @field:Positive
    val userId: Long,
    @field:PositiveOrZero
    val expectedIncome: BigDecimal = BigDecimal.ZERO,
    @field:PositiveOrZero
    val fixedExpenses: BigDecimal = BigDecimal.ZERO,
    @field:PositiveOrZero
    val eventBuffer: BigDecimal = BigDecimal.ZERO,
    @field:Positive
    val daysUntilNextPayday: Int,
    val snapshotDate: LocalDate = LocalDate.now(),
    val forceRefresh: Boolean = false,
) {
    fun toCommand(): DailyCashflowCommand {
        return DailyCashflowCommand(
            userId = userId,
            expectedIncome = expectedIncome,
            fixedExpenses = fixedExpenses,
            eventBuffer = eventBuffer,
            daysUntilNextPayday = daysUntilNextPayday,
            snapshotDate = snapshotDate,
            forceRefresh = forceRefresh,
        )
    }
}

