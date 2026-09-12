package com.moaje.asset.account.application.usecase

interface GetAccountDetailUseCase {
    fun getDetail(command: AccountDetailCommand): AccountDetailResult
}

