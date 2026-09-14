package com.whatto.bcm.infra.client.config

import com.whatto.bcm.infra.client.evm.EvmRpcProperties
import com.whatto.bcm.infra.client.fireblocks.FireblocksProperties
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.net.URI

/** 일반 빈(DB·클라이언트·스케줄러)을 만들기 전에 실행 선택과 연결 경계를 검증한다. */
@Configuration(proxyBeanMethods = false)
class ProviderConfiguration {
    companion object {
        @Bean
        @JvmStatic
        fun providerSelectionGuard(environment: Environment): BeanFactoryPostProcessor =
            BeanFactoryPostProcessor {
                val provider = environment.getProperty("bcm.provider")
                check(provider in setOf("fireblocks", "dfns", "local")) {
                    "BCM_PROVIDER must be one of fireblocks, dfns, local"
                }
                check(provider != "dfns") { "BCM_PROVIDER=dfns is not implemented; no provider fallback is allowed" }
                val local = provider == "local"
                environment.getProperty("bcm.vendor-mode")?.let { mode ->
                    check(mode == if (local) "STUB" else "FIREBLOCKS") {
                        "BCM_VENDOR_MODE conflicts with BCM_PROVIDER"
                    }
                }
                environment.getProperty("bcm.chain-mode")?.let { mode ->
                    check(if (local) mode == "LOCAL" else mode in setOf("TESTNET", "MAINNET")) {
                        "BCM_CHAIN_MODE conflicts with BCM_PROVIDER"
                    }
                }
                val binder = Binder.get(environment)
                val properties = binder.bind("bcm.fireblocks", FireblocksProperties::class.java).orElseGet(::FireblocksProperties)
                if (local) {
                    requireLocalEndpoint(properties.baseUrl)
                    requireLocalEndpoint(properties.webhookJwksUrl)
                    val rpc = binder.bind("bcm.evm-rpc", EvmRpcProperties::class.java).orElseGet(::EvmRpcProperties)
                    rpc.networks.values.forEach { requireLocalEndpoint(it.url) }
                    check(properties.apiKey == "bcm-local-stub") { "local requires bcm-local-stub API key marker" }
                }
                check(properties.apiKey.isNotBlank()) { "bcm.fireblocks.api-key is required for the selected provider" }
                check(properties.privateKeyPem.isNotBlank() || properties.privateKeyFile.isNotBlank()) {
                    "bcm.fireblocks.private-key-pem or private-key-file is required for the selected provider"
                }
            }

        private fun requireLocalEndpoint(value: String) {
            val uri =
                try {
                    URI(value)
                } catch (exception: java.net.URISyntaxException) {
                    throw IllegalStateException("local requires internal HTTP endpoints", exception)
                }
            check(uri.scheme in setOf("http", "https") && uri.userInfo == null && isInternalHost(uri.host)) {
                "local requires internal HTTP endpoints"
            }
        }

        private fun isInternalHost(host: String?): Boolean {
            val normalized = host?.lowercase()?.removePrefix("[")?.removeSuffix("]") ?: return false
            if (normalized in setOf("localhost", "::1", "0:0:0:0:0:0:0:1")) return true
            if (':' in normalized) return normalized.startsWith("fc") || normalized.startsWith("fd")
            val parts = normalized.split('.')
            if (parts.size != 4 || parts.any { it.isEmpty() || it.any { char -> char !in '0'..'9' } }) return false
            val octets = parts.map { it.toIntOrNull() ?: return false }
            if (octets.any { it !in 0..255 }) return false
            return octets[0] == 127 ||
                octets[0] == 10 ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168)
        }
    }
}
