package com.whatto.bcm.domain.submission

/** 벤더 접수 뒤 claim 소유권을 잃어 원장에 결과를 기록하지 못한 자금 사고 후보 신호. */
fun interface SubmissionConflictAlertPort {
    fun alert(alert: SubmissionConflictAlert)
}

data class SubmissionConflictAlert(
    val externalTransactionId: String,
    val observedVendorTransactionId: String,
    val recordedVendorTransactionId: String?,
    val recordedStatus: SubmissionStatus,
)
