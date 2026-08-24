package com.whatto.bcm.app.bat.sweep

import com.whatto.bcm.domain.admin.SweepExecutionPolicy
import com.whatto.bcm.domain.admin.SweepPolicyHardCeiling
import com.whatto.bcm.domain.sweep.ActiveSweepRuntimeContext
import com.whatto.bcm.domain.sweep.SweepRuntimePolicyRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SweepRuntimePolicyGuardTest {
    @Test
    fun `활성 정책과 최신 출시 evidence가 배포 설정에 일치하면 실행 snapshot을 반환한다`() {
        val context = context()
        val guard = guard(FakeRuntimePolicies(context))

        assertThat(guard.requireReady(NETWORK, SYMBOL)).isEqualTo(context)
    }

    @Test
    fun `활성 문맥이 없거나 배포 컨트랙트와 정책이 drift하면 실행을 차단한다`() {
        assertThatThrownBy { guard(FakeRuntimePolicies(null)).requireReady(NETWORK, SYMBOL) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("active Admin sweep context")

        assertThatThrownBy {
            guard(FakeRuntimePolicies(context().copy(contractAddress = "0xother"))).requireReady(NETWORK, SYMBOL)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("contract")

        assertThatThrownBy {
            guard(
                FakeRuntimePolicies(
                    context().copy(policy = context().policy.copy(allowanceCap = BigDecimal("101"))),
                ),
            ).requireReady(NETWORK, SYMBOL)
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("policy")
    }

    @Test
    fun `현재 hard ceiling이 강화되면 과거 승인 정책도 실행 직전에 다시 차단한다`() {
        val ceiling = ceiling().copy(maximumBatchAmount = BigDecimal("49"))

        assertThatThrownBy { guard(FakeRuntimePolicies(context()), ceiling).requireReady(NETWORK, SYMBOL) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("hard ceiling")
    }

    private fun guard(
        repository: SweepRuntimePolicyRepository,
        ceiling: SweepPolicyHardCeiling = ceiling(),
    ) = SweepRuntimePolicyGuard(repository, properties(), ceiling, CLOCK)

    private fun properties() =
        SweepProperties(
            batchSize = 2,
            thresholds = listOf(SweepAssetThreshold(NETWORK, SYMBOL, "10", "100")),
            contracts = listOf(SweepNetworkContract(NETWORK, CONTRACT)),
        )

    private fun context() =
        ActiveSweepRuntimeContext(
            network = NETWORK,
            symbol = SYMBOL,
            policyVersionId = "policy-v1",
            policySnapshotHash = "a".repeat(64),
            policy =
                SweepExecutionPolicy(
                    enabled = true,
                    minimumAmount = BigDecimal("10"),
                    batchSize = 2,
                    allowanceCap = BigDecimal("100"),
                    itemAmountCap = BigDecimal("30"),
                    batchAmountCap = BigDecimal("50"),
                    boostAttempts = 1,
                ),
            contractVersionId = "contract-v1",
            contractEvidenceId = "evidence-v1",
            contractAddress = CONTRACT,
        )

    private fun ceiling() =
        SweepPolicyHardCeiling(
            executionEnabled = true,
            maximumBatchSize = 2,
            maximumAllowance = BigDecimal("100"),
            maximumItemAmount = BigDecimal("30"),
            maximumBatchAmount = BigDecimal("50"),
            maximumBoostAttempts = 1,
        )

    private class FakeRuntimePolicies(
        private val context: ActiveSweepRuntimeContext?,
    ) : SweepRuntimePolicyRepository {
        override fun findActive(
            network: String,
            symbol: String,
            observedAt: Instant,
        ): ActiveSweepRuntimeContext? = context
    }

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-08-19T01:00:00Z"), ZoneOffset.UTC)
        const val NETWORK = "ETHEREUM"
        const val SYMBOL = "USDC"
        const val CONTRACT = "0xsweeper"
    }
}
