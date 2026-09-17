package com.moaje.asset.cashflow.web

import com.moaje.asset.cashflow.application.usecase.CategoryCashflowResult
import com.moaje.asset.cashflow.application.usecase.MonthlyCashflowUseCase
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
@RequestMapping("/api/v1/assets/cashflow/category")
@Tag(name = "Category Cashflow", description = "사용자 월별 카테고리 금액 집계")
class CategoryCashflowController(
    private val useCase: MonthlyCashflowUseCase,
) {
    @GetMapping
    @Operation(summary = "카테고리별 월 집계 조회", description = "거래가 없는 카테고리도 0원으로 반환합니다.")
    fun getCategoryCashflow(
        principal: Principal,
        @RequestParam
        @DateTimeFormat(pattern = "yyyy-MM")
        yearMonth: YearMonth,
    ): CategoryCashflowResult = useCase.getCategories(principal.name, yearMonth)
}
