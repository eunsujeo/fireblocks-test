package com.whatto.bcm.testsupport.chain

import java.io.Closeable
import java.net.ServerSocket
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Duration
import java.util.concurrent.TimeUnit

internal class AnvilNode private constructor(
    private val process: Process,
    val rpc: EvmJsonRpcClient,
    val keyring: DeterministicEvmKeyring,
) : Closeable {
    val rpcUrl: String = rpc.rpcUrl

    override fun close() {
        process.destroy()
        if (!process.waitFor(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(SHUTDOWN_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
        }
    }

    companion object {
        private val SHUTDOWN_TIMEOUT = Duration.ofSeconds(3)

        fun start(configuration: LocalChainConfiguration): AnvilNode {
            require(configuration.seed.length >= 32 && configuration.seed.none(Char::isWhitespace)) {
                "local chain seed must be a non-blank runtime value"
            }
            FoundryToolchain(anvilBinary = configuration.anvilBinary).verifyAnvil()
            prepareRuntimeDirectory(configuration.runtimeDirectory)
            val keyring = DeterministicEvmKeyring.fromSeed(configuration.seed, LOCAL_ACCOUNT_COUNT)
            require(configuration.rpcPort == 0 || configuration.rpcPort in 1..65535) { "Anvil RPC port is invalid" }
            val port = configuration.rpcPort.takeUnless { it == 0 } ?: availablePort()
            val logFile = configuration.runtimeDirectory.resolve("anvil.log")
            val process =
                ProcessBuilder(
                    configuration.anvilBinary,
                    "--host",
                    "127.0.0.1",
                    "--port",
                    port.toString(),
                    "--chain-id",
                    LocalChainManifest.LOCAL_CHAIN_ID.toString(),
                    "--accounts",
                    LOCAL_ACCOUNT_COUNT.toString(),
                    "--balance",
                    "10000",
                    "--mnemonic",
                    keyring.mnemonic,
                    "--hardfork",
                    "prague",
                    "--timestamp",
                    "1",
                    "--order",
                    "fifo",
                    "--quiet",
                ).redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .start()
            val rpc = EvmJsonRpcClient("http://127.0.0.1:$port")
            return try {
                rpc.awaitReady()
                check(rpc.chainId() == LocalChainManifest.LOCAL_CHAIN_ID) { "Anvil chain id mismatch" }
                check(rpc.accounts() == keyring.accounts.map(LocalEvmKey::address)) { "derived EVM keyring does not match Anvil" }
                AnvilNode(process, rpc, keyring)
            } catch (exception: Exception) {
                process.destroyForcibly()
                throw IllegalStateException("Anvil failed to start; log=$logFile", exception)
            }
        }

        private fun availablePort(): Int = ServerSocket(0).use { it.localPort }

        private fun prepareRuntimeDirectory(path: Path) {
            Files.createDirectories(path)
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"))
            }
        }

        private const val LOCAL_ACCOUNT_COUNT = 6
    }
}
