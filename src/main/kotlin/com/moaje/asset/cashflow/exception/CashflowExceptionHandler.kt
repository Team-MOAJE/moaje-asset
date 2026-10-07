package com.moaje.asset.cashflow.exception

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/**
 * Spring 에서 제공해주는 ProblemDetail 활용하여 throw 된 exception 전달
 */
@RestControllerAdvice
class CashflowExceptionHandler {

    @ExceptionHandler(AccountException::class)
    fun handleAccountException(
        ex: AccountException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {

        val pd = ProblemDetail.forStatusAndDetail(ex.httpStatus,ex.message ?: "")
        pd.title = "AccountError"
        pd.instance = URI.create(request.requestURL.toString())
        pd.setProperty("exception",ex.javaClass.simpleName)
        return ResponseEntity.status(ex.httpStatus).body(pd)

    }
}
