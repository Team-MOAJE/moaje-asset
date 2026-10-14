package com.moaje.asset.account.application.query

/** 검증된 JWT 주체와 accountId로 본인 계좌번호만 요청한다. 결과는 저장·캐시하지 않는다. */
interface BankingAccountNumberQuery {
    fun getOwnedAccountNumber(accountId: Long, principalId: String): String
}
