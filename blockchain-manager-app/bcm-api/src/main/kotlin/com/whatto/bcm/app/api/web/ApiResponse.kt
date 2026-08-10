package com.whatto.bcm.app.api.web

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * 성공 응답 envelope — data/meta.requestId/pagination (openapi v0.0.5 info 절).
 * data 는 null 도 그대로 직렬화한다 — depositAddressOf 미발급이 `data: null` 을 계약한다 (AddressNullableResponse).
 * pagination 은 목록 응답에만 실린다 — null 이면 직렬화에서 빠진다.
 */
data class ApiResponse<T>(
    val data: T,
    val meta: Meta,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val pagination: Pagination? = null,
) {
    companion object {
        fun <T> of(
            data: T,
            requestId: String,
            pagination: Pagination? = null,
        ): ApiResponse<T> = ApiResponse(data, Meta(requestId), pagination)
    }
}

data class Meta(
    val requestId: String,
)

data class Pagination(
    val nextCursor: String,
    val hasMore: Boolean,
)

/** 에러 응답 envelope — error.code 로 판단한다 (openapi 에러 절). */
data class ErrorResponse(
    val error: ErrorBody,
    val meta: Meta,
) {
    data class ErrorBody(
        val code: String,
        val message: String,
    )
}
