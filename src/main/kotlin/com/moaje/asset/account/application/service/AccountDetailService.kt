package com.moaje.asset.account.application.service

import com.moaje.asset.account.application.model.AccountDetailCommand
import com.moaje.asset.account.application.model.AccountTransactionCommand
import com.moaje.asset.account.application.model.AccountTransactionListCommand
import com.moaje.asset.account.application.model.AccountTransactionScrollResult
import com.moaje.asset.account.application.model.AccountDetailResult
import com.moaje.asset.account.application.model.AccountTransactionItem
import com.moaje.asset.account.application.model.AccountListItem

/** 계좌 조회와 새로고침 업무를 선언한다. 실제 처리는 AccountDetailServiceImpl이 담당한다. */
interface AccountDetailService {
    fun getAccounts(userId: String): List<AccountListItem>
    fun getDetail(command: AccountDetailCommand): AccountDetailResult
    fun getTranDetail(command: AccountTransactionCommand): AccountTransactionItem
    fun getTranList(command: AccountTransactionListCommand): AccountTransactionScrollResult
    fun getRefreshAccountDetail(command: AccountDetailCommand): AccountDetailResult
}
