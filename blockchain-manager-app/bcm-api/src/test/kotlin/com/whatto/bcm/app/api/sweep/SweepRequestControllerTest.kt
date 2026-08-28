package com.whatto.bcm.app.api.sweep

import com.ninjasquad.springmockk.MockkBean
import com.whatto.bcm.app.api.event.DawIntegrationProperties
import com.whatto.bcm.app.application.sweep.SweepRequestCommand
import com.whatto.bcm.app.application.sweep.SweepRequestService
import com.whatto.bcm.domain.sweep.SweepRequest
import com.whatto.bcm.domain.sweep.SweepRequestItem
import com.whatto.bcm.domain.sweep.SweepRequestItemStatus
import com.whatto.bcm.domain.sweep.SweepRequestStatus
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(SweepRequestController::class)
class SweepRequestControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean
    lateinit var service: SweepRequestService

    @MockkBean
    lateinit var properties: DawIntegrationProperties

    @Test
    fun `DAW CORE batch sweep 요청을 202로 접수한다`() {
        every { properties.enabled } returns true
        every { service.accept(any<SweepRequestCommand>()) } returns request()

        mockMvc
            .perform(
                post("/sweeps")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body()),
            ).andExpect(status().isAccepted)
            .andExpect(jsonPath("$.data.sweepRequestId").value(REQUEST_ID))
            .andExpect(jsonPath("$.data.externalSweepRequestId").value("daw-sweep-1"))
            .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
            .andExpect(jsonPath("$.data.items[0].sweepItemId").value(ITEM_ID))
            .andExpect(jsonPath("$.data.requestedAt").value("2026-08-27T01:02:03Z"))
    }

    @Test
    fun `중복 accountId와 UUID v7이 아닌 source event는 400이다`() {
        every { properties.enabled } returns true

        mockMvc
            .perform(
                post("/sweeps")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body(eventId = "not-an-event")),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `명시적으로 열지 않은 환경에서는 404다`() {
        every { properties.enabled } returns false

        mockMvc
            .perform(post("/sweeps").contentType(MediaType.APPLICATION_JSON).content(body()))
            .andExpect(status().isNotFound)
    }

    private fun body(eventId: String = EVENT_ID) =
        """
        {
          "externalSweepRequestId":"daw-sweep-1",
          "network":"BASE",
          "symbol":"USDC",
          "items":[{"accountId":"account-a","sourceEventIds":["$eventId"]}]
        }
        """.trimIndent()

    private fun request() =
        SweepRequest(
            REQUEST_ID,
            "daw-sweep-1",
            "a".repeat(64),
            "BASE",
            "USDC",
            SweepRequestStatus.ACCEPTED,
            "20260827010203",
            null,
            listOf(SweepRequestItem(ITEM_ID, 1, "account-a", SweepRequestItemStatus.PENDING, listOf(EVENT_ID))),
        )

    private companion object {
        const val REQUEST_ID = "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7801"
        const val ITEM_ID = "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7811"
        const val EVENT_ID = "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7891"
    }
}
