package com.whatto.bcm.infra.client.evm

import com.whatto.bcm.domain.sweep.SweepBatchCallItem
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

class EvmErc20ClientTest {
    private val builder = RestClient.builder()
    private val server = MockRestServiceServer.bindTo(builder).build()
    private val client = EvmErc20Client(builder, EvmRpcProperties(mapOf("ETHEREUM" to EvmRpcNetworkProperties("http://rpc"))))

    @Test
    fun `decimals와 allowance를 같은 RPC latest 블록에서 읽어 토큰 금액으로 변환한다`() {
        server
            .expect(requestTo("http://rpc"))
            .andExpect(content().json("""{"method":"eth_call","params":[{"to":"$TOKEN","data":"0x313ce567"},"latest"]}"""))
            .andRespond(withSuccess("""{"jsonrpc":"2.0","id":1,"result":"0x6"}""", MediaType.APPLICATION_JSON))
        server
            .expect(requestTo("http://rpc"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("0xdd62ed3e")))
            .andRespond(withSuccess("""{"jsonrpc":"2.0","id":1,"result":"0x4c4b40"}""", MediaType.APPLICATION_JSON))

        val observation = client.allowance("ETHEREUM", TOKEN, OWNER, SPENDER)

        assertThat(observation.amount).isEqualTo("5")
        assertThat(observation.decimals).isEqualTo(6)
        server.verify()
    }

    @Test
    fun `approve calldata는 spender와 토큰 단위 정수를 ABI word로 인코딩한다`() {
        assertThat(client.approvalCallData(SPENDER, "12.5", 6))
            .isEqualTo(
                "0x095ea7b3" +
                    "0000000000000000000000002222222222222222222222222222222222222222" +
                    "0000000000000000000000000000000000000000000000000000000000bebc20",
            )
    }

    @Test
    fun `batch calldata는 UUID bytes16과 token 및 정렬된 item struct 배열을 인코딩한다`() {
        server
            .expect(requestTo("http://rpc"))
            .andExpect(content().json("""{"method":"eth_call","params":[{"to":"$TOKEN","data":"0x313ce567"},"latest"]}"""))
            .andRespond(withSuccess("""{"jsonrpc":"2.0","id":1,"result":"0x6"}""", MediaType.APPLICATION_JSON))

        val callData =
            client.batchSweepCallData(
                "ETHEREUM",
                EXECUTION_ID,
                TOKEN,
                listOf(SweepBatchCallItem(SPENDER, "3"), SweepBatchCallItem(OWNER, "12.5")),
            )

        assertThat(callData)
            .isEqualTo(
                "0x4209ef32" +
                    "0198765432107abc8def0123456789ab00000000000000000000000000000000" +
                    "0000000000000000000000001111111111111111111111111111111111111111" +
                    "0000000000000000000000000000000000000000000000000000000000000060" +
                    "0000000000000000000000000000000000000000000000000000000000000002" +
                    "0000000000000000000000002222222222222222222222222222222222222222" +
                    "00000000000000000000000000000000000000000000000000000000002dc6c0" +
                    "0000000000000000000000003333333333333333333333333333333333333333" +
                    "0000000000000000000000000000000000000000000000000000000000bebc20",
            )
        server.verify()
    }

    @Test
    fun `token 최소 단위로 정확히 표현되지 않는 batch 금액은 제출하지 않는다`() {
        server
            .expect(requestTo("http://rpc"))
            .andRespond(withSuccess("""{"jsonrpc":"2.0","id":1,"result":"0x6"}""", MediaType.APPLICATION_JSON))

        assertThatThrownBy {
            client.batchSweepCallData(
                "ETHEREUM",
                EXECUTION_ID,
                TOKEN,
                listOf(SweepBatchCallItem(OWNER, "0.0000001")),
            )
        }.isInstanceOf(ArithmeticException::class.java)
    }

    @Test
    fun `batch execution id는 canonical UUID v7만 인코딩한다`() {
        assertThatThrownBy {
            client.batchSweepCallData(
                "ETHEREUM",
                "01987654-3210-6abc-8def-0123456789ab",
                TOKEN,
                listOf(SweepBatchCallItem(OWNER, "1")),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("UUID v7")
    }

    @Test
    fun `batch owner가 중복되거나 주소순이 아니면 RPC 호출 전에 거절한다`() {
        assertThatThrownBy {
            client.batchSweepCallData(
                "ETHEREUM",
                EXECUTION_ID,
                TOKEN,
                listOf(SweepBatchCallItem(OWNER, "1"), SweepBatchCallItem(SPENDER, "1")),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("unique and sorted")
    }

    @Test
    fun `receipt의 SweepLeg indexed topic과 data를 사람 단위 결과로 디코딩한다`() {
        val data =
            "00000000000000000000000000000000000000000000000000000000002dc6c0" +
                "00000000000000000000000000000000000000000000000000000000002625a0" +
                "0000000000000000000000000000000000000000000000000000000000000001" +
                "0000000000000000000000000000000000000000000000000000000000000000"
        val receiptJson =
            """
            {
              "jsonrpc":"2.0","id":1,
              "result":{
                "status":"0x1",
                "logs":[{
                  "address":"$SWEEPER",
                  "topics":[
                    "$SWEEP_LEG_TOPIC",
                    "0x0198765432107abc8def0123456789ab00000000000000000000000000000000",
                    "0x0000000000000000000000000000000000000000000000000000000000000001",
                    "0x0000000000000000000000003333333333333333333333333333333333333333"
                  ],
                  "data":"0x$data",
                  "logIndex":"0x7"
                }]
              }
            }
            """.trimIndent()
        server
            .expect(requestTo("http://rpc"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("eth_getTransactionReceipt")))
            .andRespond(withSuccess(receiptJson, MediaType.APPLICATION_JSON))

        val receipt = client.receipt("ETHEREUM", "0xabc", SWEEPER, 6)

        assertThat(receipt?.successful).isTrue()
        assertThat(receipt?.legs?.single())
            .extracting(
                "executionId",
                "itemSequence",
                "ownerAddress",
                "requestedAmount",
                "actualAmount",
                "successful",
                "failureCode",
                "logIndex",
            ).containsExactly(EXECUTION_ID, 1, OWNER, "3", "2.5", true, "0".repeat(64), 7)
        server.verify()
    }

    @Test
    fun `아직 채굴되지 않은 transaction receipt는 null이다`() {
        server
            .expect(requestTo("http://rpc"))
            .andRespond(withSuccess("""{"jsonrpc":"2.0","id":1,"result":null}""", MediaType.APPLICATION_JSON))

        assertThat(client.receipt("ETHEREUM", "0xabc", SWEEPER, 6)).isNull()
    }

    @Test
    fun `설정되지 않은 네트워크는 RPC 호출 전에 실패한다`() {
        assertThatThrownBy { client.allowance("BASE", TOKEN, OWNER, SPENDER) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not configured")
    }

    private companion object {
        const val TOKEN = "0x1111111111111111111111111111111111111111"
        const val SPENDER = "0x2222222222222222222222222222222222222222"
        const val OWNER = "0x3333333333333333333333333333333333333333"
        const val SWEEPER = "0x4444444444444444444444444444444444444444"
        const val SWEEP_LEG_TOPIC = "0x018084291c296fa2423afb4bbaad7ec89aad291255cc83c584e3f054c93cabb0"
        const val EXECUTION_ID = "01987654-3210-7abc-8def-0123456789ab"
    }
}
