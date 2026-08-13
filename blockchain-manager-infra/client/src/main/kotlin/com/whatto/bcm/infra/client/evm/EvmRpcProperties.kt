package com.whatto.bcm.infra.client.evm

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("bcm.evm-rpc")
data class EvmRpcProperties(
    val networks: Map<String, EvmRpcNetworkProperties> = emptyMap(),
)

data class EvmRpcNetworkProperties(
    val url: String = "",
) {
    init {
        require(url.isNotBlank()) { "EVM RPC url must not be blank" }
    }
}
