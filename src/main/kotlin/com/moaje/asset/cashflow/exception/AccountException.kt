package com.moaje.asset.cashflow.exception

import org.springframework.http.HttpStatus

sealed class AccountException(message: String, val httpStatus: HttpStatus)
    : RuntimeException(message)


class AccountNotLinkedException(message: String) : AccountException(message,HttpStatus.CONFLICT)
class NoActiveAccountException(message: String) : AccountException(message, HttpStatus.CONFLICT)
