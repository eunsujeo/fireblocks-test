package com.whatto.bcm.support.audit

/**
 * 감사 4컬럼의 시스템 센티넬 — 자동 처리 행에 채운다 (03-bcm-db 확정 이력 2026-08-05).
 * 단일 상수 관리 — 코어 운영 규약이 확인되면 여기 한 곳만 바꾼다.
 * Admin 수동 개입(수동 boost·동결 해제 등)은 실제 직원/부점을 쓴다 (Phase 7).
 */
object SystemAudit {
    const val EMPNO = "SYSTEM"
    const val BRCD = "9999"
}
