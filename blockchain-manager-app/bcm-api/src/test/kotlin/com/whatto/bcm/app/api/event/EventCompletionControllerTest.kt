package com.whatto.bcm.app.api.event

import com.ninjasquad.springmockk.MockkBean
import com.whatto.bcm.app.application.event.EventCompletionService
import com.whatto.bcm.domain.event.EventCompletion
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(EventCompletionController::class)
class EventCompletionControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean
    lateinit var service: EventCompletionService

    @MockkBean
    lateinit var properties: DawIntegrationProperties

    @Test
    fun `DAW CORE 이벤트 처리 완료를 eventId로 확인한다`() {
        every { properties.enabled } returns true
        every { service.complete(EVENT_ID) } returns completion()

        mockMvc
            .perform(put("/events/$EVENT_ID/completion"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.eventId").value(EVENT_ID))
            .andExpect(jsonPath("$.data.consumer").value("DAW_CORE"))
            .andExpect(jsonPath("$.data.txId").value("tx-91c"))
            .andExpect(jsonPath("$.data.status").value("FINALIZED"))
            .andExpect(jsonPath("$.data.completedAt").value("2026-08-27T01:02:03Z"))
    }

    @Test
    fun `공유 환경에서 명시적으로 열지 않으면 route를 404로 닫는다`() {
        every { properties.enabled } returns false

        mockMvc.perform(put("/events/$EVENT_ID/completion")).andExpect(status().isNotFound)
    }

    @Test
    fun `eventId가 canonical UUID가 아니면 400이다`() {
        every { properties.enabled } returns true

        mockMvc.perform(put("/events/not-an-event/completion")).andExpect(status().isBadRequest)
    }

    private fun completion() =
        EventCompletion(
            eventId = EVENT_ID,
            consumer = "DAW_CORE",
            completedAt = "20260827010203",
            transactionId = "tx-91c",
            status = "FINALIZED",
            sweepRequestId = null,
            sweepItemId = null,
            executionId = null,
        )

    private companion object {
        const val EVENT_ID = "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7891"
    }
}
