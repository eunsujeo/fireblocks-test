package com.whatto.bcm.testsupport.chain

import org.web3j.crypto.Bip32ECKeyPair
import org.web3j.crypto.Credentials
import org.web3j.crypto.MnemonicUtils
import org.web3j.utils.Numeric
import tools.jackson.databind.ObjectMapper
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

internal data class LocalEvmKey(
    val address: String,
    val privateKey: String,
)

internal class DeterministicEvmKeyring private constructor(
    val mnemonic: String,
    val accounts: List<LocalEvmKey>,
) {
    fun write(path: Path) {
        val document =
            mapOf(
                "schemaVersion" to 1,
                "accounts" to accounts,
            )
        Files.writeString(path, ObjectMapper().writeValueAsString(document))
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"))
        }
    }

    companion object {
        fun fromSeed(
            seed: String,
            accountCount: Int,
        ): DeterministicEvmKeyring {
            require(accountCount > 0) { "local EVM account count must be positive" }
            val entropy = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray())
            val mnemonic = MnemonicUtils.generateMnemonic(entropy)
            val master = Bip32ECKeyPair.generateKeyPair(MnemonicUtils.generateSeed(mnemonic, ""))
            val accounts =
                (0 until accountCount).map { index ->
                    val keyPair =
                        Bip32ECKeyPair.deriveKeyPair(
                            master,
                            intArrayOf(
                                44 or Bip32ECKeyPair.HARDENED_BIT,
                                60 or Bip32ECKeyPair.HARDENED_BIT,
                                0 or Bip32ECKeyPair.HARDENED_BIT,
                                0,
                                index,
                            ),
                        )
                    val credentials = Credentials.create(keyPair)
                    LocalEvmKey(
                        address = credentials.address.lowercase(),
                        privateKey = Numeric.toHexStringNoPrefixZeroPadded(keyPair.privateKey, 64),
                    )
                }
            return DeterministicEvmKeyring(mnemonic, accounts)
        }
    }
}
