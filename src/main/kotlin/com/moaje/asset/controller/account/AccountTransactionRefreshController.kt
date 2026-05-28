package com.moaje.asset.controller.account

import com.moaje.asset.application.account.AccountTransactionRefreshService
import com.moaje.asset.application.account.RefreshAccountTransactionsCommand
import com.moaje.asset.application.account.RefreshAccountTransactionsResult
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@RestController
@RequestMapping("/api/v1/assets/accounts")
class AccountTransactionRefreshController(
    private val accountTransactionRefreshService: AccountTransactionRefreshService,
) {
    /**
     * 계좌 상세 화면의 pull-to-refresh 요청을 받아 외부 금융망 거래내역 동기화를 시작합니다.
     */
    @PostMapping("/{accountToken}/transactions/refresh")
    fun refreshTransactions(
        @PathVariable accountToken: String,
        @Valid @RequestBody request: RefreshAccountTransactionsRequest,
    ): RefreshAccountTransactionsResult {
        return accountTransactionRefreshService.refresh(request.toCommand(accountToken))
    }
}

data class RefreshAccountTransactionsRequest(
    @field:Positive
    val userId: Long,
    @field:NotBlank
    val cursor: String = Instant.EPOCH.toString(),
) {
    fun toCommand(accountToken: String): RefreshAccountTransactionsCommand {
        return RefreshAccountTransactionsCommand(
            userId = userId,
            accountToken = accountToken,
            cursor = Instant.parse(cursor),
        )
    }
}
