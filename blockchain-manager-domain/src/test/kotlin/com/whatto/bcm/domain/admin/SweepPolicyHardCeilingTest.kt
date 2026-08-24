package com.whatto.bcm.domain.admin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class SweepPolicyHardCeilingTest {
    private val ceiling =
        SweepPolicyHardCeiling(
            executionEnabled = true,
            maximumBatchSize = 30,
            maximumAllowance = BigDecimal("1000"),
            maximumItemAmount = BigDecimal("500"),
            maximumBatchAmount = BigDecimal("5000"),
            maximumBoostAttempts = 2,
        )

    @Test
    fun `배포 hard ceiling 안의 운영 정책만 통과한다`() {
        val policy =
            SweepExecutionPolicy(
                enabled = true,
                minimumAmount = BigDecimal("100"),
                batchSize = 20,
                allowanceCap = BigDecimal("900"),
                itemAmountCap = BigDecimal("400"),
                batchAmountCap = BigDecimal("4000"),
                boostAttempts = 1,
            )

        assertThat(SweepPolicyCeilingEvaluator.evaluate(policy, ceiling).passed).isTrue()
    }

    @Test
    fun `batch allowance 금액 boost 중 하나라도 배포 상한을 넘으면 fail closed한다`() {
        val policy =
            SweepExecutionPolicy(
                enabled = true,
                minimumAmount = BigDecimal("100"),
                batchSize = 31,
                allowanceCap = BigDecimal("1001"),
                itemAmountCap = BigDecimal("501"),
                batchAmountCap = BigDecimal("5001"),
                boostAttempts = 3,
            )

        val result = SweepPolicyCeilingEvaluator.evaluate(policy, ceiling)

        assertThat(result.passed).isFalse()
        assertThat(result.violations).containsExactlyInAnyOrder(
            "BATCH_SIZE",
            "ALLOWANCE_CAP",
            "ITEM_AMOUNT_CAP",
            "BATCH_AMOUNT_CAP",
            "BOOST_ATTEMPTS",
        )
    }

    @Test
    fun `배포 kill switch가 꺼져 있으면 Admin 정책이 실행을 켤 수 없다`() {
        val enabledPolicy =
            SweepExecutionPolicy(
                true,
                BigDecimal.ONE,
                1,
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                0,
            )

        assertThat(
            SweepPolicyCeilingEvaluator
                .evaluate(
                    enabledPolicy,
                    ceiling.copy(executionEnabled = false),
                ).violations,
        ).containsExactly("EXECUTION_DISABLED")
    }
}
