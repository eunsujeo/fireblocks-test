package com.whatto.bcm.domain.event

/**
 * 이벤트 분류 셋 — 계열별 토픽 (docs/design/02-bcm-flow.md "EventType" 절 · openapi.yaml 이벤트 절).
 * 파티션 키는 수신자가 아니라 순서 보장 단위다 (01-infra 4토픽 표).
 */
enum class EventType(
    val topic: String,
) {
    /** 고객 입금 — 매핑된 주소로 수신. 파티션 키 = 고객 accountId */
    DEPOSIT("deposit-events"),

    /** 외부 출금. 파티션 키 = 출금 풀 vault 의 accountId */
    WITHDRAWAL("withdrawal-events"),

    /** delta 정산. 파티션 키 = 출발 계정 accountId (sweep 은 싣지 않는다) */
    INTERNAL("internal-events"),
}
