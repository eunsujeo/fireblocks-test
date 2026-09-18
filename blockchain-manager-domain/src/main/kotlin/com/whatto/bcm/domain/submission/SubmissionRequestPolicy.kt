package com.whatto.bcm.domain.submission

import com.whatto.bcm.domain.exception.InvalidRequestException

/**
 * 제출 요청 자체의 규칙 — **제공자와 무관하게 같다**(02 "신규 키 선행 검사", 2026-09-18 사용자 확정).
 * 같은 요청이 제공자에 따라 다르게 답하면 안 되므로 판단을 한 곳에 둔다.
 *
 * 호출 시점은 **새 키일 때**다. 기존 키의 처리는 02 멱등 표가 먼저이고, 그 판정을 이 검사가 가려서는 안 된다 —
 * 같은 키·다른 내용의 `409`가 `400`으로 바뀌거나 이미 `SUBMITTED`인 건의 응답이 막힌다.
 * 기존 `REQUESTED`의 회수도 막지 않는다(이미 나갔는지 모르는 구간이다). 다만 기존 `FAILED`의 재시도는 막는다 —
 * 회수는 벌어진 일의 불확실성 해소이고 재시도는 **새 자금 이동**이다.
 */
object SubmissionRequestPolicy {
    /**
     * 송신 계정과 목적지 계정이 같으면 거절한다. 같은 주소로 가는 온체인 전송이라 잔액은 그대로고 가스만 태운다
     * (Fireblocks vault↔vault도 온체인이다 — 02). 시간이 지나도 해소되지 않는 **요청값 자체의 모순**이라 보류가 아니라 거절이다.
     */
    fun requireDistinctAccounts(
        senderAccountId: String,
        recipientType: SubmissionRecipientType,
        recipientValue: String,
    ) {
        if (isSelfTransfer(senderAccountId, recipientType, recipientValue)) throw InvalidRequestException("recipient")
    }

    /**
     * 같은 계정으로 보내는 요청인가. **판정만** 한다 —
     * 기존 행이 있을 때의 예외(회수는 허용, 재시도는 금지)는 원장을 아는 호출자가 정한다.
     * 호출자가 이 값을 먼저 보면 **자기 전송이 아닌 요청은 원장을 읽지 않는다** — 거의 모든 요청이 그렇다.
     */
    fun isSelfTransfer(
        senderAccountId: String,
        recipientType: SubmissionRecipientType,
        recipientValue: String,
    ): Boolean = recipientType == SubmissionRecipientType.ACCOUNT && recipientValue == senderAccountId
}
