package com.moaje.asset.account.application.usecase

interface GetAccountDetailUseCase {
    fun getDetail(command: AccountDetailCommand): AccountDetailResult
    fun getTranDetail(command: AccountTransactionCommand): AccountTransactionItem
    fun getTranList(command: AccountTransactionListCommand): AccountTransactionScrollResult
    fun getRefreshAccountDetail(command: AccountDetailCommand): AccountDetailResult
}
