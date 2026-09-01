package com.whatto.bcm.app.api.web

import org.springframework.http.HttpStatus

/**
 * `error.code` 10종 — docs/api/openapi.yaml 에러 코드 표 그대로.
 * message 는 클라이언트 노출용 고정 문구 — 내부 예외 메시지를 싣지 않는다.
 */
enum class ErrorCode(
    val status: HttpStatus,
    override val message: String,
) : CodeEnumType {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "request validation failed"),
    ASSET_NOT_SUPPORTED(HttpStatus.BAD_REQUEST, "asset not supported"),
    ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "account not found"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "resource not found"),
    CONFLICT(HttpStatus.CONFLICT, "conflict"),
    UNPROCESSABLE_ENTITY(HttpStatus.UNPROCESSABLE_ENTITY, "request cannot be processed in the current resource state"),
    SUBMIT_IN_PROGRESS(HttpStatus.SERVICE_UNAVAILABLE, "submission for this externalTxId is in progress"),
    CREATION_RETRY_LATER(HttpStatus.SERVICE_UNAVAILABLE, "vendor resource creation must be retried later"),
    RELAY_REJECTED(HttpStatus.BAD_GATEWAY, "relay rejected the transfer"),
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error"),
    ;

    override val code: String get() = name
}
