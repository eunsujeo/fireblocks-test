package com.whatto.bcm.domain.tx

/**
 * 매니저 ↔ DAW-CORE 계약 상태 다섯 (docs/design/02-bcm-flow.md 상태 enum · openapi.yaml TxStatus).
 * 벤더(Fireblocks) 원어는 매니저 안에서 이 다섯으로 번역된다 — 벤더가 바뀌어도 유지.
 *
 * ★ CONFIRMED 는 미확정이다 — 벤더 subStatus/networkStatus 의 CONFIRMED 와 철자가 같지만 단계가 다르다.
 * ★ FINALIZED 는 체인 finality 가 아니다 — DCCP 임계 도달일 뿐, reorg 증발 시 FAILED 로 무효화될 수 있다.
 *   상태에 서열을 매겨 "뒤로 가면 무시"로 구현하면 안 된다 (전이 표가 유일한 판정 기준).
 */
enum class TxStatus {
    /** 제출됨 — 서명·전파 준비 중, 체인 미등장 (출금에서만 관찰) */
    SUBMITTED,

    /** 체인에 등장, 컨펌 누적 중 — 미확정 */
    CONFIRMED,

    /** 확정 — DCCP 임계 컨펌 도달 (체인 finality 아님) */
    FINALIZED,

    /** 거부·차단 — 임시. 사람 개입 여지 (종결 아님 — 동결 해제로 결과가 바뀐다) */
    REJECTED,

    /** 영구 실패 — 종결. 사유 동반 (reorg 증발 · revert 등) */
    FAILED,
}
