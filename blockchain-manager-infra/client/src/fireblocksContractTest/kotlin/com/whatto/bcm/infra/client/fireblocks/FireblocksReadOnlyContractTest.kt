package com.whatto.bcm.infra.client.fireblocks

import com.whatto.bcm.domain.monitoring.NoOpOperationalMetricsPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock

class FireblocksReadOnlyContractTest {
    @Test
    fun `승인된 testnet workspace의 blockchain과 asset 소비 계약을 확인한다`() {
        val blockchainId = requiredEnvironment("BCM_FIREBLOCKS_CONTRACT_BLOCKCHAIN_ID")
        val symbol = System.getenv("BCM_FIREBLOCKS_CONTRACT_ASSET_SYMBOL")?.takeIf(String::isNotBlank)
        val client = client()

        val blockchains = client.blockchains()
        val blockchain = checkNotNull(blockchains.data.singleOrNull { it.id == blockchainId })

        assertThat(blockchain.displayName).isNotBlank()

        val assets = client.assets(blockchainId, symbol)

        assertThat(assets.data).isNotEmpty
        assertThat(assets.data).allSatisfy { asset ->
            assertThat(asset.id).isNotBlank()
            assertThat(asset.blockchainId).isEqualTo(blockchainId)
            assertThat(asset.displaySymbol).isNotBlank()
            asset.decimals?.let { decimals -> assertThat(decimals).isBetween(0, 255) }
        }
    }

    private fun client(): FireblocksClient {
        val apiKey = requiredEnvironment("BCM_FIREBLOCKS_API_KEY")
        val privateKeyFile = Path.of(requiredEnvironment("BCM_FIREBLOCKS_PRIVATE_KEY_FILE"))
        val privateKeyPem = Files.readString(privateKeyFile)
        val baseUrl = requiredEnvironment("BCM_FIREBLOCKS_BASE_URL")
        requireOfficialFireblocksOrigin(baseUrl)
        val properties =
            FireblocksProperties(
                baseUrl = baseUrl,
                apiKey = apiKey,
                privateKeyFile = privateKeyFile.toString(),
            )
        return FireblocksClient(
            restClientBuilder = RestClient.builder(),
            properties = properties,
            signer = FireblocksJwtSigner(apiKey, privateKeyPem, Clock.systemUTC()),
            metrics = NoOpOperationalMetricsPort,
            restClientFactory = PooledFireblocksRestClientFactory(),
        )
    }

    private fun requiredEnvironment(name: String): String =
        checkNotNull(System.getenv(name)?.takeIf(String::isNotBlank)) { "$name is required" }

    private fun requireOfficialFireblocksOrigin(baseUrl: String) {
        val uri = runCatching { URI(baseUrl) }.getOrNull()
        check(
            uri != null &&
                uri.scheme == "https" &&
                uri.host == "api.fireblocks.io" &&
                uri.port == -1 &&
                uri.userInfo == null &&
                (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/") &&
                uri.rawQuery == null &&
                uri.rawFragment == null,
        ) {
            "real Fireblocks contract test requires official Fireblocks API origin https://api.fireblocks.io"
        }
    }
}
