package com.whatto.bcm.domain.event

import com.whatto.bcm.domain.tx.TxStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * evt_typ_dvcd 계약 고정 — docs/design/03-bcm-db.md bcm_outbox_l (입금 시나리오: 감지=TXCK · 확정=TXCF).
 */
class OutboxEventTypeTest {
    @Test
    fun `코드값은 코어 이벤트 어휘 그대로다 — TXCK·TXCF·TXFL·TXRJ`() {
        assertThat(OutboxEventType.CHECKING.code).isEqualTo("TXCK")
        assertThat(OutboxEventType.CONFIRMED.code).isEqualTo("TXCF")
        assertThat(OutboxEventType.FAILED.code).isEqualTo("TXFL")
        assertThat(OutboxEventType.REJECTED.code).isEqualTo("TXRJ")
    }

    @Test
    fun `감지 단계(입금 CONFIRMED · 출금 SUBMITTED)는 TXCK 다`() {
        assertThat(OutboxEventType.forPublishedStatus(TxStatus.CONFIRMED)).isEqualTo(OutboxEventType.CHECKING)
        assertThat(OutboxEventType.forPublishedStatus(TxStatus.SUBMITTED)).isEqualTo(OutboxEventType.CHECKING)
    }

    @Test
    fun `확정(FINALIZED)은 TXCF 다`() {
        assertThat(OutboxEventType.forPublishedStatus(TxStatus.FINALIZED)).isEqualTo(OutboxEventType.CONFIRMED)
    }

    @Test
    fun `실패(FAILED)는 TXFL 다 — reorg 무효화 포함`() {
        assertThat(OutboxEventType.forPublishedStatus(TxStatus.FAILED)).isEqualTo(OutboxEventType.FAILED)
    }

    @Test
    fun `거부(REJECTED)는 코어 회신 전 임시 코드 TXRJ 다`() {
        assertThat(OutboxEventType.forPublishedStatus(TxStatus.REJECTED)).isEqualTo(OutboxEventType.REJECTED)
    }

    @Test
    fun `발행상태 코드값 — P·D·F·S`() {
        assertThat(OutboxStatus.PENDING.code).isEqualTo("P")
        assertThat(OutboxStatus.DISPATCHED.code).isEqualTo("D")
        assertThat(OutboxStatus.FAILED.code).isEqualTo("F")
        assertThat(OutboxStatus.SUCCESS.code).isEqualTo("S")
    }

    @Test
    fun `EventType 토픽 — 계열별 3토픽 (openapi 이벤트 절)`() {
        assertThat(EventType.DEPOSIT.topic).isEqualTo("deposit-events")
        assertThat(EventType.WITHDRAWAL.topic).isEqualTo("withdrawal-events")
        assertThat(EventType.INTERNAL.topic).isEqualTo("internal-events")
    }
}
