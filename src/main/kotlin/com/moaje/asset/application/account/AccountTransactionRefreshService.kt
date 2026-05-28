package com.moaje.asset.application.account

interface AccountTransactionRefreshService {
    fun refresh(command: RefreshAccountTransactionsCommand): RefreshAccountTransactionsResult
}
