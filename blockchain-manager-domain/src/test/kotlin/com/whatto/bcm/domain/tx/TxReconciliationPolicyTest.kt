package com.whatto.bcm.domain.tx

import com.whatto.bcm.domain.submission.SubmissionTransactionType
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

    @Test
    fun `매니저 종결 스냅샷은 FINALIZED FAILED와 제출 거래의 REJECTED만 포함한다`() {
        val finalized = reconciliation(TxStatus.FINALIZED, null)
        val failed = reconciliation(TxStatus.FAILED, null)
        val outboundRejected = reconciliation(TxStatus.REJECTED, SubmissionTransactionType.WITHDRAWAL)
        val inboundRejected = reconciliation(TxStatus.REJECTED, null)
        val pending = reconciliation(TxStatus.CONFIRMED, SubmissionTransactionType.WITHDRAWAL)

        assertThat(TxReconciliationPolicy.managerSnapshot(finalized)?.status).isEqualTo(TxStatus.FINALIZED)
        assertThat(TxReconciliationPolicy.managerSnapshot(failed)?.status).isEqualTo(TxStatus.FAILED)
        assertThat(TxReconciliationPolicy.managerSnapshot(outboundRejected)?.status).isEqualTo(TxStatus.REJECTED)
        assertThat(TxReconciliationPolicy.managerSnapshot(inboundRejected)).isNull()
        assertThat(TxReconciliationPolicy.managerSnapshot(pending)).isNull()
    }

    @Test
    fun `복구는 매니저가 미결인 상태 불일치만 대상으로 한다`() {
        val recoverable = mismatch(TxReconciliationMismatchType.STATUS_MISMATCH, TxStatus.CONFIRMED)
        val terminalMismatch = mismatch(TxReconciliationMismatchType.STATUS_MISMATCH, TxStatus.FINALIZED)
        val vendorOnly = mismatch(TxReconciliationMismatchType.VENDOR_ONLY, null)

        assertThat(TxReconciliationPolicy.shouldRecover(recoverable)).isTrue()
        assertThat(TxReconciliationPolicy.shouldRecover(terminalMismatch)).isFalse()
        assertThat(TxReconciliationPolicy.shouldRecover(vendorOnly)).isFalse()
    }

    @Test
    fun `RBF 대표 관찰은 성공 증거를 우선하고 증거가 같으면 active 거래를 고른다`() {
        val previous = evidence("tx-old", "tx-active", succeeded = false)
        val successful = evidence("tx-success", "tx-active", succeeded = true)
        val active = evidence("tx-active", "tx-active", succeeded = false)
        val anotherInactive = evidence("tx-other", "tx-active", succeeded = false)

        assertThat(TxReconciliationPolicy.isPreferredObservation(successful, previous)).isTrue()
        assertThat(TxReconciliationPolicy.isPreferredObservation(active, previous)).isTrue()
        assertThat(TxReconciliationPolicy.isPreferredObservation(anotherInactive, previous)).isFalse()
    }

    private fun snapshot(
        rootVendorTransactionId: String,
        status: TxStatus,
    ) = TxReconciliationSnapshot(rootVendorTransactionId, status)

    private fun reconciliation(
        status: TxStatus,
        submissionType: SubmissionTransactionType?,
    ) = TxReconciliationRecord(
        record =
            TxRecord(
                vendorTxId = "tx-root",
                accountId = "account-1",
                network = "ETHEREUM",
                symbol = "USDC",
                lastPublishedStatus = status,
                confirmationCount = 0,
                // 관찰을 이미 통과한 행이다 — 첫 관찰 전 행만 vendorCreatedAt 을 비운다(03 V32).
                vendorCreatedAt = "20260807110000",
                firstDetectedAt = "20260807110000",
                lastChangedAt = "20260807110000",
            ),
        submissionType = submissionType,
        sweepExecutionId = null,
    )

    private fun mismatch(
        type: TxReconciliationMismatchType,
        managerStatus: TxStatus?,
    ) = TxReconciliationMismatch("tx-root", type, TxStatus.FINALIZED, managerStatus)

    private fun evidence(
        physicalVendorTransactionId: String,
        activeVendorTransactionId: String,
        succeeded: Boolean,
    ) = TxReconciliationObservationEvidence(
        physicalVendorTransactionId,
        activeVendorTransactionId,
        succeeded,
    )
}
