package com.moaje.asset.application.cashflow

interface DailyCashflowService {
    fun calculate(command: DailyCashflowCommand): DailyCashflowResult
}

