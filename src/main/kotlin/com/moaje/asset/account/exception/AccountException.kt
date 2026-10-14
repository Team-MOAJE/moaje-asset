package com.moaje.asset.account.exception

import org.springframework.http.HttpStatus

sealed class AccountException(
    val errorCode: String,
    val errorMsg: String,
    val status: HttpStatus,
) : RuntimeException(errorMsg)

class AccountNumberUnavailableException : AccountException(
    "ACCOUNT_NUMBER_UNAVAILABLE", "계좌번호를 조회할 수 없습니다. 잠시 후 다시 시도해 주세요.", HttpStatus.BAD_GATEWAY,
)


/**
 * 계좌 거래내역을 찾을 수 없음.
 */
class NotFoundAccountTransferHistory(errorMsg: String = "거래내역을 찾을 수 없습니다.", status: HttpStatus = HttpStatus.NOT_FOUND) :
    AccountException(errorMsg = errorMsg, status = status, errorCode = "NOT_FOUND_ACCOUNT_TRANSFER_HISTORY")

/**
 * 계좌 새로고침에 실패함.
 */
class AccountRefreshFailedException(errorMsg: String = "계좌 새로고침에 실패했습니다.", status: HttpStatus = HttpStatus.BAD_REQUEST) :
    AccountException(errorMsg = errorMsg, status = status, errorCode = "ACCOUNT_REFRESH_FAILED")
