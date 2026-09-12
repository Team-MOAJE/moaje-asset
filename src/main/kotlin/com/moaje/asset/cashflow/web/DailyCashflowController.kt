package com.moaje.asset.cashflow.web

import com.moaje.asset.cashflow.application.usecase.DailyCashflowCommand
import com.moaje.asset.cashflow.application.usecase.DailyCashflowResult
import com.moaje.asset.cashflow.application.usecase.CalculateDailyCashflowUseCase
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
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
    private val dailyCashflowService: CalculateDailyCashflowUseCase,
) {
    @PostMapping("/daily-limit")
    fun calculateDailyLimit(
        @Valid @RequestBody request: DailyCashflowRequest,
        principal: java.security.Principal,
    ): DailyCashflowResult {
        return dailyCashflowService.calculate(request.toCommand(principal.name))
    }
}

data class DailyCashflowRequest(
    // 구버전 앱 호환용으로만 받는다. 실제 조회 주체는 Gateway가 검증한 Principal에서만 가져온다.
    val userId: String? = null,
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
    fun toCommand(principalId: String): DailyCashflowCommand {
        return DailyCashflowCommand(
            userId = principalId,
            expectedIncome = expectedIncome,
            fixedExpenses = fixedExpenses,
            eventBuffer = eventBuffer,
            daysUntilNextPayday = daysUntilNextPayday,
            snapshotDate = snapshotDate,
            forceRefresh = forceRefresh,
        )
    }
}


