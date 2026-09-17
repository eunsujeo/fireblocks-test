package com.whatto.bcm.domain.submission

import com.whatto.bcm.domain.vendor.NetworkTransferObservation

/**
 * 전송 알림의 계열 분류(계약13 "웹훅 전송 사건 관찰"·02 "웹훅 계열 분류 — 제출 원장이 기준이다").
 *
 * 우리가 낸 전송인지, 어느 업무 계열인지는 **제출 원장으로만 가른다** — 알림 종류나 금액·주소 추정으로 가르지 않는다.
 * 원장에 없는 전송은 우리가 만든 게 아니므로 원장·이벤트를 만들지 않고 경보로 넘긴다.
 *
 * 벤더 호출·원장 쓰기는 하지 않는 순수 판단이다.
 */
object NetworkTransferJudgement {
    /**
     * [observation]이 가리키는 전송을 제출 원장에서 찾는다. 두 키를 순서대로 본다.
     *
     * 1. **벤더 전송 ID**(`vndr_tx_id`) — 제출 응답을 받아 기록을 마친 정상 경로다.
     * 2. **제출 키**(`ext_tx_id`) — 02가 규정한 "웹훅이 벤더 응답보다 먼저 올 수 있다"의 경로다.
     *    응답을 못 받아 `vndr_tx_id`가 비어 있어도 알림이 돌려준 `externalId`로 같은 행에 닿는다.
     *
     * 2번에서 찾은 행에 이미 **다른** 벤더 전송 ID가 적혀 있으면 한 제출 키에 전송이 둘 붙은 것이다 —
     * 대조로 풀 문제가 아니므로 [NetworkTransferJudgementResult.Conflicting]으로 올려 사람이 보게 한다.
     */
    fun judge(
        observation: NetworkTransferObservation,
        submissions: SubmissionLookup,
    ): NetworkTransferJudgementResult {
        submissions.byVendorTransactionId(observation.transferId)?.let { record ->
            return NetworkTransferJudgementResult.Ours(record, linked = true)
        }
        val externalId =
            observation.externalId
                ?: return NetworkTransferJudgementResult.Unknown(observation.transferId)
        val record =
            submissions.byExternalTransactionId(externalId)
                ?: return NetworkTransferJudgementResult.Unknown(observation.transferId)
        val recorded = record.vendorTransactionId
        if (recorded != null && recorded != observation.transferId) {
            return NetworkTransferJudgementResult.Conflicting(record, observation.transferId)
        }
        return NetworkTransferJudgementResult.Ours(record, linked = false)
    }

    /** 제출 원장 조회 경계 — 판단이 저장소 구현을 알지 않게 한다. */
    interface SubmissionLookup {
        fun byVendorTransactionId(vendorTransactionId: String): SubmissionRecord?

        fun byExternalTransactionId(externalTransactionId: String): SubmissionRecord?
    }
}

sealed interface NetworkTransferJudgementResult {
    /**
     * 우리가 낸 전송이다. [linked]가 false면 원장의 `vndr_tx_id`가 아직 비어 있어 이 알림이 채워야 한다 —
     * 02의 "웹훅이 원장의 빈 `vndr_tx_id`를 채워 같은 결과에 도달한다".
     */
    data class Ours(
        val submission: SubmissionRecord,
        val linked: Boolean,
    ) : NetworkTransferJudgementResult

    /**
     * 제출 원장에 없다 — 우리가 만든 전송이 아니거나 원장이 유실된 상태다.
     * **원장·이벤트를 만들지 않는다.** 벤더 ID만으로 계정·업무 계열을 지어낼 수 없고, 지어내면 남의 자금이 우리 원장에 들어온다.
     */
    data class Unknown(
        val transferId: String,
    ) : NetworkTransferJudgementResult

    /** 한 제출 키에 다른 전송 ID가 이미 붙어 있다 — 재시도로 풀리지 않으므로 즉시 격리한다(03 `sbmt_stcd` 전이 표). */
    data class Conflicting(
        val submission: SubmissionRecord,
        val observedTransferId: String,
    ) : NetworkTransferJudgementResult
}
