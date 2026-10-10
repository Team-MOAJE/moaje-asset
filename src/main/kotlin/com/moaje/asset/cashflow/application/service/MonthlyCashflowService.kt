package com.moaje.asset.cashflow.application.service

import com.moaje.asset.cashflow.application.model.MonthlyCashflowResult
import com.moaje.asset.cashflow.application.model.CategoryCashflowResult
import java.time.YearMonth

/** 월별 집계와 조회 업무를 선언한다. 실제 처리는 MonthlyCashflowServiceImpl이 담당한다. */
interface MonthlyCashflowService {
    fun getMonthly(userId: String, yearMonth: YearMonth): MonthlyCashflowResult
    fun getCategories(userId: String, yearMonth: YearMonth): CategoryCashflowResult
    fun aggregate(userId: String, yearMonth: YearMonth): MonthlyCashflowResult
}
