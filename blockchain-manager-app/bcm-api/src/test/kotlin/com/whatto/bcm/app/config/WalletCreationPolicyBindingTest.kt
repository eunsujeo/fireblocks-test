package com.whatto.bcm.app.config

import com.whatto.bcm.domain.account.VendorCallDecision
import com.whatto.bcm.domain.account.WalletProvisioningPolicy
import com.whatto.bcm.domain.vendor.VendorExecutionLimits
import com.whatto.bcm.domain.vendor.WalletCreationPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.time.LocalDateTime

class WalletCreationPolicyBindingTest {
    // WalletProvisioningConfig는 Fireblocks 생성 흐름 전용 조건부 조립이다 — 선택값을 주어 원래 계약(정책 미제공 시 조립 거절)을 그대로 검사한다.
    private val runner =
        ApplicationContextRunner()
            .withPropertyValues("bcm.provider=fireblocks")
            .withUserConfiguration(WalletProvisioningConfig::class.java)

    @Test
    fun `시간 상한만 있으면 생성 정책을 추정하지 않고 조립을 거절한다`() {
        runner
            .withBean(VendorExecutionLimits::class.java, {
                object : VendorExecutionLimits {
                    override val maximumCallMillis = 17_000L
                    override val maximumSubmissionFlowMillis = 53_000L
                }
            })
            .run { context ->
                assertThat(context).hasFailed()
                assertThat(context.startupFailure).hasStackTraceContaining("WalletCreationPolicy")
            }
    }

    @Test
    fun `공통 생성 정책은 선택된 구현에 원본 입력을 전달하고 모든 판정을 보존한다`() {
        val now = LocalDateTime.parse("2026-09-14T00:00:00.500")
        val decisions =
            listOf(
                VendorCallDecision.Reuse("selected-key", "20260913000000"),
                VendorCallDecision.RetryLater(123),
                VendorCallDecision.Rotate,
            )
        decisions.forEach { decision ->
            var calls = 0
            val selected =
                WalletCreationPolicy { key, registeredAt, preparedAt, observedNow ->
                    calls++
                    assertThat(key).isEqualTo("current-key")
                    assertThat(registeredAt).isEqualTo("20260912000000")
                    assertThat(preparedAt).isEqualTo("20260913000000")
                    assertThat(observedNow).isEqualTo(now)
                    decision
                }
            runner.withBean(WalletCreationPolicy::class.java, { selected }).run { context ->
                assertThat(context).hasNotFailed()
                val policy = context.getBean(WalletProvisioningPolicy::class.java)
                assertThat(policy.vendorCallDecision("current-key", "20260912000000", "20260913000000", now))
                    .isSameAs(decision)
                assertThat(calls).isEqualTo(1)
            }
        }
    }
}
