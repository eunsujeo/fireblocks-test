package com.whatto.bcm.app.api.web

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

/**
 * `error.code` 9종 계약 고정 — docs/api/openapi.yaml 에러 코드 표.
 */
class ErrorCodeTest {
    @Test
    fun `에러 코드는 9종이고 code 값은 스펙 표기 그대로다`() {
        assertThat(ErrorCode.entries).hasSize(9)
        assertThat(ErrorCode.entries.map { it.code }).containsExactlyInAnyOrder(
            "VALIDATION_FAILED",
            "ASSET_NOT_SUPPORTED",
            "ACCOUNT_NOT_FOUND",
            "NOT_FOUND",
            "CONFLICT",
            "UNPROCESSABLE_ENTITY",
            "SUBMIT_IN_PROGRESS",
            "RELAY_REJECTED",
            "INTERNAL",
        )
    }

    @Test
    fun `코드별 HTTP status — 스펙 표와 일치`() {
        assertThat(ErrorCode.VALIDATION_FAILED.status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(ErrorCode.ASSET_NOT_SUPPORTED.status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(ErrorCode.ACCOUNT_NOT_FOUND.status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(ErrorCode.NOT_FOUND.status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(ErrorCode.CONFLICT.status).isEqualTo(HttpStatus.CONFLICT)
        assertThat(ErrorCode.UNPROCESSABLE_ENTITY.status).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat(ErrorCode.SUBMIT_IN_PROGRESS.status).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
        assertThat(ErrorCode.RELAY_REJECTED.status).isEqualTo(HttpStatus.BAD_GATEWAY)
        assertThat(ErrorCode.INTERNAL.status).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
    }

    @Test
    fun `모든 에러 코드는 CodeEnumType 을 구현한다 — 사내 표준`() {
        ErrorCode.entries.forEach { code ->
            assertThat(code).isInstanceOf(CodeEnumType::class.java)
            assertThat(code.message).isNotBlank()
        }
    }
}
