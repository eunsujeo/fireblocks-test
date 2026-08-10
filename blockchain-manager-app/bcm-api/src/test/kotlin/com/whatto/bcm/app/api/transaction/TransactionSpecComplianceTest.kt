package com.whatto.bcm.app.api.transaction

import com.atlassian.oai.validator.OpenApiInteractionValidator
import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi
import com.atlassian.oai.validator.whitelist.ValidationErrorsWhitelist
import com.ninjasquad.springmockk.MockkBean
import com.whatto.bcm.app.application.submission.TransactionSubmissionResult
import com.whatto.bcm.app.application.submission.TransactionSubmissionService
import com.whatto.bcm.app.application.transaction.TransactionPage
import com.whatto.bcm.app.application.transaction.TransactionQueryService
import com.whatto.bcm.app.application.transaction.TransactionView
import com.whatto.bcm.domain.tx.TxStatus
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.File

@WebMvcTest(TransactionController::class)
class TransactionSpecComplianceTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean
    lateinit var service: TransactionSubmissionService

    @MockkBean
    lateinit var queryService: TransactionQueryService

    @Test
    fun `submitTransaction 202 응답이 스펙 SubmitResponse와 일치한다`() {
        every { service.submit(any()) } returns TransactionSubmissionResult("tx-91c")

        mockMvc
            .perform(
                post("/transactions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(TransactionControllerTest.VALID_REQUEST),
            ).andExpect(status().isAccepted)
            .andExpect(openApi().isValid(VALIDATOR))
    }

    @Test
    fun `거래 단건 두 경로의 응답이 스펙 TransferResponse와 일치한다`() {
        every { queryService.transaction("tx-91c") } returns view()
        every { queryService.transactionByExternalTransactionId("wd-260713-0042") } returns view()

        mockMvc
            .perform(get("/transactions/tx-91c"))
            .andExpect(status().isOk)
            .andExpect(openApi().isValid(VALIDATOR))
        mockMvc
            .perform(get("/transactions/external/wd-260713-0042"))
            .andExpect(status().isOk)
            .andExpect(openApi().isValid(VALIDATOR))
    }

    @Test
    fun `거래 목록 응답이 스펙 TransferListResponse와 일치한다`() {
        every { queryService.transactions(any(), any()) } returns
            TransactionPage(listOf(view()), "opaque-next", false)

        mockMvc
            .perform(
                get("/accounts/acct_pool_02/transactions")
                    .queryParam("after", "2026-08-07T02:00:00Z")
                    .queryParam("order", "asc")
                    .queryParam("status", "FINALIZED")
                    .queryParam("limit", "200"),
            ).andExpect(status().isOk)
            .andExpect(openApi().isValid(VALIDATOR))
    }

    private fun view() =
        TransactionView(
            transactionId = "tx-91c",
            transactionHash = "0xabc",
            externalTransactionId = "wd-260713-0042",
            network = "ETHEREUM",
            symbol = "USDC",
            amount = "1.50",
            sourceAddress = "0xFrom",
            destinationAddress = "0x9fE2",
            status = TxStatus.FINALIZED,
            confirmationCount = 12,
            createdAt = "2026-08-07T02:05:06.789Z",
            lastUpdated = "2026-08-07T02:06:10.120Z",
        )

    companion object {
        private val SPEC: String = File("../../docs/api/openapi.yaml").absolutePath

        /**
         * validator 3.0.0은 `from`의 두 번째 allOf(const ACCOUNT)를 닫힌 객체로 오판해
         * TransferPeer에서 정의한 accountId를 additional property로 보고한다. 그 한 키만 낮추고 나머지는 검증한다.
         */
        private val VALIDATOR =
            OpenApiInteractionValidator
                .createForSpecificationUrl(SPEC)
                .withWhitelist(
                    ValidationErrorsWhitelist.create().withRule("from-allOf-validator-bug") { message, _, _, _ ->
                        val pointers = message.context.flatMap { it.pointers }.orElse(null)
                        message.key == "validation.request.body.schema.additionalProperties" &&
                            pointers?.instance == "/from" &&
                            pointers.schema == "#/properties/from/allOf/1/additionalProperties"
                    },
                ).build()
    }
}
