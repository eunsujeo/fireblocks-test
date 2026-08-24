package com.whatto.bcm.testsupport.chain

import com.whatto.bcm.infra.client.evm.EvmErc20Client
import com.whatto.bcm.infra.client.evm.EvmRpcNetworkProperties
import com.whatto.bcm.infra.client.evm.EvmRpcProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.web.client.RestClient
import java.math.BigInteger
import java.nio.file.Path
import java.security.SecureRandom

class DeterministicLocalChainIntegrationTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `같은 런타임 seed는 같은 계정과 컨트랙트를 만들고 reset은 기준 snapshot을 복원한다`() {
        val seed = runtimeSeed()
        val firstManifest =
            environment(seed, "first").use { chain ->
                val customer = chain.manifest.customerAddresses.first()
                val baselineNonce = chain.transactionCount(customer)

                chain.approve(customer, TOKEN_AMOUNT)
                assertThat(chain.transactionCount(customer)).isEqualTo(baselineNonce + BigInteger.ONE)

                chain.reset()
                assertThat(chain.transactionCount(customer)).isEqualTo(baselineNonce)
                assertThat(chain.tokenAllowance(customer)).isEqualTo(BigInteger.ZERO)
                assertThat(chain.manifest.toJson()).doesNotContain(seed)
                chain.manifest
            }

        val secondManifest = environment(seed, "second").use { it.manifest }

        assertThat(secondManifest).isEqualTo(firstManifest)
    }

    @Test
    fun `approve 뒤 batchSweep은 성공과 실패 항목을 실제 receipt SweepLeg로 남긴다`() {
        environment(runtimeSeed(), "partial").use { chain ->
            val owners = chain.manifest.customerAddresses.sorted()
            val successfulOwner = owners.first()
            val failedOwner = owners.last()
            chain.approve(successfulOwner, TOKEN_AMOUNT)
            chain.approve(failedOwner, INSUFFICIENT_ALLOWANCE)

            val transactionHash =
                chain.batchSweep(
                    EXECUTION_ID,
                    listOf(
                        LocalSweepItem(successfulOwner, TOKEN_AMOUNT),
                        LocalSweepItem(failedOwner, TOKEN_AMOUNT),
                    ),
                )
            chain.waitForReceipt(transactionHash)

            val receipt =
                checkNotNull(evmClient(chain.rpcUrl).receipt("LOCAL", transactionHash, chain.manifest.sweepContractAddress, TOKEN_DECIMALS))

            assertThat(receipt.successful).isTrue()
            assertThat(receipt.legs).hasSize(2)
            assertThat(receipt.legs.single { it.ownerAddress == successfulOwner }.successful).isTrue()
            assertThat(receipt.legs.single { it.ownerAddress == successfulOwner }.actualAmount).isEqualTo("20")
            assertThat(receipt.legs.single { it.ownerAddress == failedOwner }.successful).isFalse()
            assertThat(receipt.legs.single { it.ownerAddress == failedOwner }.actualAmount).isEqualTo("0")
            assertThat(receipt.legs.single { it.ownerAddress == failedOwner }.failureCode).isNotEqualTo(ZERO_FAILURE_CODE)
            assertThat(chain.tokenBalance(chain.manifest.omnibusAddress)).isEqualTo(TOKEN_AMOUNT)

            assertThatThrownBy {
                chain.batchSweep(EXECUTION_ID, listOf(LocalSweepItem(successfulOwner, TOKEN_AMOUNT)))
            }.isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("EVM RPC")
        }
    }

    private fun environment(
        seed: String,
        directory: String,
    ): LocalChainEnvironment =
        LocalChainEnvironment.start(
            LocalChainConfiguration(
                seed = seed,
                runtimeDirectory = temporaryDirectory.resolve(directory),
                contractArtifactDirectory = Path.of(checkNotNull(System.getProperty(CONTRACT_ARTIFACT_PROPERTY))),
            ),
        )

    private fun evmClient(rpcUrl: String): EvmErc20Client =
        EvmErc20Client(
            RestClient.builder(),
            EvmRpcProperties(mapOf("LOCAL" to EvmRpcNetworkProperties(rpcUrl))),
        )

    private fun runtimeSeed(): String = ByteArray(32).also(SecureRandom()::nextBytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val CONTRACT_ARTIFACT_PROPERTY = "bcm.contract-artifacts"
        private const val TOKEN_DECIMALS = 6
        private val TOKEN_AMOUNT = BigInteger("20000000")
        private val INSUFFICIENT_ALLOWANCE = BigInteger("10000000")
        private const val EXECUTION_ID = "0198c7d5-7a30-7000-8000-000000000001"
        private val ZERO_FAILURE_CODE = "0".repeat(64)
    }
}
