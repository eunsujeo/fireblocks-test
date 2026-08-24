package com.whatto.bcm.testsupport.chain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.ServerSocket
import java.nio.file.Files
import java.nio.file.Path

class LocalChainCommandIntegrationTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `chain 명령은 런타임 seed를 만들고 고정 포트에 같은 manifest를 재현한다`() {
        val port = availablePort()
        val environment = commandEnvironment(port)

        val firstManifest =
            LocalChainCommand.start(environment).use { chain ->
                assertThat(chain.rpcUrl).isEqualTo("http://127.0.0.1:$port")
                assertThat(chain.manifest.chainId).isEqualTo(31337)
                chain.manifest
            }
        val seedFile = temporaryDirectory.resolve("seed")
        val seed = Files.readString(seedFile)

        assertThat(seed).matches("[0-9a-f]{64}")
        assertThat(Files.readString(temporaryDirectory.resolve("chain/manifest.json"))).doesNotContain(seed)
        assertThat(temporaryDirectory.resolve("chain/evm-keys.json")).isRegularFile()

        val secondManifest = LocalChainCommand.start(environment).use { it.manifest }

        assertThat(secondManifest).isEqualTo(firstManifest)
        assertThat(Files.readString(seedFile)).isEqualTo(seed)
    }

    @Test
    fun `chain 인자 하나만 독립 체인 프로세스 모드로 판정한다`() {
        assertThat(LocalChainCommand.requested(arrayOf("chain"))).isTrue()
        assertThat(LocalChainCommand.requested(emptyArray())).isFalse()
        assertThat(LocalChainCommand.requested(arrayOf("chain", "unexpected"))).isFalse()
    }

    private fun commandEnvironment(port: Int): Map<String, String> =
        mapOf(
            "BCM_LOCAL_CHAIN_SEED_FILE" to temporaryDirectory.resolve("seed").toString(),
            "BCM_LOCAL_CHAIN_RUNTIME_DIR" to temporaryDirectory.resolve("chain").toString(),
            "BCM_LOCAL_CONTRACT_ARTIFACT_DIR" to checkNotNull(System.getProperty(CONTRACT_ARTIFACT_PROPERTY)),
            "BCM_LOCAL_ANVIL_BINARY" to "anvil",
            "BCM_LOCAL_ANVIL_PORT" to port.toString(),
        )

    private fun availablePort(): Int = ServerSocket(0).use { it.localPort }

    companion object {
        private const val CONTRACT_ARTIFACT_PROPERTY = "bcm.contract-artifacts"
    }
}
