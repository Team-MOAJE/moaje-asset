package com.moaje.asset.cashflow.application.usecase

interface CalculateDailyCashflowUseCase {
    fun calculate(command: DailyCashflowCommand): DailyCashflowResult
}


