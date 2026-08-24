package com.whatto.bcm.testsupport.chain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

class LocalChainClusterCommandIntegrationTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `cluster 명령은 ETHEREUM과 BASE에 USDC와 KRWK를 6자리로 준비한다`() {
        val ethereumPort = availablePort()
        val basePort = availablePort()

        LocalChainClusterCommand.start(commandEnvironment(ethereumPort, basePort)).use { cluster ->
            assertThat(cluster.manifest.chains.map { it.networkCode }).containsExactly("ETHEREUM", "BASE")
            assertThat(cluster.manifest.chains.map { it.chainId }).containsExactly(31337L, 31338L)
            cluster.manifest.chains.forEach { chain ->
                assertThat(chain.assets.map { it.symbol }).containsExactly("USDC", "KRWK")
                assertThat(chain.assets.map { it.decimals }).containsOnly(6)
                assertThat(chain.assets.map { it.contractAddress }).allMatch { it.startsWith("0x") && it.length == 42 }
            }
        }

        assertThat(temporaryDirectory.resolve("cluster/manifest.json")).isRegularFile()
        assertThat(temporaryDirectory.resolve("cluster/ethereum/evm-keys.json")).isRegularFile()
        assertThat(temporaryDirectory.resolve("cluster/base/evm-keys.json")).isRegularFile()
        assertThat(
            Files.readString(temporaryDirectory.resolve("cluster/manifest.json")),
        ).doesNotContain(
            Files.readString(temporaryDirectory.resolve("seed")),
        )
    }

    @Test
    fun `chain-cluster 인자 하나만 독립 클러스터 프로세스 모드로 판정한다`() {
        assertThat(LocalChainClusterCommand.requested(arrayOf("chain-cluster"))).isTrue()
        assertThat(LocalChainClusterCommand.requested(emptyArray())).isFalse()
        assertThat(LocalChainClusterCommand.requested(arrayOf("chain-cluster", "unexpected"))).isFalse()
    }

    private fun commandEnvironment(
        ethereumPort: Int,
        basePort: Int,
    ): Map<String, String> =
        mapOf(
            "BCM_LOCAL_CHAIN_SEED_FILE" to temporaryDirectory.resolve("seed").toString(),
            "BCM_LOCAL_CHAIN_RUNTIME_DIR" to temporaryDirectory.resolve("cluster").toString(),
            "BCM_LOCAL_CONTRACT_ARTIFACT_DIR" to checkNotNull(System.getProperty(CONTRACT_ARTIFACT_PROPERTY)),
            "BCM_LOCAL_ANVIL_BINARY" to "anvil",
            "BCM_LOCAL_ETHEREUM_ANVIL_PORT" to ethereumPort.toString(),
            "BCM_LOCAL_BASE_ANVIL_PORT" to basePort.toString(),
        )

    private fun availablePort(): Int = ServerSocket(0).use { it.localPort }

    companion object {
        private const val CONTRACT_ARTIFACT_PROPERTY = "bcm.contract-artifacts"
    }
}
