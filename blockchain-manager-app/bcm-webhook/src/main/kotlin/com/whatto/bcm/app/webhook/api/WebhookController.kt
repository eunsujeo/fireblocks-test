package com.whatto.bcm.app.webhook.api

import com.whatto.bcm.app.webhook.application.webhook.WebhookIngestionResult
import com.whatto.bcm.app.webhook.application.webhook.WebhookIngestionService
import com.whatto.bcm.domain.webhook.WebhookProtocol
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/** 선택된 제공자의 웹훅 수신 — 성공과 중복은 200, 서명 없음·불일치는 401이다. */
@RestController
class WebhookController(
    private val ingestionService: WebhookIngestionService,
    private val protocol: WebhookProtocol,
) {
    @PostMapping("/webhook", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun receive(
        @RequestHeader headers: HttpHeaders,
        @RequestBody payload: ByteArray,
    ): ResponseEntity<Void> =
        when (ingestionService.ingest(headers[protocol.signatureHeaderName]?.singleOrNull(), payload)) {
            WebhookIngestionResult.ACCEPTED -> ResponseEntity.ok().build()
            WebhookIngestionResult.INVALID_SIGNATURE -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
}
