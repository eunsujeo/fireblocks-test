package com.whatto.bcm.infra.client.evm

import com.whatto.bcm.domain.tx.ChainHeadPort
import org.springframework.web.client.RestClient
import java.math.BigInteger

/**
 * ChainHeadPort의 EVM JSON-RPC 구현 — `eth_blockNumber`로 현재 head를 읽는다(계약13 "확정 판정").
 * 위탁 RPC endpoint는 `bcm.evm-rpc.networks.<network>.url`을 그대로 쓴다.
 *
 * 실패(미설정 네트워크·RPC 오류·결손·형식 오류)는 감추지 않고 예외로 올린다 — head를 모르는 동안 확정을 내지 않되,
 * 모름을 "아직 미확정"으로 바꾸지도 않는다. 판단 경로가 재시도로 회수한다. **내부 대역이며 실행 빈으로 등록하지 않았다.**
 */
class EvmChainHeadClient(
    builder: RestClient.Builder,
    properties: EvmRpcProperties,
) : ChainHeadPort {
    private val clients = properties.networks.mapValues { (_, network) -> builder.clone().baseUrl(network.url).build() }

    override fun headBlockNumber(network: String): Long {
        val client = checkNotNull(clients[network]) { "EVM RPC is not configured: network=$network" }
        val response =
            client
                .post()
                .body(JsonRpcRequest(method = "eth_blockNumber", params = emptyList()))
                .retrieve()
                .body(JsonRpcResponse::class.java)
                ?: error("EVM RPC response body is missing: network=$network")
        check(response.error == null) { "EVM RPC returned an error: network=$network code=${response.error?.code}" }
        val result = checkNotNull(response.result) { "EVM RPC result is missing: network=$network" }
        return blockNumber(result, network)
    }

    /** JSON-RPC quantity — `0x` 접두사의 16진수다. 빈 값·음수·Long 범위 밖은 head로 받지 않는다. */
    private fun blockNumber(
        result: String,
        network: String,
    ): Long {
        require(result.startsWith("0x")) { "EVM RPC result is not hex: network=$network" }
        val digits = result.removePrefix("0x")
        require(digits.isNotEmpty() && digits.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
            "EVM RPC result is not hex: network=$network"
        }
        val value = BigInteger(digits, 16)
        require(value.bitLength() < Long.SIZE_BITS) { "EVM RPC block number is out of range: network=$network" }
        return value.toLong()
    }

    private data class JsonRpcRequest(
        val jsonrpc: String = "2.0",
        val id: Int = 1,
        val method: String,
        val params: List<Any>,
    )

    private data class JsonRpcResponse(
        val result: String? = null,
        val error: JsonRpcError? = null,
    )

    private data class JsonRpcError(
        val code: Int? = null,
    )
}
