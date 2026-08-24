package com.whatto.bcm.app.application.admin

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class BandSConfigTest {
    private val config = BandSConfig()
    private val contextRunner = ApplicationContextRunner().withUserConfiguration(BandSConfig::class.java)

    @Test
    fun `제거된 egress 설정은 조용히 무시하지 않고 시작을 거절한다`() {
        assertThatThrownBy {
            config.bandSExecutionBoundary(
                BandSBoundaryProperties(treasuryEgressVaults = mapOf("BASE" to "legacy-egress")),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("treasury-egress-vaults")
    }

    @Test
    fun `legacy egress property가 실제 binding되어 application context 시작을 막는다`() {
        contextRunner
            .withPropertyValues("bcm.admin-band-s.treasury-egress-vaults.BASE=legacy-egress")
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasRootCauseMessage(
                    "bcm.admin-band-s.treasury-egress-vaults was removed; configure omnibus-vaults instead",
                )
            }
    }

    @Test
    fun `일부 registry만 있거나 omnibus가 출금 풀에 포함된 설정을 거절한다`() {
        assertThatThrownBy {
            config.bandSExecutionBoundary(
                BandSBoundaryProperties(omnibusVaults = mapOf("BASE" to "omnibus-base")),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatThrownBy {
            config.bandSExecutionBoundary(
                BandSBoundaryProperties(
                    omnibusVaults = mapOf("BASE" to "omnibus-base"),
                    withdrawalPoolVaults = mapOf("BASE" to setOf("omnibus-base")),
                    fixedColdAddresses = mapOf("BASE:USDC" to "cold-base-usdc"),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `완전한 registry는 네트워크 자산별 실행 경계로 변환한다`() {
        val boundary =
            config.bandSExecutionBoundary(
                BandSBoundaryProperties(
                    omnibusVaults = mapOf("BASE" to "omnibus-base"),
                    withdrawalPoolVaults = mapOf("BASE" to setOf("withdrawal-pool-base")),
                    fixedColdAddresses = mapOf("BASE:USDC" to "cold-base-usdc"),
                ),
            )

        assertThat(boundary.omnibusVaults).containsEntry("BASE", "omnibus-base")
        assertThat(boundary.withdrawalPoolVaults).containsEntry("BASE", setOf("withdrawal-pool-base"))
        assertThat(boundary.fixedColdAddresses.values).containsExactly("cold-base-usdc")
    }
}
