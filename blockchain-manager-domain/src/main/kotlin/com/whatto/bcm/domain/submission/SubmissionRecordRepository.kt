package com.whatto.bcm.domain.submission

interface SubmissionRecordRepository {
    fun insert(record: SubmissionRecord): SubmissionRecord

    /**
     * 소유권을 잡는다. `REQUESTED`는 물론 **`FAILED`도 되살려 `REQUESTED`로 되돌린다**(02 멱등 표의 Fireblocks 규칙).
     * Dfns처럼 그 재제출을 열지 않는 제공자는 [tryClaimRequested]를 쓴다.
     */
    fun tryClaim(
        externalTransactionId: String,
        claimId: String,
        claimExpiresAt: String,
        now: String,
    ): SubmissionRecord?

    /**
     * **`REQUESTED` 행만** 소유권을 잡는다. 종결된 `FAILED`를 되살리지 않는다.
     *
     * Dfns는 `FAILED` 재시도를 열지 않는데(체인 제출 여부를 확정하지 못해 이중 지급이 될 수 있다),
     * 상태를 읽고 소유권을 잡는 사이에 다른 요청이 그 행을 `FAILED`로 바꿀 수 있다.
     * 그때 공용 [tryClaim]을 쓰면 금지한 `FAILED → REQUESTED`가 다시 열린다 — 판정과 전이가 같은 조건으로 원자적이어야 한다.
     */
    fun tryClaimRequested(
        externalTransactionId: String,
        claimId: String,
        claimExpiresAt: String,
        now: String,
    ): SubmissionRecord?

    fun markSubmitted(
        externalTransactionId: String,
        vendorTransactionId: String,
        respondedAt: String,
    ): SubmissionRecord

    fun markSubmittedByClaim(
        externalTransactionId: String,
        claimId: String,
        vendorTransactionId: String,
        respondedAt: String,
    ): SubmissionRecord

    fun markFailedByClaim(
        externalTransactionId: String,
        claimId: String,
        respondedAt: String,
    ): SubmissionRecord

    fun findByExternalTransactionId(externalTransactionId: String): SubmissionRecord?

    fun findByVendorTransactionId(vendorTransactionId: String): SubmissionRecord?
}
