package com.whatto.bcm.domain.tx

import com.whatto.bcm.domain.tx.TransitionDecision.Ignore
import com.whatto.bcm.domain.tx.TransitionDecision.Publish
import com.whatto.bcm.domain.tx.TransitionDecision.RecordOnly
import com.whatto.bcm.domain.tx.TransitionDecision.SynthesizeDetectionThenPublish
import com.whatto.bcm.domain.tx.TxStatus.CONFIRMED
import com.whatto.bcm.domain.tx.TxStatus.FAILED
import com.whatto.bcm.domain.tx.TxStatus.FINALIZED
import com.whatto.bcm.domain.tx.TxStatus.REJECTED
import com.whatto.bcm.domain.tx.TxStatus.SUBMITTED
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 허용 전이 표 전 케이스 고정 — docs/design/02-bcm-flow.md "허용 전이 표" 표의 행 그대로.
 * (직전 상태 6종 × 새 상태 5종 = 30조합 전수)
 */
class TransitionTableTest {
    /** 계약 기대값 전수 행렬 — 02 전이 표가 정본, 표에 없는 전이는 Ignore */
    private val contract: Map<Pair<TxStatus?, TxStatus>, TransitionDecision> =
        mapOf(
            // (없음) 행 — 신규 도착
            (null to SUBMITTED) to Publish,
            (null to CONFIRMED) to Publish,
            (null to FINALIZED) to SynthesizeDetectionThenPublish,
            (null to REJECTED) to SynthesizeDetectionThenPublish,
            (null to FAILED) to RecordOnly,
            // SUBMITTED 행
            (SUBMITTED to SUBMITTED) to Ignore,
            (SUBMITTED to CONFIRMED) to Publish,
            (SUBMITTED to FINALIZED) to Publish,
            (SUBMITTED to REJECTED) to Publish,
            (SUBMITTED to FAILED) to Publish,
            // CONFIRMED 행
            (CONFIRMED to SUBMITTED) to Ignore,
            (CONFIRMED to CONFIRMED) to Ignore,
            (CONFIRMED to FINALIZED) to Publish,
            (CONFIRMED to REJECTED) to Publish,
            (CONFIRMED to FAILED) to Publish,
            // FINALIZED 행 — reorg 무효화와 확정 후 동결을 반영, 나머지는 늦게 온 옛 알림
            (FINALIZED to SUBMITTED) to Ignore,
            (FINALIZED to CONFIRMED) to Ignore,
            (FINALIZED to FINALIZED) to Ignore,
            (FINALIZED to REJECTED) to Publish,
            (FINALIZED to FAILED) to Publish,
            // REJECTED 행 — 종결 아님 (동결 해제·최종 실패)
            (REJECTED to SUBMITTED) to Ignore,
            (REJECTED to CONFIRMED) to Ignore,
            (REJECTED to FINALIZED) to Publish,
            (REJECTED to REJECTED) to Ignore,
            (REJECTED to FAILED) to Publish,
            // FAILED 행 — 영구 실패는 종결
            (FAILED to SUBMITTED) to Ignore,
            (FAILED to CONFIRMED) to Ignore,
            (FAILED to FINALIZED) to Ignore,
            (FAILED to REJECTED) to Ignore,
            (FAILED to FAILED) to Ignore,
        )

    @Test
    fun `전이 표 전 케이스 30조합이 계약 기대값과 일치한다`() {
        val previousStates: List<TxStatus?> = listOf(null) + TxStatus.entries
        for (previous in previousStates) {
            for (incoming in TxStatus.entries) {
                val expected = contract.getValue(previous to incoming)
                assertThat(TransitionTable.decide(previous, incoming))
                    .describedAs("전이 %s → %s", previous ?: "(없음)", incoming)
                    .isEqualTo(expected)
            }
        }
    }

    @Test
    fun `FINALIZED 에서 FAILED 는 reorg 무효화로 발행·반영한다 — 서열 구현 금지`() {
        assertThat(TransitionTable.decide(FINALIZED, FAILED)).isEqualTo(Publish)
    }

    @Test
    fun `FINALIZED 에서 REJECTED 는 확정 후 동결로 발행·반영한다`() {
        assertThat(TransitionTable.decide(FINALIZED, REJECTED)).isEqualTo(Publish)
    }

    @Test
    fun `FINALIZED 에서 CONFIRMED 알림이 오면 무시한다`() {
        assertThat(TransitionTable.decide(FINALIZED, CONFIRMED)).isEqualTo(Ignore)
    }

    @Test
    fun `기록 없이 FINALIZED 가 오면 감지 이벤트를 합성해 먼저 발행한다`() {
        assertThat(TransitionTable.decide(null, FINALIZED)).isEqualTo(SynthesizeDetectionThenPublish)
    }

    @Test
    fun `기록 없이 FAILED 가 오면 기록만 남기고 발행하지 않는다`() {
        assertThat(TransitionTable.decide(null, FAILED)).isEqualTo(RecordOnly)
    }

    @Test
    fun `FAILED 는 종결이라 이후 어떤 알림도 무시한다`() {
        for (incoming in TxStatus.entries) {
            assertThat(TransitionTable.decide(FAILED, incoming))
                .describedAs("FAILED → %s", incoming)
                .isEqualTo(Ignore)
        }
    }

    @Test
    fun `REJECTED 는 종결이 아니다 — 동결 해제(FINALIZED)와 최종 실패(FAILED)는 발행한다`() {
        assertThat(TransitionTable.decide(REJECTED, FINALIZED)).isEqualTo(Publish)
        assertThat(TransitionTable.decide(REJECTED, FAILED)).isEqualTo(Publish)
    }

    @Test
    fun `합성 판정은 감지 상태를 실제 수신 상태보다 먼저 발행한다`() {
        assertThat(SynthesizeDetectionThenPublish.publishedStatuses(FINALIZED))
            .containsExactly(CONFIRMED, FINALIZED)
    }

    @Test
    fun `무시 판정은 기존 기록 상태를 유지하고 그 밖의 판정은 수신 상태를 기록한다`() {
        assertThat(Ignore.statusToRecord(CONFIRMED, SUBMITTED)).isEqualTo(CONFIRMED)
        assertThat(Publish.statusToRecord(CONFIRMED, FINALIZED)).isEqualTo(FINALIZED)
        assertThat(RecordOnly.statusToRecord(null, FAILED)).isEqualTo(FAILED)
    }
}
