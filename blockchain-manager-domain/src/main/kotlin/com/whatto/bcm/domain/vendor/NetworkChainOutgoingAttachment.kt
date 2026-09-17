package com.whatto.bcm.domain.vendor

import com.whatto.bcm.domain.submission.SubmissionRecord
import com.whatto.bcm.domain.tx.TxRecord

/**
 * 발신(`direction: Out`) 온체인 이동 사건을 **기존 거래에 붙이는** 판정(계약13 "발신 이동 대조").
 *
 * 발신 사건은 **새 거래를 만들지 않는다.** 우리가 낸 전송은 이미 전송 알림이 `bcm_tx_l`에 만들어 뒀고,
 * 이 사건은 그 거래에 **블록 좌표를 주는 관찰**일 뿐이다. 새로 만들면 같은 자금이 논리 거래 둘이 된다.
 *
 * 붙이려면 세 가지가 모두 성립해야 한다.
 * 1. `(ntwk_cd, tx_hash)` 후보가 **정확히 하나** — hash는 유일하지 않다(한 트랜잭션에 여러 이동).
 * 2. 그 거래가 **제출 원장에 대응** — 02의 "제출 원장이 기준"을 hash 일치로 바꾸지 않는다.
 * 3. 관찰이 **그 제출의 canonical 값과 일치**(지갑·목적지·최소 단위 금액, 03 V28) —
 *    후보 수가 1인 것만으로는 이 이동이 그 제출의 것임을 증명하지 못한다. 한 트랜잭션에 우리 이동 A·B가 있고
 *    A의 전송 알림만 먼저 와 있으면 B의 사건이 A에 붙어 **다른 거래에 남의 확정이 붙는다**.
 *
 * 벤더 호출·원장 쓰기는 하지 않는 순수 판단이다.
 */
object NetworkChainOutgoingAttachment {
    fun attach(
        observation: NetworkChainTransfer,
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
        if (!matches(observation, submission)) {
            return NetworkChainAttachmentResult.Mismatched(candidate, submission)
        }
        return NetworkChainAttachmentResult.Attach(candidate, submission)
    }

    /**
     * 관찰이 그 제출의 것인지 본다. 대조는 **제출 시점에 저장한 canonical 값**(03 V28)으로만 한다 —
     * 현재 자산 매핑을 다시 읽으면 그 사이 교체된 값으로 판정이 달라진다.
     *
     * canonical이 없는 행(V28 이전·다른 제공자)은 **대응을 증명할 수 없으므로 붙이지 않는다**.
     * 증명하지 못한 채 붙이는 것이 곧 다른 거래에 남의 확정을 붙이는 일이다.
     */
    private fun matches(
        observation: NetworkChainTransfer,
        submission: SubmissionRecord,
    ): Boolean {
        val canonical = submission.vendorCanonical ?: return false
        return observation.vendorWalletId == canonical.vendorWalletId &&
            observation.amountBaseUnits == canonical.amountBaseUnits &&
            observation.toAddress?.equals(submission.recipientValue, ignoreCase = true) == true
    }

    /** 제출 원장 조회 경계 — 판단이 저장소 구현을 알지 않게 한다. */
    fun interface SubmissionLookup {
        fun byVendorTransactionId(vendorTransactionId: String): SubmissionRecord?
    }
}

sealed interface NetworkChainAttachmentResult {
    /** 붙일 거래를 찾았고 제출 원장·관찰이 모두 대응한다 — 이 거래의 전이로 반영한다. */
    data class Attach(
        val record: TxRecord,
        val submission: SubmissionRecord,
    ) : NetworkChainAttachmentResult

    /**
     * `(ntwk_cd, tx_hash)`에 해당하는 거래가 없다. **아직 못 붙이는 것이지 잘못된 것이 아니다** —
     * 전송 알림이 늦게 올 수 있다(도착 순서는 수용 항목). 거래를 만들지 않고 **재처리 가능한 상태로 남긴다**.
     */
    data object NoCandidate : NetworkChainAttachmentResult

    /** 같은 `(ntwk_cd, tx_hash)`에 거래가 여럿이다 — 하나를 고르는 규칙을 지어내지 않고 중단한다. */
    data class Ambiguous(
        val candidateCount: Int,
    ) : NetworkChainAttachmentResult

    /**
     * 거래는 하나 찾았는데 제출 원장에 대응이 없다. 우리 지갑에서 나간 이동인데 우리가 낸 제출이 아니라는 뜻이라
     * 확정을 붙이지 않고 중단한다 — 시간이 지나도 해소되지 않는 이상 신호다.
     */
    data class NoSubmission(
        val record: TxRecord,
    ) : NetworkChainAttachmentResult

    /**
     * 후보는 하나이고 제출 원장도 있는데 **관찰이 그 제출의 값과 다르다**. 한 트랜잭션의 다른 이동이거나
     * 저장된 canonical이 없어 증명할 수 없는 경우다 — 붙이면 다른 거래에 남의 확정이 붙는다.
     */
    data class Mismatched(
        val record: TxRecord,
        val submission: SubmissionRecord,
    ) : NetworkChainAttachmentResult
}
