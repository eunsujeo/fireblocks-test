package com.whatto.bcm.app.webhook.api

import com.whatto.bcm.app.application.webhook.WebhookIngestionResult
import com.whatto.bcm.app.application.webhook.WebhookIngestionService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController

/** Fireblocks 웹훅 v2 수신 endpoint — 성공과 중복은 200, 서명 없음·불일치는 401이다. */
@RestController
class WebhookController(
    private val ingestionService: WebhookIngestionService,
) {
    @PostMapping("/webhook", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun receive(
        @RequestHeader(name = SIGNATURE_HEADER, required = false) signature: String?,
        @RequestBody payload: ByteArray,
    ): ResponseEntity<Void> =
        when (ingestionService.ingest(signature, payload)) {
            WebhookIngestionResult.ACCEPTED -> ResponseEntity.ok().build()
            WebhookIngestionResult.INVALID_SIGNATURE -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }

    companion object {
        const val SIGNATURE_HEADER = "Fireblocks-Webhook-Signature"
    }
}
