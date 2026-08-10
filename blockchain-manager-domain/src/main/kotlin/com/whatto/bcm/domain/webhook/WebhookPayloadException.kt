package com.whatto.bcm.domain.webhook

/** 재시도 후 격리할 수신 payload 오류. 외부 원문 대신 저장 가능한 안전한 사유만 노출한다. */
class WebhookPayloadException(
    val safeReason: String,
    cause: Throwable? = null,
) : RuntimeException(safeReason, cause)
