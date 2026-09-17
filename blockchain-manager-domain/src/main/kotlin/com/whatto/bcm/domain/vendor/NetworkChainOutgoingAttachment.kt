package com.whatto.bcm.domain.vendor

import com.whatto.bcm.domain.submission.SubmissionRecord
import com.whatto.bcm.domain.submission.SubmissionVendorCanonical
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
 * 3. 관찰이 **그 제출의 canonical 값과 일치**(지갑·자산 키·최소 단위 금액·목적지, 03 V28) —
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
        // 값이 같다고 이 이동이 **이 제출의 것**임이 증명되지는 않는다 — 같은 값의 제출이 아직 hash를 못 받은 채 남아 있으면
        // 그쪽의 이동일 수도 있다. 벤더는 이동과 제출을 잇는 키를 주지 않으므로 배제할 수 없으면 붙이지 않는다.
        val canonical = requireNotNull(submission.vendorCanonical) { "matched submission must carry canonical values" }
        if (submissions.hasUnresolvedWithSameCanonical(submission.externalTransactionId, canonical, submission.recipientValue)) {
            return NetworkChainAttachmentResult.Unresolved(candidate, submission)
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
        val destination = observation.toAddress ?: return false
        return observation.vendorWalletId == canonical.vendorWalletId &&
            // 자산 키까지 봐야 한다 — 같은 지갑·목적지로 **최소 단위 금액이 같은 다른 자산** 이동이 한 트랜잭션에 있을 수 있다.
            observation.vendorAssetId == canonical.vendorAssetId &&
            observation.amountBaseUnits == canonical.amountBaseUnits &&
            sameAddress(destination, submission.recipientValue)
    }

    /**
     * 주소 동일성. **기본은 대소문자 구분**이다 — Solana base58 주소는 대소문자가 값의 일부라 무시하면 서로 다른 주소가 같아진다.
     * EVM 16진 주소일 때만 대소문자를 무시한다(checksum 표기는 같은 주소의 다른 표기다).
     */
    private fun sameAddress(
        observed: String,
        recorded: String,
    ): Boolean =
        if (EVM_ADDRESS.matches(observed) && EVM_ADDRESS.matches(recorded)) {
            observed.equals(recorded, ignoreCase = true)
        } else {
            observed == recorded
        }

    private val EVM_ADDRESS = Regex("0x[0-9a-fA-F]{40}")

    /** 제출 원장 조회 경계 — 판단이 저장소 구현을 알지 않게 한다. */
    interface SubmissionLookup {
        fun byVendorTransactionId(vendorTransactionId: String): SubmissionRecord?

        /** 같은 canonical이면서 온체인 hash가 아직 기록되지 않은 미결 제출이 (자기 자신 말고) 또 있는가. */
        fun hasUnresolvedWithSameCanonical(
            excludingExternalTransactionId: String,
            canonical: SubmissionVendorCanonical,
            recipientValue: String,
        ): Boolean
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
     * 값은 맞지만 **이 제출의 것이라고 증명하지 못한다** — 같은 canonical의 제출이 아직 hash를 받지 못한 채 남아 있다.
     * 그쪽의 전송 알림이 오면 해소되므로(그 hash가 같으면 후보가 둘이 되어 [Ambiguous], 다르면 이 모호함이 사라진다)
     * 격리가 아니라 **보류·재시도**다.
     */
    data class Unresolved(
        val record: TxRecord,
        val submission: SubmissionRecord,
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
