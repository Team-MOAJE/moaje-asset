package com.moaje.asset.application.account.impl

import com.moaje.asset.application.account.AccountTransactionRefreshService
import com.moaje.asset.application.account.RefreshAccountTransactionsCommand
import com.moaje.asset.application.account.RefreshAccountTransactionsResult
import com.moaje.asset.application.auth.AuthAccountInfoClient
import com.moaje.asset.application.banking.BankingExternalTransactionSyncClient
import com.moaje.asset.application.banking.ExternalTransactionSyncRequest
import com.moaje.asset.repository.account.AccountRepository
import org.springframework.stereotype.Service

@Service
class AccountTransactionRefreshServiceImpl(
    private val accountRepository: AccountRepository,
    private val authAccountInfoClient: AuthAccountInfoClient,
    private val bankingExternalTransactionSyncClient: BankingExternalTransactionSyncClient,
) : AccountTransactionRefreshService {
    /**
     * 화면 당김 새로고침 시 Auth에서 금융망 조회용 정보를 받아 Banking에 외부 거래내역 동기화를 요청합니다.
     */
    override fun refresh(command: RefreshAccountTransactionsCommand): RefreshAccountTransactionsResult {
        val account = accountRepository.findByAccountToken(command.accountToken)
            ?: throw IllegalArgumentException("계좌를 찾을 수 없습니다.")

        require(account.userId == command.userId) { "사용자의 계좌가 아닙니다." }

        val authAccount = authAccountInfoClient.resolveExternalBankingAccount(
            userId = command.userId,
            accountToken = command.accountToken,
        )

        val result = bankingExternalTransactionSyncClient.syncExternalAccountTransactions(
            ExternalTransactionSyncRequest(
                userId = command.userId,
                assetAccountId = account.id ?: throw IllegalStateException("계좌 ID가 없습니다."),
                accountToken = account.accountToken,
                externalAccountNumber = authAccount.externalAccountNumber,
                ci = authAccount.ci,
                userName = authAccount.userName,
                phoneNumber = authAccount.phoneNumber,
                cursor = command.cursor,
            ),
        )

        return RefreshAccountTransactionsResult(
            accountToken = command.accountToken,
            syncedCount = result.syncedCount,
        )
    }
}
