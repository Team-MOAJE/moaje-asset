package com.moaje.asset.account.application.query

import com.moaje.asset.account.application.model.BankingAccountProjectionSnapshot
import java.time.Instant

/** Banking에서 계좌 잔액과 거래내역을 조회하는 계약이다. 실제 통신은 BankingGrpcClient가 담당한다. */
interface BankingAccountSnapshotQuery {
    fun getAccountSnapshot(accountId: Long, cursor: Instant): BankingAccountProjectionSnapshot
}
