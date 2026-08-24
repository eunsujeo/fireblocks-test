package com.whatto.bcm.testsupport.chain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.web3j.crypto.AuthorizationTuple
import org.web3j.crypto.Credentials
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.TransactionEncoder
import org.web3j.utils.Numeric
import java.math.BigInteger
import java.nio.file.Path

class Eip7702SupportSpikeTest {
    @TempDir
    lateinit var runtimeDirectory: Path

    @Test
    fun `Anvil Prague와 Web3j는 별도 fee payer가 제출한 type 4 위임을 처리한다`() {
        AnvilNode
            .start(
                LocalChainConfiguration(
                    seed = "bcm-eip-7702-support-spike-seed-1",
                    runtimeDirectory = runtimeDirectory,
                    contractArtifactDirectory = runtimeDirectory,
                ),
            ).use { node ->
                val feePayer = node.keyring.accounts[0]
                val vault = node.keyring.accounts[1]
                val delegate = node.keyring.accounts[2]
                val rpc = node.rpc
                rpc.setNativeBalance(vault.address, BigInteger.ZERO)
                val authorization =
                    AuthorizationTuple.from(
                        LOCAL_CHAIN_ID,
                        delegate.address,
                        rpc.transactionCount(vault.address),
                        Credentials.create(vault.privateKey),
                    )
                val transaction =
                    RawTransaction.createTransaction(
                        LOCAL_CHAIN_ID,
                        rpc.transactionCount(feePayer.address),
                        MAX_PRIORITY_FEE_PER_GAS,
                        MAX_FEE_PER_GAS,
                        GAS_LIMIT,
                        vault.address,
                        BigInteger.ZERO,
                        "0x",
                        emptyList(),
                        listOf(authorization),
                    )
                val signed =
                    Numeric.toHexString(
                        TransactionEncoder.signMessage(transaction, Credentials.create(feePayer.privateKey)),
                    )
                val feePayerBalanceBefore = rpc.nativeBalance(feePayer.address)

                assertThat(signed).startsWith("0x04")
                val receipt = rpc.waitForReceipt(rpc.sendRawTransaction(signed))

                assertThat(receipt.successful).isTrue()
                assertThat(rpc.code(vault.address)).isEqualTo("0xef0100${delegate.address.removePrefix("0x")}")
                assertThat(rpc.transactionCount(vault.address)).isEqualTo(BigInteger.ONE)
                assertThat(rpc.nativeBalance(vault.address)).isZero()
                assertThat(rpc.nativeBalance(feePayer.address)).isLessThan(feePayerBalanceBefore)
            }
    }

    companion object {
        private const val LOCAL_CHAIN_ID = 31337L
        private val GAS_LIMIT = BigInteger.valueOf(100_000)
        private val MAX_PRIORITY_FEE_PER_GAS = BigInteger.valueOf(1_000_000_000)
        private val MAX_FEE_PER_GAS = BigInteger.valueOf(2_000_000_000)
    }
}
