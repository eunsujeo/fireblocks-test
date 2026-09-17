package com.whatto.bcm.domain.vendor

import com.whatto.bcm.domain.submission.SubmissionRecord
import com.whatto.bcm.domain.tx.TxRecord

/**
 * 발신(`direction: Out`) 온체인 이동 사건을 **기존 거래에 붙이는** 판정(계약13 "논리 거래 식별자").
 *
 * 발신 사건은 **새 거래를 만들지 않는다.** 우리가 낸 전송은 이미 전송 알림이 `bcm_tx_l`에 만들어 뒀고,
 * 이 사건은 그 거래에 **블록 좌표를 주는 관찰**일 뿐이다. 새로 만들면 같은 자금이 논리 거래 둘이 된다.
 *
 * `tx_hash`는 유일하지 않으므로 **후보가 정확히 하나이고 제출 원장에 대응할 때만** 붙인다 —
 * 02의 "제출 원장이 기준"을 hash 추정으로 바꾸지 않는다. 여럿이거나 대응이 없으면 붙이지 않고 중단해 운영 신호로 남긴다.
 *
 * 벤더 호출·원장 쓰기는 하지 않는 순수 판단이다.
 */
object NetworkChainOutgoingAttachment {
    fun attach(
        candidates: List<TxRecord>,
        submissions: SubmissionLookup,
    ): NetworkChainAttachmentResult {
        if (candidates.isEmpty()) return NetworkChainAttachmentResult.NoCandidate
        // 여럿이면 어느 것이 이 이동인지 hash만으로 가릴 수 없다. 하나를 고르는 규칙을 지어내면 다른 거래에 남의 확정이 붙는다.
        if (candidates.size > 1) return NetworkChainAttachmentResult.Ambiguous(candidates.size)

        val candidate = candidates.single()
        val submission =
            submissions.byVendorTransactionId(candidate.vendorTxId)
                ?: return NetworkChainAttachmentResult.NoSubmission(candidate)
        return NetworkChainAttachmentResult.Attach(candidate, submission)
    }

    /** 제출 원장 조회 경계 — 판단이 저장소 구현을 알지 않게 한다. */
    fun interface SubmissionLookup {
        fun byVendorTransactionId(vendorTransactionId: String): SubmissionRecord?
    }
}

sealed interface NetworkChainAttachmentResult {
    /** 붙일 거래를 찾았고 제출 원장에도 대응한다 — 이 거래의 전이로 반영한다. */
    data class Attach(
        val record: TxRecord,
        val submission: SubmissionRecord,
    ) : NetworkChainAttachmentResult

    /**
     * `(ntwk_cd, tx_hash)`에 해당하는 거래가 없다. 전송 알림이 아직 안 왔거나(도착 순서) 우리가 낸 전송이 아니다.
     * **거래를 만들지 않는다** — 만들면 알림이 나중에 와서 같은 자금의 거래가 둘이 된다.
     */
    data object NoCandidate : NetworkChainAttachmentResult

    /** 같은 `(ntwk_cd, tx_hash)`에 거래가 여럿이다 — 하나를 고르는 규칙을 지어내지 않고 중단한다. */
    data class Ambiguous(
        val candidateCount: Int,
    ) : NetworkChainAttachmentResult

    /**
     * 거래는 하나 찾았는데 제출 원장에 대응이 없다. 우리 지갑에서 나간 이동인데 우리가 낸 제출이 아니라는 뜻이라
     * 확정을 붙이지 않고 중단한다 — 02의 "제출 원장이 기준"을 hash 일치로 대체하지 않는다.
     */
    data class NoSubmission(
        val record: TxRecord,
    ) : NetworkChainAttachmentResult
}
