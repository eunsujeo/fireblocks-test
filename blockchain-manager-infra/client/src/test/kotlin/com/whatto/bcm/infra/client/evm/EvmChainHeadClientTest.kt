package com.whatto.bcm.infra.client.evm

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.content
import org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient

/** `eth_blockNumber`로 head를 읽는 어댑터(계약13 "확정 판정") — 실패를 감추지 않는다. */
class EvmChainHeadClientTest {
    @Test
    fun `eth_blockNumber 결과를 head 블록 번호로 읽는다`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server
            .expect(requestTo(RPC_URL))
            .andExpect(content().contentType(MediaType.APPLICATION_JSON))
            .andExpect(jsonPath("$.method").value("eth_blockNumber"))
            .andExpect(jsonPath("$.params").isEmpty())
            .andRespond(withSuccess("""{"jsonrpc":"2.0","id":1,"result":"0x80f317"}""", MediaType.APPLICATION_JSON))

        assertThat(client(builder).headBlockNumber(NETWORK)).isEqualTo(8_450_839L)
        server.verify()
    }

    @Test
    fun `설정에 없는 네트워크는 임의 endpoint를 고르지 않는다`() {
        assertThatThrownBy { client(RestClient.builder()).headBlockNumber("SOLANA_DEVNET") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not configured")
    }

    @Test
    fun `RPC 오류·결손·형식 오류는 head로 받지 않고 그대로 올린다`() {
        listOf(
            """{"jsonrpc":"2.0","id":1,"error":{"code":-32000}}""" to "error",
            """{"jsonrpc":"2.0","id":1}""" to "result is missing",
            """{"jsonrpc":"2.0","id":1,"result":"8450839"}""" to "not hex",
            """{"jsonrpc":"2.0","id":1,"result":"0x"}""" to "not hex",
            """{"jsonrpc":"2.0","id":1,"result":"0xzz"}""" to "not hex",
            // 부호 있는 64비트를 넘는 값은 블록 번호로 다루지 않는다.
            """{"jsonrpc":"2.0","id":1,"result":"0x${"f".repeat(16)}"}""" to "out of range",
        ).forEach { (body, message) ->
            val builder = RestClient.builder()
            val server = MockRestServiceServer.bindTo(builder).build()
            server.expect(requestTo(RPC_URL)).andRespond(withSuccess(body, MediaType.APPLICATION_JSON))

            assertThatThrownBy { client(builder).headBlockNumber(NETWORK) }
                .describedAs(body)
                .isInstanceOf(RuntimeException::class.java)
                .hasMessageContaining(message)
            server.verify()
        }
    }

    @Test
    fun `HTTP 실패는 확정 보류로 이어지도록 예외를 전파한다`() {
        val builder = RestClient.builder()
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo(RPC_URL)).andRespond(withServerError())

        assertThatThrownBy { client(builder).headBlockNumber(NETWORK) }.isInstanceOf(RuntimeException::class.java)
        server.verify()
    }

    private fun client(builder: RestClient.Builder) =
        EvmChainHeadClient(builder, EvmRpcProperties(mapOf(NETWORK to EvmRpcNetworkProperties(RPC_URL))))

    private companion object {
        const val NETWORK = "ETHEREUM_SEPOLIA"
        const val RPC_URL = "https://rpc.internal.test"
    }
}
