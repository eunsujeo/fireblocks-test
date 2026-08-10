package com.whatto.bcm.domain.tx

/**
 * 허용 전이 표(docs/design/02-bcm-flow.md)의 판정 결과.
 * 판정 기준은 알림 도착 순서가 아니라 직전 상태와의 전이가 표에 있는지다.
 */
sealed interface TransitionDecision {
    /** 발행·반영 */
    data object Publish : TransitionDecision

    /** 감지 이벤트를 합성해 먼저 발행한 뒤 새 상태를 발행 — 순서 계약(감지 → 확정|무효)을 매니저가 지킨다 */
    data object SynthesizeDetectionThenPublish : TransitionDecision

    /** 기록만 남기고 발행하지 않는다 — 반영할 자금이 없다 ((없음) → FAILED) */
    data object RecordOnly : TransitionDecision

    /** 무시 — 늦게 온 옛 알림 · 종결 후 알림 · 표에 없는 전이 */
    data object Ignore : TransitionDecision

    /** 전이 판정이 요구하는 발행 상태들. 합성 감지는 항상 실제 수신 상태보다 먼저 놓인다. */
    fun publishedStatuses(incoming: TxStatus): List<TxStatus> =
        when (this) {
            Publish -> listOf(incoming)
            SynthesizeDetectionThenPublish -> listOf(TxStatus.CONFIRMED, incoming)
            RecordOnly, Ignore -> emptyList()
        }

    /** Ignore만 기존 판정 기준을 유지하고, 나머지는 관찰한 상태를 기록한다. */
    fun statusToRecord(
        previous: TxStatus?,
        incoming: TxStatus,
    ): TxStatus = if (this == Ignore) previous ?: incoming else incoming
}
