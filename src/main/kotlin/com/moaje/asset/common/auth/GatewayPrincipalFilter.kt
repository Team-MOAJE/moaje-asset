package com.moaje.asset.common.auth

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.Principal

/**
 * Gateway가 JWT를 검증한 뒤 만든 사용자 헤더를 Controller의 Principal로 바꾼다.
 * 이 헤더 자체에는 서명이 없다. 따라서 서비스 HTTP 포트를 외부에 공개하지 않고 Gateway만 진입점으로 둬야 한다.
 * 요청 본문과 URL의 userId는 신분증이 아니므로, 헤더가 없을 때 대신 사용하지 않고 401을 반환한다.
 */
@Component
class GatewayPrincipalFilter : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        !request.requestURI.startsWith("/api/v1/assets/") && request.requestURI != "/api/v1/assets"

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val values = request.getHeaders(USER_HEADER).toList()
        val subject = values.singleOrNull()
        if (subject == null || !subject.matches(Regex("[A-Za-z0-9._:@-]{1,100}"))) {
            response.status = 401
            response.contentType = "application/json"
            response.writer.write("""{"code":"UNAUTHENTICATED","message":"Verified Gateway identity is required."}""")
            return
        }
        val principal = Principal { subject }
        chain.doFilter(object : HttpServletRequestWrapper(request) {
            override fun getUserPrincipal(): Principal = principal
            override fun getRemoteUser(): String = subject
        }, response)
    }

    companion object { const val USER_HEADER = "X-Authenticated-User-Id" }
}
