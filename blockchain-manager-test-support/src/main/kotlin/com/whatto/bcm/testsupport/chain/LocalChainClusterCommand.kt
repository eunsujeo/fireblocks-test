package com.whatto.bcm.testsupport.chain

import tools.jackson.databind.ObjectMapper
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

data class LocalChainClusterManifest(
    val schemaVersion: Int = 1,
    val chains: List<LocalChainClusterEntry>,
) {
    fun toJson(): String = ObjectMapper().writeValueAsString(this)
}

data class LocalChainClusterEntry(
    val blockchainId: String,
    val networkCode: String,
    val displayName: String,
    val chainId: Long,
    val rpcUrl: String,
    val manifestFile: String,
    val keyFile: String,
    val assets: List<LocalChainAssetManifest>,
)

class LocalChainClusterEnvironment internal constructor(
    private val environments: List<LocalChainEnvironment>,
    val manifest: LocalChainClusterManifest,
) : Closeable {
    override fun close() = environments.asReversed().forEach(LocalChainEnvironment::close)
}

internal object LocalChainClusterCommand {
    fun requested(args: Array<String>): Boolean = args.contentEquals(arrayOf(COMMAND))

    fun start(environment: Map<String, String> = System.getenv()): LocalChainClusterEnvironment {
        val runtimeDirectory = Path.of(environment.value(RUNTIME_DIRECTORY, "build/local/cluster"))
        val seed = LocalChainCommand.loadOrCreateSeed(Path.of(environment.value(SEED_FILE, "build/local/cluster.seed")))
        val artifactDirectory = Path.of(environment.value(CONTRACT_ARTIFACT_DIRECTORY, "build/contracts"))
        val anvilBinary = environment.value(ANVIL_BINARY, "anvil")
        val ethereum =
            LocalChainEnvironment.start(
                LocalChainConfiguration(
                    seed = seed,
                    runtimeDirectory = runtimeDirectory.resolve("ethereum"),
                    contractArtifactDirectory = artifactDirectory,
                    anvilBinary = anvilBinary,
                    rpcPort = environment.port(ETHEREUM_ANVIL_PORT, DEFAULT_ETHEREUM_PORT),
                    chainId = ETHEREUM_CHAIN_ID,
                    blockchainId = "ethereum-local",
                    networkCode = "ETHEREUM",
                    displayName = "Ethereum Local",
                    tokenDefinitions = catalogTokens("ETH"),
                ),
            )
        val base =
            try {
                LocalChainEnvironment.start(
                    LocalChainConfiguration(
                        seed = seed,
                        runtimeDirectory = runtimeDirectory.resolve("base"),
                        contractArtifactDirectory = artifactDirectory,
                        anvilBinary = anvilBinary,
                        rpcPort = environment.port(BASE_ANVIL_PORT, DEFAULT_BASE_PORT),
                        chainId = BASE_CHAIN_ID,
                        blockchainId = "base-local",
                        networkCode = "BASE",
                        displayName = "Base Local",
                        tokenDefinitions = catalogTokens("BASE"),
                    ),
                )
            } catch (exception: Exception) {
                ethereum.close()
                throw exception
            }
        val environments = listOf(ethereum, base)
        val manifest =
            LocalChainClusterManifest(
                chains =
                    environments.map { chain ->
                        val chainDirectory = runtimeDirectory.resolve(chain.manifest.networkCode.lowercase())
                        LocalChainClusterEntry(
                            blockchainId = chain.manifest.blockchainId,
                            networkCode = chain.manifest.networkCode,
                            displayName = chain.manifest.displayName,
                            chainId = chain.manifest.chainId,
                            rpcUrl = chain.rpcUrl,
                            manifestFile =
                                chainDirectory
                                    .resolve("manifest.json")
                                    .toAbsolutePath()
                                    .normalize()
                                    .toString(),
                            keyFile =
                                chainDirectory
                                    .resolve("evm-keys.json")
                                    .toAbsolutePath()
                                    .normalize()
                                    .toString(),
                            assets = chain.manifest.assets,
                        )
                    },
            )
        Files.createDirectories(runtimeDirectory)
        Files.writeString(runtimeDirectory.resolve("manifest.json"), manifest.toJson())
        return LocalChainClusterEnvironment(environments, manifest)
    }

    fun run(environment: Map<String, String> = System.getenv()) {
        val cluster = start(environment)
        val stopped = CountDownLatch(1)
        val closed = AtomicBoolean()
        val closeCluster = {
            if (closed.compareAndSet(false, true)) cluster.close()
            stopped.countDown()
        }
        val shutdownHook = Thread(closeCluster, "bcm-local-chain-cluster-shutdown")
        Runtime.getRuntime().addShutdownHook(shutdownHook)
        try {
            stopped.await()
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook)
            } catch (exception: IllegalStateException) {
                if (!closed.get()) throw exception
            }
            closeCluster()
        }
    }

    private fun catalogTokens(networkSuffix: String): List<LocalTokenDefinition> =
        listOf(
            LocalTokenDefinition("USDC_${networkSuffix}_LOCAL", "USDC", "Local USD Coin", "CatalogToken", "TestUSDC"),
            LocalTokenDefinition("KRWK_${networkSuffix}_LOCAL", "KRWK", "Local Korean Won Token", "CatalogToken", "TestKRWK"),
        )

    private fun Map<String, String>.port(
        key: String,
        defaultValue: Int,
    ): Int {
        val value = value(key, defaultValue.toString()).toIntOrNull()
        require(value != null && value in 1..65535) { "$key must be a valid TCP port" }
        return value
    }

    private fun Map<String, String>.value(
        key: String,
        defaultValue: String,
    ): String = get(key)?.takeIf(String::isNotBlank) ?: defaultValue

    private const val COMMAND = "chain-cluster"
    private const val RUNTIME_DIRECTORY = "BCM_LOCAL_CHAIN_RUNTIME_DIR"
    private const val SEED_FILE = "BCM_LOCAL_CHAIN_SEED_FILE"
    private const val CONTRACT_ARTIFACT_DIRECTORY = "BCM_LOCAL_CONTRACT_ARTIFACT_DIR"
    private const val ANVIL_BINARY = "BCM_LOCAL_ANVIL_BINARY"
    private const val ETHEREUM_ANVIL_PORT = "BCM_LOCAL_ETHEREUM_ANVIL_PORT"
    private const val BASE_ANVIL_PORT = "BCM_LOCAL_BASE_ANVIL_PORT"
    private const val DEFAULT_ETHEREUM_PORT = 38545
    private const val DEFAULT_BASE_PORT = 38546
    private const val ETHEREUM_CHAIN_ID = 31337L
    private const val BASE_CHAIN_ID = 31338L
}
