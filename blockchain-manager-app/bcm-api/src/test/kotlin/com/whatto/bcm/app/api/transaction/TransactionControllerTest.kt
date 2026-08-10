package com.whatto.bcm.app.api.transaction

import com.ninjasquad.springmockk.MockkBean
import com.whatto.bcm.app.application.submission.TransactionSubmissionCommand
import com.whatto.bcm.app.application.submission.TransactionSubmissionRecipient
import com.whatto.bcm.app.application.submission.TransactionSubmissionResult
import com.whatto.bcm.app.application.submission.TransactionSubmissionService
import com.whatto.bcm.app.application.transaction.TransactionPage
import com.whatto.bcm.app.application.transaction.TransactionPageQuery
import com.whatto.bcm.app.application.transaction.TransactionQueryService
import com.whatto.bcm.app.application.transaction.TransactionView
import com.whatto.bcm.domain.tx.TxStatus
import io.mockk.every
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(TransactionController::class)
class TransactionControllerTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @MockkBean
    lateinit var service: TransactionSubmissionService

    @MockkBean
    lateinit var queryService: TransactionQueryService

    @Test
    fun `출금을 제출하면 명령을 변환하고 txId만 담아 202를 반환한다`() {
        val command = slot<TransactionSubmissionCommand>()
        every { service.submit(capture(command)) } returns TransactionSubmissionResult("tx-91c")

        mockMvc
            .perform(post("/transactions").contentType(MediaType.APPLICATION_JSON).content(VALID_REQUEST))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.data.txId").value("tx-91c"))
            .andExpect(jsonPath("$.data.vendorAssetId").doesNotExist())

        assertThat(command.captured.senderAccountId).isEqualTo("acct_pool_02")
        assertThat(command.captured.recipient).isEqualTo(TransactionSubmissionRecipient.Address("0x9fE2"))
        assertThat(command.captured.symbol).isEqualTo("USDC")
    }

    @Test
    fun `보내는 쪽은 ACCOUNT만 허용한다`() {
        mockMvc
            .perform(
                post("/transactions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        VALID_REQUEST.replace(
                            "\"type\":\"ACCOUNT\",\"accountId\":\"acct_pool_02\"",
                            "\"type\":\"ADDRESS\",\"address\":\"0xsource\"",
                        ),
                    ),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `목적지 유형의 식별자가 없거나 금액이 decimal 문자열이 아니면 400이다`() {
        mockMvc
            .perform(
                post("/transactions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_REQUEST.replace("\"address\":\"0x9fE2\"", "\"accountId\":\"acct_dest\"")),
            ).andExpect(status().isBadRequest)

        mockMvc
            .perform(
                post("/transactions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(VALID_REQUEST.replace("\"amount\":\"1.5\"", "\"amount\":\"1e3\"")),
            ).andExpect(status().isBadRequest)
    }

    @Test
    fun `0원이거나 원장 NUMERIC 36 18에 정확히 저장할 수 없는 금액이면 400이다`() {
        listOf(
            "0",
            "0.0000000000000000001",
            "1000000000000000000",
        ).forEach { amount ->
            mockMvc
                .perform(
                    post("/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_REQUEST.replace("\"amount\":\"1.5\"", "\"amount\":\"$amount\"")),
                ).andExpect(status().isBadRequest)
        }
    }

    @Test
    fun `txId와 externalTxId로 거래 단건을 조회한다`() {
        every { queryService.transaction("tx-91c") } returns view()
        every { queryService.transactionByExternalTransactionId("wd-260713-0042") } returns view()

        mockMvc
            .perform(get("/transactions/tx-91c"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.txId").value("tx-91c"))
            .andExpect(jsonPath("$.data.symbol").value("USDC"))
            .andExpect(jsonPath("$.data.status").value("FINALIZED"))

        mockMvc
            .perform(get("/transactions/external/wd-260713-0042"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.externalTxId").value("wd-260713-0042"))
    }

    @Test
    fun `SUBMITTED 거래의 온체인 주소가 미확정이면 from과 to 키를 null로 반환한다`() {
        every { queryService.transaction("tx-new") } returns view(sourceAddress = null, destinationAddress = null)

        mockMvc
            .perform(get("/transactions/tx-new"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.from").value(nullValue()))
            .andExpect(jsonPath("$.data.to").value(nullValue()))
    }

    @Test
    fun `목록 쿼리를 그대로 서비스에 넘기고 pagination을 응답한다`() {
        val query = slot<TransactionPageQuery>()
        every { queryService.transactions("acct_pool_02", capture(query)) } returns
            TransactionPage(listOf(view()), "opaque-next", false)

        mockMvc
            .perform(
                get("/accounts/acct_pool_02/transactions")
                    .queryParam("cursor", "opaque-current")
                    .queryParam("after", "ignored-invalid-date")
                    .queryParam("limit", "ignored-invalid-limit"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].txId").value("tx-91c"))
            .andExpect(jsonPath("$.pagination.nextCursor").value("opaque-next"))
            .andExpect(jsonPath("$.pagination.hasMore").value(false))

        assertThat(query.captured.cursor).isEqualTo("opaque-current")
        assertThat(query.captured.after).isEqualTo("ignored-invalid-date")
        assertThat(query.captured.limit).isEqualTo("ignored-invalid-limit")
    }

    private fun view(
        sourceAddress: String? = "0xFrom",
        destinationAddress: String? = "0x9fE2",
    ) = TransactionView(
        transactionId = "tx-91c",
        transactionHash = "0xabc",
        externalTransactionId = "wd-260713-0042",
        network = "ETHEREUM",
        symbol = "USDC",
        amount = "1.50",
        sourceAddress = sourceAddress,
        destinationAddress = destinationAddress,
        status = TxStatus.FINALIZED,
        confirmationCount = 12,
        createdAt = "2026-08-07T02:05:06.789Z",
        lastUpdated = "2026-08-07T02:06:10.120Z",
    )

    companion object {
        const val VALID_REQUEST =
            """{"externalTxId":"wd-260713-0042","from":{"type":"ACCOUNT","accountId":"acct_pool_02"},"to":{"type":"ADDRESS","address":"0x9fE2"},"network":"ETHEREUM","symbol":"USDC","amount":"1.5","note":null,"travelRule":null}"""
    }
}
