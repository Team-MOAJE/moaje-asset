package com.moaje.asset.cashflow.application.service

import com.moaje.asset.cashflow.application.model.DailyCashflowResult
import com.moaje.asset.cashflow.application.model.DailyCashflowCommand

/** 하루 생활비 계산 업무를 선언한다. 실제 처리는 DailyCashflowServiceImpl이 담당한다. */
interface DailyCashflowService {
    fun calculate(command: DailyCashflowCommand): DailyCashflowResult
}


