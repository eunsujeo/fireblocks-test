package com.whatto.bcm.app.api.admin

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi
import com.ninjasquad.springmockk.MockkBean
import com.whatto.bcm.app.application.admin.AdminTransactionInvestigationService
import com.whatto.bcm.domain.admin.TransactionBoostAttempt
import com.whatto.bcm.domain.admin.TransactionFeeQuote
import com.whatto.bcm.domain.admin.TransactionInvestigation
import com.whatto.bcm.domain.admin.TransactionInvestigationSummary
import com.whatto.bcm.domain.admin.TransactionTimelineEntry
import io.mockk.every
import io.mockk.verify
import org.hamcrest.Matchers.hasKey
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.File
import java.time.Instant

@WebMvcTest(AdminTransactionInvestigationController::class)
class AdminTransactionInvestigationControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean
    lateinit var service: AdminTransactionInvestigationService

    @Test
    fun `거래 조사 응답은 root와 물리 거래를 구분하고 구조화된 운영 근거만 노출한다`() {
        every { service.investigate("wd-1") } returns investigation()

        mockMvc
            .perform(get("/admin/transaction-investigations/wd-1").header("X-Request-Id", "request-1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.meta.requestId").isNotEmpty)
            .andExpect(jsonPath("$.data.summary.rootTransactionId").value("tx-root"))
            .andExpect(jsonPath("$.data.summary.activeTransactionId").value("tx-new"))
            .andExpect(jsonPath("$.data.summary.amount").value("30"))
            .andExpect(jsonPath("$.data.timeline[0].source").value("SUBMISSION"))
            .andExpect(jsonPath("$.data.boosts[0].newTransactionId").value("tx-new"))
            .andExpect(jsonPath("$.data.feeQuotes[0].gasPrice").value("2.1"))
            .andExpect(jsonPath("$.data", not(hasKey<String>("rawPayload"))))
            .andExpect(jsonPath("$.data", not(hasKey<String>("signature"))))
            .andExpect(openApi().isValid(SPEC))
    }

    @Test
    fun `식별자가 최대 길이를 넘으면 조회하지 않고 400을 반환한다`() {
        mockMvc
            .perform(get("/admin/transaction-investigations/${"x".repeat(129)}"))
            .andExpect(status().isBadRequest)

        verify(exactly = 0) { service.investigate(any()) }
    }

    private fun investigation() =
        TransactionInvestigation(
            summary =
                TransactionInvestigationSummary(
                    rootTransactionId = "tx-root",
                    activeTransactionId = "tx-new",
                    externalTransactionId = "wd-1",
                    transactionHash = "0xactive",
                    accountId = "acct-1",
                    network = "BASE",
                    symbol = "USDC",
                    transactionType = "WITHDRAWAL",
                    status = "CONFIRMED",
                    confirmationCount = 0,
                    vendorSubStatus = "PENDING_BLOCKCHAIN_CONFIRMATIONS",
                    vendorNetworkStatus = "CONFIRMING",
                    submissionStatus = "SUBMITTED",
                    amount = "30".toBigDecimal(),
                    senderAccountId = "acct-1",
                    receiverType = "ADDRESS",
                    receiverValue = "0xdestination",
                    sweepExecutionId = null,
                    submissionRequestedAt = Instant.parse("2026-08-17T12:00:00Z"),
                    submissionRespondedAt = Instant.parse("2026-08-17T12:00:01Z"),
                    vendorCreatedAt = Instant.parse("2026-08-17T12:00:00Z"),
                    firstDetectedAt = Instant.parse("2026-08-17T12:00:10Z"),
                    lastChangedAt = Instant.parse("2026-08-17T12:07:00Z"),
                    reconciliationCheckedAt = Instant.parse("2026-08-17T12:08:00Z"),
                    reconciliationCheckCount = 2,
                    reconciliationStoppedAt = null,
                ),
            timeline =
                listOf(
                    TransactionTimelineEntry(
                        source = "SUBMISSION",
                        code = "INTENT",
                        status = "SUBMITTED",
                        observedAt = Instant.parse("2026-08-17T12:00:00Z"),
                        identifier = "wd-1",
                    ),
                ),
            boosts =
                listOf(
                    TransactionBoostAttempt(
                        attemptSequence = 1,
                        externalTransactionId = "bst-1",
                        status = "SUBMITTED",
                        replacedTransactionId = "tx-root",
                        replacedTransactionHash = "0xold",
                        newTransactionId = "tx-new",
                        feeLevel = "HIGH",
                        gasless = true,
                        requestedAt = Instant.parse("2026-08-17T12:05:00Z"),
                        respondedAt = Instant.parse("2026-08-17T12:05:01Z"),
                    ),
                ),
            sweepExecution = null,
            allowances = emptyList(),
            feeQuotes =
                listOf(
                    TransactionFeeQuote(
                        context = "SUBMISSION",
                        level = "MEDIUM",
                        observedAt = Instant.parse("2026-08-17T11:59:00Z"),
                        feePerByte = null,
                        gasPrice = "2.1".toBigDecimal(),
                        networkFee = null,
                        baseFee = null,
                        priorityFee = null,
                    ),
                ),
            truncatedSources = emptyList(),
        )

    companion object {
        private val SPEC: String = File("../../docs/api/openapi.yaml").absolutePath
    }
}
