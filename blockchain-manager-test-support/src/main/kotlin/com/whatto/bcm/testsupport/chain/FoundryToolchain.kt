package com.whatto.bcm.testsupport.chain

import java.time.Duration
import java.util.concurrent.TimeUnit

data class FoundryVersions(
    val forge: String,
    val anvil: String,
)

class FoundryToolchain(
    private val forgeBinary: String = "forge",
    private val anvilBinary: String = "anvil",
) {
    fun verify(): FoundryVersions {
        val versions =
            FoundryVersions(
                forge = version(forgeBinary, "forge"),
                anvil = verifyAnvil(),
            )
        check(versions.forge == REQUIRED_VERSION) {
            "Foundry toolchain version mismatch: required=$REQUIRED_VERSION " +
                "forge=${versions.forge} anvil=${versions.anvil}"
        }
        return versions
    }

    fun verifyAnvil(): String =
        version(anvilBinary, "anvil").also { actual ->
            check(actual == REQUIRED_VERSION) {
                "Anvil version mismatch: required=$REQUIRED_VERSION actual=$actual"
            }
        }

    private fun version(
        binary: String,
        tool: String,
    ): String {
        val process =
            try {
                ProcessBuilder(binary, "--version").redirectErrorStream(true).start()
            } catch (exception: Exception) {
                throw IllegalStateException("$tool executable is not available", exception)
            }
        if (!process.waitFor(COMMAND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException("$tool version command timed out")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.exitValue() == 0) { "$tool version command failed" }
        return checkNotNull(VERSION_PATTERN.find(output)?.groupValues?.get(1)) {
            "$tool version output is not recognized"
        }
    }

    companion object {
        const val REQUIRED_VERSION = "1.7.1"
        private val COMMAND_TIMEOUT = Duration.ofSeconds(5)
        private val VERSION_PATTERN = Regex("Version: ([0-9]+\\.[0-9]+\\.[0-9]+)")
    }
}
