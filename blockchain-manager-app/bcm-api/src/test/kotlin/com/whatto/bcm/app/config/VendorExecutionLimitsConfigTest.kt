package com.whatto.bcm.app.config

import com.whatto.bcm.app.application.admin.VaultReconciliationProperties
import com.whatto.bcm.app.application.submission.TransactionSubmissionProperties
import com.whatto.bcm.domain.account.VendorCallDecision
import com.whatto.bcm.domain.vendor.VendorExecutionLimits
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.LocalDateTime

class VendorExecutionLimitsConfigTest {
    private val limits =
        object : VendorExecutionLimits {
            override val maximumCallMillis = 17_000L
            override val maximumSubmissionFlowMillis = 53_000L
        }

    @Test
    fun `벤더 설정 클래스 없이 제출 전체 시간의 경계를 검사한다`() {
        assertThatThrownBy {
            TransactionSubmissionSafetyConfig(TransactionSubmissionProperties(claimTtlSeconds = 53), limits)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatCode {
            TransactionSubmissionSafetyConfig(TransactionSubmissionProperties(claimTtlSeconds = 54), limits)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `전체 지갑 대사는 선택 벤더의 단일 호출 상한을 사용한다`() {
        assertThatThrownBy {
            VaultReconciliationSafetyConfig(VaultReconciliationProperties(claimTtlSeconds = 17), limits)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatCode {
            VaultReconciliationSafetyConfig(VaultReconciliationProperties(claimTtlSeconds = 18), limits)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `지갑 생성은 선택 벤더 호출이 멱등 창 안에 끝날 때만 키를 재사용한다`() {
        val policy = WalletProvisioningConfig().walletProvisioningPolicy(limits)
        val expiresAt = LocalDateTime.of(2026, 9, 15, 0, 0)

        assertThat(policy.vendorCallDecision("key", "20260914000000", null, expiresAt.minusSeconds(18)))
            .isInstanceOf(VendorCallDecision.Reuse::class.java)
        assertThat(policy.vendorCallDecision("key", "20260914000000", null, expiresAt.minusSeconds(17)))
            .isEqualTo(VendorCallDecision.Rotate)
    }
}
