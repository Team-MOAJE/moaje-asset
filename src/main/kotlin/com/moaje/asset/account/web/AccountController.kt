package com.moaje.asset.account.web

import com.moaje.asset.account.application.usecase.AccountDetailCommand
import com.moaje.asset.account.application.usecase.AccountDetailResult
import com.moaje.asset.account.application.usecase.AccountTransactionCommand
import com.moaje.asset.account.application.usecase.AccountTransactionItem
import com.moaje.asset.account.application.usecase.AccountTransactionListCommand
import com.moaje.asset.account.application.usecase.AccountTransactionScrollResult
import com.moaje.asset.account.application.usecase.GetAccountDetailUseCase
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.Principal

@RestController
@RequestMapping("/api/v1/assets/accounts")
class AccountController(
    private val getAccountDetailUseCase: GetAccountDetailUseCase,
) {
    @GetMapping("/{accountId}/detail")
    fun getDetail(
        @PathVariable accountId: Long,
        principal: Principal,
    ): AccountDetailResult {
        return getAccountDetailUseCase.getDetail(
            AccountDetailCommand(
                userId = principal.name,
                accountId = accountId,
            ),
        )
    }

    // 거래내역 단건 조회
    @GetMapping("/{accountId}/{transactionId}/transaction")
    fun getTransaction(
        @PathVariable accountId: Long,
        @PathVariable transactionId: Long,
        principal: Principal,
    ): AccountTransactionItem {
        return getAccountDetailUseCase.getTranDetail(
            AccountTransactionCommand(
                userId = principal.name,
                accountId = accountId,
                transactionId = transactionId,
            )
        )
    }

    // 거래내역 50건 단위로 무한 스크롤
    @GetMapping("/{accountId}/transactions")
    fun getTransactions(
        @PathVariable accountId: Long,
        @RequestParam(required = false) lastTransactionId: Long?,
        principal: Principal,
    ): AccountTransactionScrollResult {
        return getAccountDetailUseCase.getTranList(
            AccountTransactionListCommand(
                userId = principal.name,
                accountId = accountId,
                lastTransactionId = lastTransactionId,
            ),
        )
    }

    // Refresh는 계좌 Projection을 변경하므로 읽기 전용 GET이 아니라 POST로 요청한다.
    @PostMapping("/{accountId}/refresh")
    fun refresh(
        @PathVariable accountId: Long,
        principal: Principal,
    ): AccountDetailResult {
        return getAccountDetailUseCase.getRefreshAccountDetail(
            AccountDetailCommand(
                userId = principal.name,
                accountId = accountId,
            ),
        )
    }
}
