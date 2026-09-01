package com.whatto.bcm.app.api.web

import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 에러 응답 계약 — error.code 10종 · HTTP status · envelope (OpenAPI 에러 표 · error-handling.md).
 */
@WebMvcTest(EnvelopeTestController::class)
class ApiExceptionHandlerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `계정 없음 — 404 ACCOUNT_NOT_FOUND (주소 미발급 NOT_FOUND 와 구분)`() {
        mockMvc
            .perform(get("/test-envelope/account-missing"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_FOUND"))
            .andExpect(jsonPath("$.error.message").isNotEmpty)
            .andExpect(jsonPath("$.meta.requestId").isNotEmpty)
            .andExpect(jsonPath("$.data").doesNotExist())
    }

    @Test
    fun `그 밖의 리소스 없음 — 404 NOT_FOUND`() {
        mockMvc
            .perform(get("/test-envelope/resource-missing"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("NOT_FOUND"))
    }

    @Test
    fun `상태·멱등 충돌 — 409 CONFLICT`() {
        mockMvc
            .perform(get("/test-envelope/conflict"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error.code").value("CONFLICT"))
    }

    @Test
    fun `relay 거절 — 502 RELAY_REJECTED`() {
        mockMvc
            .perform(get("/test-envelope/relay-rejected"))
            .andExpect(status().isBadGateway)
            .andExpect(jsonPath("$.error.code").value("RELAY_REJECTED"))
    }

    @Test
    fun `동일 키 제출 진행 중 — 503 SUBMIT_IN_PROGRESS와 Retry-After`() {
        mockMvc
            .perform(get("/test-envelope/submission-in-progress"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.error.code").value("SUBMIT_IN_PROGRESS"))
            .andExpect(
                header()
                    .string("Retry-After", "3"),
            )
    }

    @Test
    fun `생성 키 cooldown — 503 CREATION_RETRY_LATER와 정확한 재시도 시간`() {
        mockMvc
            .perform(get("/test-envelope/creation-retry-later"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.error.code").value("CREATION_RETRY_LATER"))
            .andExpect(jsonPath("$.error.retryAfterSeconds").value(82_800))
            .andExpect(header().string("Retry-After", "82800"))
    }

    @Test
    fun `본문이 규약에 안 맞으면 — 400 VALIDATION_FAILED`() {
        mockMvc
            .perform(
                post("/test-envelope/echo")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{ broken json"),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.meta.requestId").isNotEmpty)
    }

    @Test
    fun `벤더 호출 실패 — 500 INTERNAL (스펙 에러 표에 벤더용 코드 없음)`() {
        mockMvc
            .perform(get("/test-envelope/vendor-failure"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.error.code").value("INTERNAL"))
    }

    @Test
    fun `예상치 못한 예외 — 500 INTERNAL, 내부 메시지를 노출하지 않는다`() {
        mockMvc
            .perform(get("/test-envelope/boom"))
            .andExpect(status().isInternalServerError)
            .andExpect(jsonPath("$.error.code").value("INTERNAL"))
            .andExpect(content().string(not(org.hamcrest.Matchers.containsString("internal secret detail"))))
    }
}
