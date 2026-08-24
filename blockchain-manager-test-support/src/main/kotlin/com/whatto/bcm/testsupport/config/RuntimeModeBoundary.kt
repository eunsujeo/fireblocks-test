package com.whatto.bcm.testsupport.config

import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

enum class VendorMode {
    STUB,
    FIREBLOCKS,
}

enum class ChainMode {
    LOCAL,
    TESTNET,
    MAINNET,
}

enum class ApiAuthenticationMode {
    BASIC,
    STRICT,
}

@ConfigurationProperties("bcm.test-support")
data class TestSupportProperties(
    val vendorMode: VendorMode = VendorMode.STUB,
    val chainMode: ChainMode = ChainMode.LOCAL,
    val serverAddress: String = LOOPBACK,
    val managementAddress: String = LOOPBACK,
    val fireblocksBaseUrl: String = "http://$LOOPBACK:18080",
    val fireblocksApiKey: String = LOCAL_STUB_API_KEY,
    val fireblocksPrivateKeyPem: String = "",
    val fireblocksPrivateKeyFile: String = "",
    val allowedLocalPrivateKeySha256: String = "",
    val webhookJwksUrl: String = "http://$LOOPBACK:18080/.well-known/jwks.json",
    val evmRpcUrl: String = "http://$LOOPBACK:8545",
    val evmChainId: Long = LOCAL_CHAIN_ID,
    val localChainManifestFile: String = "build/local/chain/manifest.json",
    val localChainKeyFile: String = "build/local/chain/evm-keys.json",
    val apiAuthenticationMode: ApiAuthenticationMode = ApiAuthenticationMode.BASIC,
    val apiPublicKeyFile: String = "",
    val webhookDeliveryUrl: String = "",
    val resetEnabled: Boolean = false,
) : InitializingBean {
    override fun afterPropertiesSet() = RuntimeModeBoundary(this).validate()

    companion object {
        const val LOCAL_STUB_API_KEY = "bcm-local-stub"
        const val LOCAL_CHAIN_ID = 31337L
        private const val LOOPBACK = "127.0.0.1"
    }
}

class RuntimeModeBoundary(
    private val properties: TestSupportProperties,
) {
    fun validate() {
        check(properties.vendorMode to properties.chainMode in ALLOWED_MODES) {
            "unsupported runtime mode: ${properties.vendorMode}+${properties.chainMode}"
        }
        check(!properties.resetEnabled || properties.vendorMode == VendorMode.STUB && properties.chainMode == ChainMode.LOCAL) {
            "local environment reset requires STUB+LOCAL"
        }
        if (properties.vendorMode == VendorMode.STUB && properties.chainMode == ChainMode.LOCAL) {
            validateLocalBoundary()
        }
    }

    private fun validateLocalBoundary() {
        requireInternalAddress("server address", properties.serverAddress)
        requireInternalAddress("management address", properties.managementAddress)
        requireInternalEndpoint("Fireblocks Base URL", properties.fireblocksBaseUrl)
        requireInternalEndpoint("Webhook JWKS URL", properties.webhookJwksUrl)
        requireInternalEndpoint("EVM RPC URL", properties.evmRpcUrl)
        if (properties.webhookDeliveryUrl.isNotBlank()) {
            requireInternalEndpoint("Webhook delivery URL", properties.webhookDeliveryUrl)
        }
        check(properties.evmChainId == TestSupportProperties.LOCAL_CHAIN_ID) {
            "STUB+LOCAL requires the fixed local chain id"
        }
        check(properties.fireblocksApiKey == TestSupportProperties.LOCAL_STUB_API_KEY) {
            "STUB+LOCAL requires the local API key marker"
        }
        check(properties.fireblocksPrivateKeyPem.isBlank()) {
            "STUB+LOCAL rejects an inline Fireblocks private key"
        }
        validateLocalPrivateKeyFile()
        if (properties.apiAuthenticationMode == ApiAuthenticationMode.STRICT) {
            check(properties.apiPublicKeyFile.isNotBlank()) { "STUB+LOCAL strict API authentication requires a public key file" }
            check(Files.isRegularFile(Path.of(properties.apiPublicKeyFile))) {
                "STUB+LOCAL strict API authentication public key file is not readable"
            }
        }
    }

    private fun validateLocalPrivateKeyFile() {
        val fileConfigured = properties.fireblocksPrivateKeyFile.isNotBlank()
        val fingerprintConfigured = properties.allowedLocalPrivateKeySha256.isNotBlank()
        check(fileConfigured == fingerprintConfigured) {
            "STUB+LOCAL requires a test key file and its allowed fingerprint together"
        }
        if (!fileConfigured) return

        check(HEX_SHA256.matches(properties.allowedLocalPrivateKeySha256)) {
            "STUB+LOCAL local key fingerprint must be lowercase SHA-256"
        }
        val keyBytes =
            try {
                Files.readAllBytes(Path.of(properties.fireblocksPrivateKeyFile))
            } catch (exception: Exception) {
                throw IllegalStateException("STUB+LOCAL test key file is not readable", exception)
            }
        check(sha256Hex(keyBytes) == properties.allowedLocalPrivateKeySha256) {
            "STUB+LOCAL test key fingerprint does not match"
        }
    }

    private fun requireInternalEndpoint(
        label: String,
        value: String,
    ) {
        val uri =
            try {
                URI.create(value)
            } catch (exception: IllegalArgumentException) {
                throw IllegalStateException("STUB+LOCAL $label must be an internal HTTP endpoint", exception)
            }
        check(uri.scheme in HTTP_SCHEMES && uri.host?.isInternalAddress() == true && uri.userInfo == null) {
            "STUB+LOCAL $label must be an internal HTTP endpoint"
        }
    }

    private fun requireInternalAddress(
        label: String,
        value: String,
    ) {
        check(value.isInternalAddress()) { "STUB+LOCAL $label must be internal" }
    }

    private fun String.isInternalAddress(): Boolean {
        val normalized = lowercase().removePrefix("[").removeSuffix("]")
        if (normalized in LOOPBACK_NAMES) return true
        val octets = normalized.split('.').mapNotNull(String::toIntOrNull)
        if (octets.size == 4 && octets.all { it in 0..255 }) {
            return octets[0] == 127 ||
                octets[0] == 10 ||
                (octets[0] == 172 && octets[1] in 16..31) ||
                (octets[0] == 192 && octets[1] == 168)
        }
        return ':' in normalized && (normalized.startsWith("fc") || normalized.startsWith("fd"))
    }

    private fun sha256Hex(value: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value)
            .joinToString("") { "%02x".format(it) }

    companion object {
        private val ALLOWED_MODES =
            setOf(
                VendorMode.STUB to ChainMode.LOCAL,
                VendorMode.FIREBLOCKS to ChainMode.TESTNET,
                VendorMode.FIREBLOCKS to ChainMode.MAINNET,
            )
        private val HTTP_SCHEMES = setOf("http", "https")
        private val HEX_SHA256 = Regex("[0-9a-f]{64}")
        private val LOOPBACK_NAMES = setOf("localhost", "::1", "0:0:0:0:0:0:0:1")
    }
}
