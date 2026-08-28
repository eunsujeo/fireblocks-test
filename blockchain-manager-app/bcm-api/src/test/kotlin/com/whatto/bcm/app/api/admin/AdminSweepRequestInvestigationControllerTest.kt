package com.whatto.bcm.app.api.admin

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi
import com.ninjasquad.springmockk.MockkBean
import com.whatto.bcm.app.application.admin.AdminSweepRequestInvestigationService
import com.whatto.bcm.domain.admin.SweepLinkedEvent
import com.whatto.bcm.domain.admin.SweepOperationsOverview
import com.whatto.bcm.domain.admin.SweepRequestInvestigation
import com.whatto.bcm.domain.admin.SweepRequestItemInvestigation
import com.whatto.bcm.domain.admin.SweepRequestSummary
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.File
import java.time.Instant

@WebMvcTest(AdminSweepRequestInvestigationController::class)
class AdminSweepRequestInvestigationControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean
    lateinit var service: AdminSweepRequestInvestigationService

    @Test
    fun `Sweep 요청은 항목별 event 발행과 DAW 완료를 구분해 반환한다`() {
        every { service.investigate("request-1") } returns investigation()

        mockMvc
            .perform(get("/admin/sweep-request-investigations/request-1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.sweepRequestId").value("request-1"))
            .andExpect(jsonPath("$.data.nextAction").value("WAIT_FOR_RECONCILIATION"))
            .andExpect(jsonPath("$.data.items[0].sourceEvents[0].dawCompletedAt").isNotEmpty)
            .andExpect(jsonPath("$.data.items[0].resultEvents[0].chainStatus").value("FINALIZED"))
            .andExpect(jsonPath("$.data.items[0].resultEvents[0].itemOutcome").value("FAILED"))
            .andExpect(jsonPath("$.data.items[0].resultEvents[0].dawCompletedAt").doesNotExist())
            .andExpect(openApi().isValid(SPEC))
    }

    @Test
    fun `Sweep 운영 요약은 outbox 발행과 DAW 완료 대기를 별도 집계한다`() {
        every { service.operationsOverview() } returns
            SweepOperationsOverview(1, 2, 3, 4, 5, 6, 7, NOW, 8, 9, 10, NOW)

        mockMvc
            .perform(get("/admin/sweep-operations"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.blockedRequestCount").value(2))
            .andExpect(jsonPath("$.data.pendingEventCount").value(8))
            .andExpect(jsonPath("$.data.awaitingDawCompletionCount").value(10))
            .andExpect(openApi().isValid(SPEC))
    }

    private fun investigation() =
        SweepRequestInvestigation(
            summary =
                SweepRequestSummary(
                    sweepRequestId = "request-1",
                    externalSweepRequestId = "daw-request-1",
                    requester = "DAW_CORE",
                    requesterEmployeeNo = "SYSTEM",
                    requesterBranchCode = "9999",
                    network = "BASE",
                    symbol = "USDC",
                    status = "PROCESSING",
                    itemCount = 1,
                    requestedAt = NOW,
                    finishedAt = null,
                    retryable = false,
                    nextAction = "WAIT_FOR_RECONCILIATION",
                ),
            items =
                listOf(
                    SweepRequestItemInvestigation(
                        sweepItemId = "item-1",
                        sequence = 1,
                        accountId = "account-1",
                        status = "PROCESSING",
                        lastFailureCode = null,
                        retryable = false,
                        nextAction = "WAIT_FOR_RECONCILIATION",
                        sourceEvents = listOf(event("source-event", "FINALIZED", "SUCCEEDED", NOW)),
                        executions = emptyList(),
                        resultEvents = listOf(event("result-event", "FINALIZED", "FAILED", null)),
                    ),
                ),
            truncatedSources = emptyList(),
        )

    private fun event(
        eventId: String,
        chainStatus: String,
        outcome: String,
        completedAt: Instant?,
    ) = SweepLinkedEvent(eventId, "TXCF", "S", chainStatus, outcome, null, NOW, completedAt)

    companion object {
        private val NOW = Instant.parse("2026-08-28T00:00:00Z")
        private val SPEC: String = File("../../docs/api/openapi.yaml").absolutePath
    }
}
