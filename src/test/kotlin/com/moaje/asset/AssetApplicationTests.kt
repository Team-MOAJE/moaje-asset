package com.moaje.asset

import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
class AssetApplicationTests(@Autowired private val mockMvc: MockMvc) {
    @Test
    @DisplayName("스프링 애플리케이션 컨텍스트가 정상적으로 로드된다")
    fun contextLoads() {
    }

    @Test
    @DisplayName("OpenAPI 문서에 월별·카테고리 Cashflow 조회 경로가 공개된다")
    fun exposesCashflowOpenApi() {
        mockMvc.get("/v3/api-docs")
            .andExpect {
                status { isOk() }
                jsonPath("$.info.title") { value("Moaje Asset API") }
                jsonPath("$.servers[0].url") { value("/") }
                jsonPath("$.paths['/api/v1/assets/cashflow/monthly']") { exists() }
                jsonPath("$.paths['/api/v1/assets/cashflow/category']") { exists() }
            }
        mockMvc.get("/swagger-ui/index.html")
            .andExpect { status { isOk() } }
    }
}

