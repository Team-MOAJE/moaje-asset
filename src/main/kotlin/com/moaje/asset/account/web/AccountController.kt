package com.moaje.asset.account.web

import com.moaje.asset.account.application.usecase.AccountDetailCommand
import com.moaje.asset.account.application.usecase.AccountDetailResult
import com.moaje.asset.account.application.usecase.GetAccountDetailUseCase
import jakarta.validation.constraints.NotBlank
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/assets/accounts")
class AccountController(
    private val getAccountDetailUseCase: GetAccountDetailUseCase,
) {
    @GetMapping("/{accountId}/detail")
    fun getDetail(
        @PathVariable accountId: Long,
        principal: java.security.Principal,
    ): AccountDetailResult {
        return getAccountDetailUseCase.getDetail(
            AccountDetailCommand(
                userId = principal.name,
                accountId = accountId,
            ),
        )
    }

}
