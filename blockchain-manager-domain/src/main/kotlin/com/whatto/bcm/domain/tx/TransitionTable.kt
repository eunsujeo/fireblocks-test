package com.whatto.bcm.domain.tx

import com.whatto.bcm.domain.tx.TransitionDecision.Ignore
import com.whatto.bcm.domain.tx.TransitionDecision.Publish
import com.whatto.bcm.domain.tx.TransitionDecision.RecordOnly
import com.whatto.bcm.domain.tx.TransitionDecision.SynthesizeDetectionThenPublish

/**
 * 허용 전이 표 — 매니저·DAW-CORE 공용 계약 (docs/design/02-bcm-flow.md "허용 전이 표" 절 그대로).
 * 표에 없는 전이는 무시한다. else 없는 exhaustive when — 상태가 추가되면 컴파일 에러로 누락을 잡는다.
 */
object TransitionTable {
    /**
     * @param previous 직전 상태 — 기록이 없으면 null ((없음) 행)
     * @param incoming 새 상태
     */
    fun decide(
        previous: TxStatus?,
        incoming: TxStatus,
    ): TransitionDecision =
        when (previous) {
            null -> {
                when (incoming) {
                    TxStatus.SUBMITTED, TxStatus.CONFIRMED -> Publish
                    TxStatus.FINALIZED, TxStatus.REJECTED -> SynthesizeDetectionThenPublish
                    TxStatus.FAILED -> RecordOnly
                }
            }

            TxStatus.SUBMITTED -> {
                when (incoming) {
                    TxStatus.CONFIRMED, TxStatus.FINALIZED, TxStatus.REJECTED, TxStatus.FAILED -> Publish
                    TxStatus.SUBMITTED -> Ignore
                }
            }

            TxStatus.CONFIRMED -> {
                when (incoming) {
                    TxStatus.FINALIZED, TxStatus.REJECTED, TxStatus.FAILED -> Publish
                    TxStatus.SUBMITTED, TxStatus.CONFIRMED -> Ignore
                }
            }

            // reorg 무효화(FAILED)와 확정 후 동결(REJECTED)을 반영 — FINALIZED 도 되돌아갈 수 있다
            TxStatus.FINALIZED -> {
                when (incoming) {
                    TxStatus.REJECTED, TxStatus.FAILED -> Publish
                    TxStatus.SUBMITTED, TxStatus.CONFIRMED, TxStatus.FINALIZED -> Ignore
                }
            }

            // 종결 아님 — 동결 해제(FINALIZED)·최종 실패(FAILED)로 결과가 바뀐다
            TxStatus.REJECTED -> {
                when (incoming) {
                    TxStatus.FINALIZED, TxStatus.FAILED -> Publish
                    TxStatus.SUBMITTED, TxStatus.CONFIRMED, TxStatus.REJECTED -> Ignore
                }
            }

            // 영구 실패는 종결
            TxStatus.FAILED -> {
                Ignore
            }
        }
}
