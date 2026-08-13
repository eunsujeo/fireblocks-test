package com.whatto.bcm.domain.tx

import com.whatto.bcm.domain.submission.SubmissionTransactionType

data class StallCandidate(
    val record: TxRecord,
    val submissionType: SubmissionTransactionType?,
)

interface StallCandidateRepository {
    /** DB 공통 상태·마지막 변경 시각으로 고른 1차 후보. 실제 조치는 벤더 최신 관찰 뒤에만 판단한다. */
    fun findStallCandidates(
        changedBefore: String,
        limit: Int,
    ): List<StallCandidate>

    /** 같은 root의 경보는 한 번만 발송하도록 NULL일 때만 시각을 기록한다. */
    fun markStallAlertedIfAbsent(
        candidate: TxRecord,
        alertedAt: String,
    ): Boolean
}

data class StallAlert(
    val rootVendorTransactionId: String,
    val activeVendorTransactionId: String,
    val reason: StallAlertReason,
    val observedTransactionHash: String?,
)

fun interface StallAlertPort {
    fun alert(alert: StallAlert)
}
