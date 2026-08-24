package com.whatto.bcm.testsupport.chain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.math.BigInteger
import java.nio.file.Path
import java.security.SecureRandom

class LocalGaslessExecutionIntegrationTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `native 잔액이 없는 vault는 별도 fee payer로 gasless approve를 실행한다`() {
        environment().use { chain ->
            val vault = chain.manifest.customerAddresses.first()
            val feePayer = chain.manifest.gaslessFeePayerAddress
            chain.setNativeBalance(vault, BigInteger.ZERO)
            val feePayerBalanceBefore = chain.nativeBalance(feePayer)

            chain.gaslessApprove(vault, TOKEN_AMOUNT)

            assertThat(chain.tokenAllowance(vault)).isEqualTo(TOKEN_AMOUNT)
            assertThat(chain.nativeBalance(vault)).isZero()
            assertThat(chain.nativeBalance(feePayer)).isLessThan(feePayerBalanceBefore)
            assertThat(chain.delegatedCode(vault))
                .isEqualTo("0xef0100${chain.manifest.gaslessDelegationContractAddress.removePrefix("0x")}")
        }
    }

    @Test
    fun `fee payer 잔액 부족은 위임과 allowance를 남기지 않는다`() {
        environment().use { chain ->
            val vault = chain.manifest.customerAddresses.first()
            chain.setNativeBalance(vault, BigInteger.ZERO)
            chain.setNativeBalance(chain.manifest.gaslessFeePayerAddress, BigInteger.ZERO)

            assertThatThrownBy { chain.gaslessApprove(vault, TOKEN_AMOUNT) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("EVM RPC")
            assertThat(chain.delegatedCode(vault)).isEqualTo("0x")
            assertThat(chain.tokenAllowance(vault)).isZero()
        }
    }

    @Test
    fun `만료와 nonce 재사용 및 내부 revert는 intent nonce와 allowance를 바꾸지 않는다`() {
        environment().use { chain ->
            val vault = chain.manifest.customerAddresses.first()
            chain.setNativeBalance(vault, BigInteger.ZERO)
            chain.gaslessApprove(vault, TOKEN_AMOUNT)

            val expired = chain.gaslessApproveAttempt(vault, BigInteger.ZERO, BigInteger.ONE, BigInteger.ZERO)
            val replayed = chain.gaslessApproveAttempt(vault, BigInteger.ZERO, BigInteger.ZERO, MAX_DEADLINE)
            val reverted = chain.gaslessContractCallAttempt(vault, chain.manifest.tokenContractAddress, "0xdeadbeef", BigInteger.ONE)

            assertThat(expired.successful).isFalse()
            assertThat(replayed.successful).isFalse()
            assertThat(reverted.successful).isFalse()
            assertThat(chain.gaslessIntentNonce(vault)).isEqualTo(BigInteger.ONE)
            assertThat(chain.tokenAllowance(vault)).isEqualTo(TOKEN_AMOUNT)

            val revoked = chain.gaslessApproveAttempt(vault, BigInteger.ZERO, BigInteger.ONE, MAX_DEADLINE)
            assertThat(revoked.successful).isTrue()
            assertThat(chain.gaslessIntentNonce(vault)).isEqualTo(BigInteger.TWO)
            assertThat(chain.tokenAllowance(vault)).isZero()
        }
    }

    private fun environment(): LocalChainEnvironment =
        LocalChainEnvironment.start(
            LocalChainConfiguration(
                seed = ByteArray(32).also(SecureRandom()::nextBytes).toHex(),
                runtimeDirectory = temporaryDirectory,
                contractArtifactDirectory = Path.of(checkNotNull(System.getProperty(CONTRACT_ARTIFACT_PROPERTY))),
            ),
        )

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private const val CONTRACT_ARTIFACT_PROPERTY = "bcm.contract-artifacts"
        private val TOKEN_AMOUNT = BigInteger("20000000")
        private val MAX_DEADLINE = BigInteger.valueOf(Long.MAX_VALUE)
    }
}
