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

    /**
     * **같은 canonical(지갑·자산 키·목적지·최소 단위 금액)이면서 온체인 hash가 아직 기록되지 않은** 미결 제출이
     * [excludingExternalTransactionId] 말고 또 있는가.
     *
     * 발신 이동을 기존 거래에 붙일 때 쓴다(계약13 "발신 이동 대조"). 벤더는 이동과 제출을 잇는 키를 주지 않으므로,
     * 같은 값의 제출이 아직 hash를 받지 못한 채 남아 있으면 **이 이동이 그 제출의 것일 가능성을 배제할 수 없다** —
     * 배제하지 못한 채 붙이면 다른 논리 출금에 남의 확정이 붙는다.
     */
    fun existsUnresolvedWithSameCanonical(
        excludingExternalTransactionId: String,
        canonical: SubmissionVendorCanonical,
        recipientValue: String,
    ): Boolean
}
