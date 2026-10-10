package com.moaje.asset.account.web

import com.moaje.asset.account.exception.AccountAuthFailedException
import com.moaje.asset.account.exception.AccountRefreshFailedException
import com.moaje.asset.account.exception.InvalidAccountException
import com.moaje.asset.account.exception.NotFoundAccountTransferHistory
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

@RestControllerAdvice
class AccountExceptionHandler {

    @ExceptionHandler(AccountAuthFailedException::class)
    fun handleAccountAuthFailedException(ex: AccountAuthFailedException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {

        val pd = ProblemDetail.forStatusAndDetail(ex.status, ex.message ?: "")
        pd.title = ex.errorCode
        pd.instance = URI.create(request.requestURL.toString())
        pd.setProperty("msg", ex.message)
        return ResponseEntity.status(ex.status).body(pd)

    }

    @ExceptionHandler(InvalidAccountException::class)
    fun handleInvalidAccountException(ex: InvalidAccountException, request: HttpServletRequest): ResponseEntity<ProblemDetail> {
        val pd = ProblemDetail.forStatusAndDetail(ex.status, ex.message ?: "")
        pd.title = ex.errorCode
        pd.instance = URI.create(request.requestURL.toString())
        pd.setProperty("msg", ex.message)
        return ResponseEntity.status(ex.status).body(pd)
    }

    @ExceptionHandler(NotFoundAccountTransferHistory::class)
    fun handleNotFoundAccountTransferHistory(ex: NotFoundAccountTransferHistory, request: HttpServletRequest) : ResponseEntity<ProblemDetail> {
        val pd = ProblemDetail.forStatusAndDetail(ex.status, ex.message ?: "")
        pd.title = ex.errorCode
        pd.instance = URI.create(request.requestURL.toString())
        pd.setProperty("msg", ex.message)
        return ResponseEntity.status(ex.status).body(pd)
    }


    @ExceptionHandler(AccountRefreshFailedException::class)
    fun handleAccountRefreshFailedException(ex: AccountRefreshFailedException, request: HttpServletRequest) : ResponseEntity<ProblemDetail> {
        val pd = ProblemDetail.forStatusAndDetail(ex.status, ex.message ?: "")
        pd.title = ex.errorCode
        pd.instance = URI.create(request.requestURL.toString())
        pd.setProperty("msg", ex.message)
        return ResponseEntity.status(ex.status).body(pd)
    }

}
