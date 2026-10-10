package com.moaje.asset.cashflow.web

import com.moaje.asset.cashflow.application.model.MonthlyCashflowResult
import com.moaje.asset.cashflow.application.service.MonthlyCashflowService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.Principal
import java.time.YearMonth

@RestController
@RequestMapping("/api/v1/assets/cashflow/monthly")
@Tag(name = "Monthly Cashflow", description = "사용자 월별 수입·지출·자금이동 집계")
class MonthlyCashflowController(
    private val monthlyCashflowService: MonthlyCashflowService,
) {
    @GetMapping
    @Operation(summary = "월별 Cashflow 조회", description = "집계가 없으면 해당 월을 계산한 뒤 반환합니다.")
    fun getMonthlyCashflow(
        principal: Principal,
        @RequestParam
        @DateTimeFormat(pattern = "yyyy-MM")
        yearMonth: YearMonth,
    ): MonthlyCashflowResult = monthlyCashflowService.getMonthly(principal.name, yearMonth)
}
