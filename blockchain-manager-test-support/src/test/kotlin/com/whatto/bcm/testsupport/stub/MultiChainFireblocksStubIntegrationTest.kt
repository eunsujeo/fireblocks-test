package com.whatto.bcm.testsupport.stub

import com.whatto.bcm.domain.monitoring.NoOpOperationalMetricsPort
import com.whatto.bcm.infra.client.fireblocks.FireblocksClient
import com.whatto.bcm.infra.client.fireblocks.FireblocksJwtSigner
import com.whatto.bcm.infra.client.fireblocks.FireblocksProperties
import com.whatto.bcm.infra.client.fireblocks.PooledFireblocksRestClientFactory
import com.whatto.bcm.testsupport.TestSupportApplication
import com.whatto.bcm.testsupport.chain.LocalChainClusterCommand
import com.whatto.bcm.testsupport.chain.LocalChainClusterEnvironment
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import java.net.ServerSocket
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.time.Clock
import java.util.Base64

@SpringBootTest(
    classes = [TestSupportApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "management.server.port=0",
        "bcm.test-support.vendor-mode=STUB",
        "bcm.test-support.chain-mode=LOCAL",
        "bcm.test-support.server-address=127.0.0.1",
        "bcm.test-support.management-address=127.0.0.1",
        "bcm.test-support.fireblocks-base-url=http://127.0.0.1:18080",
        "bcm.test-support.fireblocks-api-key=bcm-local-stub",
        "bcm.test-support.webhook-jwks-url=http://127.0.0.1:18080/.well-known/jwks.json",
        "bcm.test-support.evm-chain-id=31337",
        "bcm.test-support.reset-enabled=true",
    ],
)
class MultiChainFireblocksStubIntegrationTest {
    @LocalServerPort
    var serverPort: Int = 0

    @Test
    fun `Stub 카탈로그와 Vault 잔액은 ETHEREUM과 BASE의 네 자산을 각각 사용한다`() {
        val client = client()

        assertThat(client.blockchains().data.map { it.id }).containsExactly("ethereum-local", "base-local")
        assertThat(client.assets("ethereum-local").data.map { it.id })
            .containsExactly("USDC_ETH_LOCAL", "KRWK_ETH_LOCAL")
        assertThat(client.assets("base-local").data.map { it.id })
            .containsExactly("USDC_BASE_LOCAL", "KRWK_BASE_LOCAL")
        val vault = client.createVault("CUSTOMER:MULTI", "multi-chain-vault")
        listOf("USDC_ETH_LOCAL", "KRWK_ETH_LOCAL", "USDC_BASE_LOCAL", "KRWK_BASE_LOCAL").forEach { assetId ->
            client.createDepositAddress(vault.vaultId, assetId, "wallet-$assetId")
            assertThat(client.balanceOf(vault.vaultId, assetId).available).isEqualTo("100")
        }
    }

    private fun client(): FireblocksClient {
        val privateKeyPem = testPrivateKeyPem()
        val properties = FireblocksProperties("http://127.0.0.1:$serverPort", "bcm-local-stub", privateKeyPem)
        return FireblocksClient(
            RestClient.builder(),
            properties,
            FireblocksJwtSigner(properties.apiKey, privateKeyPem, Clock.systemUTC()),
            NoOpOperationalMetricsPort,
            PooledFireblocksRestClientFactory(),
        )
    }

    private fun testPrivateKeyPem(): String {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val body = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(keyPair.private.encoded)
        return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----"
    }

    companion object {
        private val runtimeDirectory = Files.createTempDirectory("bcm-multi-chain-stub-")
        private val cluster: LocalChainClusterEnvironment =
            LocalChainClusterCommand.start(
                mapOf(
                    "BCM_LOCAL_CHAIN_SEED_FILE" to runtimeDirectory.resolve("seed").toString(),
                    "BCM_LOCAL_CHAIN_RUNTIME_DIR" to runtimeDirectory.resolve("cluster").toString(),
                    "BCM_LOCAL_CONTRACT_ARTIFACT_DIR" to checkNotNull(System.getProperty("bcm.contract-artifacts")),
                    "BCM_LOCAL_ANVIL_BINARY" to "anvil",
                    "BCM_LOCAL_ETHEREUM_ANVIL_PORT" to availablePort().toString(),
                    "BCM_LOCAL_BASE_ANVIL_PORT" to availablePort().toString(),
                ),
            )

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            val ethereum = cluster.manifest.chains.single { it.networkCode == "ETHEREUM" }
            registry.add("bcm.test-support.evm-rpc-url") { ethereum.rpcUrl }
            registry.add("bcm.test-support.local-chain-manifest-file") { ethereum.manifestFile }
            registry.add("bcm.test-support.local-chain-key-file") { ethereum.keyFile }
            registry.add("bcm.test-support.local-chain-cluster-manifest-file") {
                runtimeDirectory.resolve("cluster/manifest.json").toString()
            }
        }

        @JvmStatic
        @AfterAll
        fun stopCluster() {
            cluster.close()
            runtimeDirectory.toFile().deleteRecursively()
        }

        private fun availablePort(): Int = ServerSocket(0).use { it.localPort }
    }
}
