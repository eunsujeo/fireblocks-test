package com.whatto.bcm.app.api.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper

/**
 * 응답 envelope 계약 — data/meta.requestId/pagination (openapi v0.0.5 info 절).
 */
@WebMvcTest(EnvelopeTestController::class)
class ApiEnvelopeTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @Test
    fun `단일 리소스 — data 와 meta requestId 를 담고 pagination 은 없다`() {
        mockMvc
            .perform(get("/test-envelope/single"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.ref").value("ACT-000123"))
            .andExpect(jsonPath("$.meta.requestId").isNotEmpty)
            .andExpect(jsonPath("$.pagination").doesNotExist())
            .andExpect(jsonPath("$.error").doesNotExist())
    }

    @Test
    fun `목록 리소스 — pagination 에 nextCursor 와 hasMore 를 담는다`() {
        mockMvc
            .perform(get("/test-envelope/paged"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].txId").value("tx_9f2a"))
            .andExpect(jsonPath("$.meta.requestId").isNotEmpty)
            .andExpect(jsonPath("$.pagination.nextCursor").value("cursor-next"))
            .andExpect(jsonPath("$.pagination.hasMore").value(true))
    }

    @Test
    fun `requestId 는 요청마다 새로 발급된다`() {
        val first = requestIdOfSingleCall()
        val second = requestIdOfSingleCall()

        assertThat(first).isNotBlank()
        assertThat(first).isNotEqualTo(second)
    }

    private fun requestIdOfSingleCall(): String {
        val body =
            mockMvc
                .perform(get("/test-envelope/single"))
                .andExpect(status().isOk)
                .andReturn()
                .response.contentAsString
        return objectMapper
            .readTree(body)
            .path("meta")
            .path("requestId")
            .asString()
    }
}
