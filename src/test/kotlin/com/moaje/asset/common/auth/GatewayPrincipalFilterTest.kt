package com.moaje.asset.common.auth

import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class GatewayPrincipalFilterTest {
    @Test
    @DisplayName("생활비 요청 Body의 userId를 바꿔도 조회 주체는 Principal이다")
    fun cashflowIgnoresBodyOwner() {
        // given
        val request = com.moaje.asset.cashflow.web.DailyCashflowRequest(userId = "untrusted", daysUntilNextPayday = 1)
        // when
        val command = request.toCommand("123456789")
        // then
        assertThat(command.userId).isEqualTo("123456789")
    }

    @Test
    @DisplayName("사용자 Query나 Body가 있어도 검증 헤더가 없으면 401로 거절한다")
    fun rejectsBodyIdentityFallback() {
        // given: 클라이언트가 스스로 정한 userId는 인증 정보가 아니다.
        val request = MockHttpServletRequest("POST", "/api/v1/assets/accounts").apply {
            addParameter("userId", "untrusted")
            setContent("""{"userId":"untrusted"}""".toByteArray())
        }
        val response = MockHttpServletResponse()
        // when
        GatewayPrincipalFilter().doFilter(request, response) { _, _ -> error("인증 없이 실행되면 안 됩니다.") }
        // then
        assertThat(response.status).isEqualTo(401)
    }

    @Test
    @DisplayName("Gateway가 검증한 헤더만 Principal로 사용하며 Query의 userId는 무시한다")
    fun exposesVerifiedPrincipal() {
        // given
        val request = MockHttpServletRequest("GET", "/api/v1/assets/accounts").apply {
            addHeader(GatewayPrincipalFilter.USER_HEADER, "123456789")
            addParameter("userId", "untrusted")
        }
        var identity: String? = null
        // when
        GatewayPrincipalFilter().doFilter(request, MockHttpServletResponse()) { forwarded, _ ->
            identity = (forwarded as HttpServletRequest).userPrincipal.name
        }
        // then
        assertThat(identity).isEqualTo("123456789")
    }

    @Test
    @DisplayName("내부 사용자 헤더가 두 개이면 모호한 인증 요청을 거절한다")
    fun rejectsDuplicateIdentityHeaders() {
        // given
        val request = MockHttpServletRequest("GET", "/api/v1/assets/accounts").apply {
            addHeader(GatewayPrincipalFilter.USER_HEADER, "1001")
            addHeader(GatewayPrincipalFilter.USER_HEADER, "1002")
        }
        val response = MockHttpServletResponse()
        // when
        GatewayPrincipalFilter().doFilter(request, response) { _, _ -> error("중복 신원은 허용하지 않습니다.") }
        // then
        assertThat(response.status).isEqualTo(401)
    }

    @Test
    @DisplayName("Auth user_id 형식이 아닌 내부 사용자 헤더는 401로 거절한다")
    fun rejectsNonNumericSubject() {
        // given: Auth의 현재 user_id는 정수를 문자열로 바꾼 sub다.
        val request = MockHttpServletRequest("GET", "/api/v1/assets/accounts").apply {
            addHeader(GatewayPrincipalFilter.USER_HEADER, "user-123")
        }
        val response = MockHttpServletResponse()
        // when
        GatewayPrincipalFilter().doFilter(request, response) { _, _ -> error("잘못된 사용자 ID를 허용하면 안 됩니다.") }
        // then
        assertThat(response.status).isEqualTo(401)
    }
}
