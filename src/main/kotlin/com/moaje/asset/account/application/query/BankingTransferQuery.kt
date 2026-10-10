package com.moaje.asset.account.application.query

import com.moaje.asset.account.application.model.BankingTransferProjectionState
/** Banking의 거래 상태를 조회하는 계약이다. 실제 통신은 BankingGrpcClient가 담당한다. */
interface BankingTransferQuery {
    fun getTransferStates(accountId: Long, transferIds: Collection<Long>): List<BankingTransferProjectionState>
}
