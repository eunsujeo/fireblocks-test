package com.whatto.bcm.domain.submission

data class PendingSubmissionCheck(
    val externalTransactionId: String,
    val checkedAt: String,
    val checkCount: Int,
)

interface PendingSubmissionRecoveryRepository {
    /**
     * 오래된 REQUESTED를 점검 대상으로 예약한다. 유효 claim은 제외하며, 마지막 점검 시각과 횟수를
     * 같은 원자 연산으로 갱신해 여러 배치 인스턴스가 같은 건을 동시에 조회하지 않게 한다.
     */
    fun reserveRequestedForRecovery(
        now: String,
        requestedBefore: String,
        checkedBefore: String,
        limit: Int,
    ): List<PendingSubmissionCheck>

    /** 벤더 externalTxId 조회에서 실재가 확인된 거래만 SUBMITTED로 회수한다. */
    fun markRecoveredSubmitted(
        externalTransactionId: String,
        vendorTransactionId: String,
        respondedAt: String,
    )
}
