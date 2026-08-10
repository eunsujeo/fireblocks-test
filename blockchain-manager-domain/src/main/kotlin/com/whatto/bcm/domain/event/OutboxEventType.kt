package com.whatto.bcm.domain.event

import com.whatto.bcm.domain.tx.TxStatus

/**
 * outbox 이벤트유형(evt_typ_dvcd) — 코어 이벤트 어휘와 통일 (docs/design/03-bcm-db.md bcm_outbox_l).
 * TXCK(Checking = 감지) · TXCF(Confirmed = 확정) · TXFL(Failed) · TXRJ(Rejected).
 */
enum class OutboxEventType(
    val code: String,
) {
    /** 감지 — 신규 관찰 (입금 CONFIRMED · 출금 SUBMITTED). 합성 발행의 대상 */
    CHECKING("TXCK"),

    /** 확정 — FINALIZED 발행 */
    CONFIRMED("TXCF"),

    /** 실패 — FAILED 발행 (reorg 무효화 포함) */
    FAILED("TXFL"),

    /** 거부 — REJECTED 발행. 코어 회신 전까지 이 한 곳에서만 임시 코드값을 관리한다. */
    REJECTED("TXRJ"),

    ;

    companion object {
        /**
         * 발행할 TxStatus → 이벤트유형. 근거: 03 입금 시나리오(감지=TXCK · 확정=TXCF) · 03 컬럼 표(TXFL).
         * REJECTED 는 코어 회신 전 임시 코드 TXRJ 로 발행한다 (PLAN #15).
         */
        fun forPublishedStatus(status: TxStatus): OutboxEventType =
            when (status) {
                TxStatus.SUBMITTED, TxStatus.CONFIRMED -> CHECKING
                TxStatus.FINALIZED -> CONFIRMED
                TxStatus.FAILED -> FAILED
                TxStatus.REJECTED -> REJECTED
            }
    }
}
