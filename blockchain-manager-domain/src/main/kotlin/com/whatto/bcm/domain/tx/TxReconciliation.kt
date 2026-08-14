package com.whatto.bcm.domain.tx

import com.whatto.bcm.domain.submission.SubmissionTransactionType

data class TxReconciliationRecord(
    val record: TxRecord,
    val submissionType: SubmissionTransactionType?,
    val sweepExecutionId: String?,
)

interface TxReconciliationRepository {
    fun findByPhysicalVendorTransactionId(vendorTransactionId: String): TxReconciliationRecord?

    fun findCreatedBetween(
        createdAtOrAfter: String,
        createdAtOrBefore: String,
    ): List<TxReconciliationRecord>

    fun markExpiredPendingStopped(
        detectedAtOrBefore: String,
        stoppedAt: String,
    ): Int

    /** 후보를 잠그고 확인 시각·횟수를 먼저 기록한 실행자만 반환한다. 백오프 단계는 저장소 계약에 고정한다. */
    fun claimPendingForReconciliation(
        changedAtOrBefore: String,
        checkedAt: String,
        limit: Int,
    ): List<TxReconciliationRecord>
}

data class TxReconciliationSnapshot(
    val rootVendorTransactionId: String,
    val status: TxStatus,
)

enum class TxReconciliationMismatchType {
    VENDOR_ONLY,
    MANAGER_ONLY,
    STATUS_MISMATCH,
}

data class TxReconciliationMismatch(
    val rootVendorTransactionId: String,
    val type: TxReconciliationMismatchType,
    val vendorStatus: TxStatus?,
    val managerStatus: TxStatus?,
)

data class TxReconciliationResult(
    val matchedCount: Int,
    val mismatches: List<TxReconciliationMismatch>,
)

data class TxReconciliationReport(
    val from: String,
    val to: String,
    val result: TxReconciliationResult,
    val recoveredCount: Int,
    val stoppedTrackingCount: Int,
)

fun interface TxReconciliationReportPort {
    fun report(report: TxReconciliationReport)
}

object TxReconciliationPolicy {
    fun compare(
        vendor: List<TxReconciliationSnapshot>,
        manager: List<TxReconciliationSnapshot>,
    ): TxReconciliationResult {
        val vendorByRoot = vendor.uniqueByRoot("vendor")
        val managerByRoot = manager.uniqueByRoot("manager")
        var matchedCount = 0
        val mismatches = mutableListOf<TxReconciliationMismatch>()

        (vendorByRoot.keys + managerByRoot.keys).sorted().forEach { rootVendorTransactionId ->
            val vendorStatus = vendorByRoot[rootVendorTransactionId]
            val managerStatus = managerByRoot[rootVendorTransactionId]
            when {
                vendorStatus == null ->
                    mismatches +=
                        TxReconciliationMismatch(
                            rootVendorTransactionId,
                            TxReconciliationMismatchType.MANAGER_ONLY,
                            null,
                            managerStatus,
                        )

                managerStatus == null ->
                    mismatches +=
                        TxReconciliationMismatch(
                            rootVendorTransactionId,
                            TxReconciliationMismatchType.VENDOR_ONLY,
                            vendorStatus,
                            null,
                        )

                vendorStatus != managerStatus ->
                    mismatches +=
                        TxReconciliationMismatch(
                            rootVendorTransactionId,
                            TxReconciliationMismatchType.STATUS_MISMATCH,
                            vendorStatus,
                            managerStatus,
                        )

                else -> matchedCount += 1
            }
        }
        return TxReconciliationResult(matchedCount, mismatches)
    }

    private fun List<TxReconciliationSnapshot>.uniqueByRoot(side: String): Map<String, TxStatus> {
        val result = mutableMapOf<String, TxStatus>()
        forEach { snapshot ->
            val previous = result.putIfAbsent(snapshot.rootVendorTransactionId, snapshot.status)
            require(previous == null || previous == snapshot.status) {
                "conflicting $side reconciliation status: rootVendorTransactionId=${snapshot.rootVendorTransactionId}"
            }
        }
        return result
    }
}
