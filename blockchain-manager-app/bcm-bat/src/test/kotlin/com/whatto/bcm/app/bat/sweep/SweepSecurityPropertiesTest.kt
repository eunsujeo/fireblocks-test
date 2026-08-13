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
            )

        listOf<() -> Unit>(
            security::requireNormalApprovalReady,
            security::requireBatchSubmissionReady,
            security::requireEmergencyRevocationReady,
        ).forEach { operation ->
            assertThatThrownBy(operation)
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Co-signer Callback")
        }
    }
}
