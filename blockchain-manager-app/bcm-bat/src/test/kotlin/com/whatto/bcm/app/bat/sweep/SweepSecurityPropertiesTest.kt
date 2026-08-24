package com.whatto.bcm.app.bat.sweep

import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SweepSecurityPropertiesTest {
    @Test
    fun `Callback 검증 플래그가 거짓이면 approve batch 긴급 회수를 모두 차단한다`() {
        val security =
            SweepSecurityProperties(
                normalApprovalEnabled = true,
                emergencyRevocationEnabled = true,
                batchSubmissionEnabled = true,
                tapApprovalPolicyVerified = true,
                tapRevocationPolicyVerified = true,
                tapBatchPolicyVerified = true,
                callbackVerified = false,
                universalGaslessVerified = true,
                sweepContractVerified = true,
                normalApprovalEnabledNetworks = setOf("ETHEREUM"),
                emergencyRevocationEnabledNetworks = setOf("ETHEREUM"),
                batchSubmissionEnabledNetworks = setOf("ETHEREUM"),
            )

        listOf<() -> Unit>(
            { security.requireNormalApprovalReady("ETHEREUM") },
            { security.requireBatchSubmissionReady("ETHEREUM") },
            { security.requireEmergencyRevocationReady("ETHEREUM") },
        ).forEach { operation ->
            assertThatThrownBy(operation)
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Co-signer Callback")
        }
    }

    @Test
    fun `제한 출시는 기능별 allowlist에 등록한 네트워크에서만 열린다`() {
        val security =
            SweepSecurityProperties(
                normalApprovalEnabled = true,
                emergencyRevocationEnabled = true,
                batchSubmissionEnabled = true,
                tapApprovalPolicyVerified = true,
                tapRevocationPolicyVerified = true,
                tapBatchPolicyVerified = true,
                callbackVerified = true,
                universalGaslessVerified = true,
                sweepContractVerified = true,
                normalApprovalEnabledNetworks = setOf("ETHEREUM"),
                emergencyRevocationEnabledNetworks = setOf("ETHEREUM"),
                batchSubmissionEnabledNetworks = setOf("ETHEREUM"),
            )

        security.requireNormalApprovalReady("ETHEREUM")
        security.requireEmergencyRevocationReady("ETHEREUM")
        security.requireBatchSubmissionReady("ETHEREUM")

        assertThatThrownBy { security.requireNormalApprovalReady("BASE") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("BASE")
        assertThatThrownBy { security.requireEmergencyRevocationReady("BASE") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("BASE")
        assertThatThrownBy { security.requireBatchSubmissionReady("BASE") }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("BASE")
    }
}
