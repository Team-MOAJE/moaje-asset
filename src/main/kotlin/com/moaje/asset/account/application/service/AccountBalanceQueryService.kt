package com.moaje.asset.account.application.service

import com.moaje.asset.account.domain.AccountStatus
import com.moaje.asset.account.persistence.AccountRepository
import com.moaje.asset.cashflow.exception.AccountNotLinkedException
import com.moaje.asset.cashflow.exception.NoActiveAccountException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@Service
class AccountBalanceQueryService(
    private val accountRepository: AccountRepository,
) {

    @Transactional(readOnly = true)
    fun getCurrentBalance(userId: String) : BigDecimal {

        require(userId.isNotBlank()) {" 사용자 ID가 없습니다. "}

        return accountRepository.findByUserId(userId)
            .ifEmpty { throw AccountNotLinkedException("계좌가 존재하지 않습니다.") }
            .filter { it.status == AccountStatus.ACTIVE }
            .ifEmpty { throw NoActiveAccountException("활성화된 계좌가 없습니다.") }
            .fold(BigDecimal.ZERO) { acc, account -> acc.add(account.balance)}
    }

}
