package com.whatto.bcm.domain.tx

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TxReconciliationPolicyTest {
    @Test
    fun `일치와 벤더 only 매니저 only 상태 불일치를 구분한다`() {
        val vendor =
            listOf(
                snapshot("tx-match", TxStatus.FINALIZED),
                snapshot("tx-vendor-only", TxStatus.FAILED),
                snapshot("tx-status", TxStatus.FINALIZED),
            )
        val manager =
            listOf(
                snapshot("tx-match", TxStatus.FINALIZED),
                snapshot("tx-manager-only", TxStatus.FAILED),
                snapshot("tx-status", TxStatus.CONFIRMED),
            )

        val result = TxReconciliationPolicy.compare(vendor, manager)

        assertThat(result.matchedCount).isEqualTo(1)
        assertThat(result.mismatches)
            .containsExactly(
                TxReconciliationMismatch(
                    rootVendorTransactionId = "tx-manager-only",
                    type = TxReconciliationMismatchType.MANAGER_ONLY,
                    vendorStatus = null,
                    managerStatus = TxStatus.FAILED,
                ),
                TxReconciliationMismatch(
                    rootVendorTransactionId = "tx-status",
                    type = TxReconciliationMismatchType.STATUS_MISMATCH,
                    vendorStatus = TxStatus.FINALIZED,
                    managerStatus = TxStatus.CONFIRMED,
                ),
                TxReconciliationMismatch(
                    rootVendorTransactionId = "tx-vendor-only",
                    type = TxReconciliationMismatchType.VENDOR_ONLY,
                    vendorStatus = TxStatus.FAILED,
                    managerStatus = null,
                ),
            )
    }

    @Test
    fun `같은 root가 서로 다른 상태로 두 번 정규화되면 비교를 거절한다`() {
        val vendor =
            listOf(
                snapshot("tx-root", TxStatus.FAILED),
                snapshot("tx-root", TxStatus.FINALIZED),
            )

        org.assertj.core.api.Assertions
            .assertThatThrownBy { TxReconciliationPolicy.compare(vendor, emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("tx-root")
    }

    private fun snapshot(
        rootVendorTransactionId: String,
        status: TxStatus,
    ) = TxReconciliationSnapshot(rootVendorTransactionId, status)
}
