package com.whatto.bcm.domain.event

/**
 * outbox 발행상태(evnt_stcd) — docs/design/03-bcm-db.md bcm_outbox_l.
 * 워커 적재 시 P, relay 발송 성공 시 S, 실패 누적 시 F. relay 는 P 를 evnt_id 순으로 집는다.
 */
enum class OutboxStatus(
    val code: String,
) {
    PENDING("P"),
    DISPATCHED("D"),
    FAILED("F"),
    SUCCESS("S"),
}
