package com.moaje.asset.application.auth

import org.springframework.stereotype.Component

data class AuthExternalBankingAccount(
    val externalAccountNumber: String,
    val ci: String,
    val userName: String,
    val phoneNumber: String,
)

interface AuthAccountInfoClient {
    fun resolveExternalBankingAccount(userId: Long, accountToken: String): AuthExternalBankingAccount
}

@Component
class TodoAuthAccountInfoClient : AuthAccountInfoClient {
    /**
     * Auth 서비스 RPC 스펙 확정 후 계좌 토큰으로 암호화된 계좌정보와 사용자 식별정보를 조회합니다.
     */
    override fun resolveExternalBankingAccount(
        userId: Long,
        accountToken: String,
    ): AuthExternalBankingAccount {
        TODO("Auth 서비스의 계좌정보 조회 RPC 스펙 확정 후 구현합니다.")
    }
}
