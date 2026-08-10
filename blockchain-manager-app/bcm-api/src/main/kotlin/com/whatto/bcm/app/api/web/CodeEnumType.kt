package com.whatto.bcm.app.api.web

/**
 * 코드성 enum 공통 계약 — 사내 표준 (docs/standards/architecture.md).
 * 에러 코드 enum 은 반드시 이 인터페이스를 구현한다 (.claude/rules/error-handling.md).
 */
interface CodeEnumType {
    val code: String
    val message: String
}
