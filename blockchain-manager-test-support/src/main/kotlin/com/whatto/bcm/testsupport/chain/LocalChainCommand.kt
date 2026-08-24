package com.whatto.bcm.testsupport.chain

import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

internal object LocalChainCommand {
    fun requested(args: Array<String>): Boolean = args.contentEquals(arrayOf(COMMAND))

    fun start(environment: Map<String, String> = System.getenv()): LocalChainEnvironment =
        LocalChainEnvironment.start(configuration(environment))

    fun run(environment: Map<String, String> = System.getenv()) {
        val chain = start(environment)
        val stopped = CountDownLatch(1)
        val closed = AtomicBoolean()
        val closeChain = {
            if (closed.compareAndSet(false, true)) chain.close()
            stopped.countDown()
        }
        val shutdownHook = Thread(closeChain, "bcm-local-chain-shutdown")
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
            closeChain()
        }
    }

    private fun configuration(environment: Map<String, String>): LocalChainConfiguration {
        val runtimeDirectory = Path.of(environment.value(RUNTIME_DIRECTORY, "build/local/chain"))
        val seedFile = Path.of(environment.value(SEED_FILE, "build/local/chain.seed"))
        val port = environment.value(ANVIL_PORT, DEFAULT_PORT.toString()).toIntOrNull()
        require(port != null && port in 1..65535) { "$ANVIL_PORT must be a valid TCP port" }
        return LocalChainConfiguration(
            seed = loadOrCreateSeed(seedFile),
            runtimeDirectory = runtimeDirectory,
            contractArtifactDirectory = Path.of(environment.value(CONTRACT_ARTIFACT_DIRECTORY, "build/contracts")),
            anvilBinary = environment.value(ANVIL_BINARY, "anvil"),
            rpcPort = port,
        )
    }

    private fun loadOrCreateSeed(path: Path): String {
        path.parent?.let(Files::createDirectories)
        if (!Files.exists(path)) {
            val generated = ByteArray(SEED_BYTES).also(SecureRandom()::nextBytes).toHex()
            try {
                Files.writeString(path, generated, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
                restrictOwnerAccess(path)
            } catch (exception: FileAlreadyExistsException) {
                if (!Files.isRegularFile(path)) throw exception
            }
        }
        restrictOwnerAccess(path)
        return Files.readString(path).trim().also { seed ->
            require(SEED_PATTERN.matches(seed)) { "$SEED_FILE must contain exactly 64 lowercase hex characters" }
        }
    }

    private fun restrictOwnerAccess(path: Path) {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        }
    }

    private fun Map<String, String>.value(
        key: String,
        defaultValue: String,
    ): String = get(key)?.takeIf(String::isNotBlank) ?: defaultValue

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private const val COMMAND = "chain"
    private const val RUNTIME_DIRECTORY = "BCM_LOCAL_CHAIN_RUNTIME_DIR"
    private const val SEED_FILE = "BCM_LOCAL_CHAIN_SEED_FILE"
    private const val CONTRACT_ARTIFACT_DIRECTORY = "BCM_LOCAL_CONTRACT_ARTIFACT_DIR"
    private const val ANVIL_BINARY = "BCM_LOCAL_ANVIL_BINARY"
    private const val ANVIL_PORT = "BCM_LOCAL_ANVIL_PORT"
    private const val DEFAULT_PORT = 8545
    private const val SEED_BYTES = 32
    private val SEED_PATTERN = Regex("[0-9a-f]{64}")
}
