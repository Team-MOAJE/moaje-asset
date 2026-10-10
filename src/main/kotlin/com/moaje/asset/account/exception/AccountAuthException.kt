package com.moaje.asset.account.exception

import org.springframework.http.HttpStatus

sealed class AccountAuthException(
    val errorCode: String,
    val errorMsg: String,
    val status: HttpStatus,
) : RuntimeException(errorMsg)


class AccountAuthFailedException(errorMsg: String = "잘못된 요청입니다.") :
    AccountAuthException("DUPLICATE_AUTH",errorMsg, HttpStatus.FORBIDDEN)

class InvalidAccountException(errorMsg: String = "잘못된 요청입니다.") :
    AccountAuthException("ACCOUNT_NOT_FOUND",errorMsg, HttpStatus.NOT_FOUND)
