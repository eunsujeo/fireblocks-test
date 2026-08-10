package com.whatto.bcm.app.application.webhook

import com.whatto.bcm.domain.webhook.WebhookInboxRepository
import com.whatto.bcm.domain.webhook.WebhookNotification
import com.whatto.bcm.domain.webhook.WebhookSignatureVerifier
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock

/** 웹훅 요청 처리의 전부: 원문 서명 검증 → 인박스 1회 적재. 판단은 Phase 4 워커 소관이다. */
@Service
class WebhookIngestionService(
    private val signatureVerifier: WebhookSignatureVerifier,
    private val webhookInboxRepository: WebhookInboxRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    fun ingest(
        signature: String?,
        payload: ByteArray,
    ): WebhookIngestionResult {
        if (signature.isNullOrBlank() || !signatureVerifier.verify(signature, payload)) {
            return WebhookIngestionResult.INVALID_SIGNATURE
        }

        val root = objectMapper.readTree(payload)
        val notificationId = requiredText(root.path("id").asString(), "id")
        val eventType = requiredText(root.path("eventType").asString(), "eventType")
        val vendorTransactionId =
            root
                .path("data")
                .path("id")
                .asString()
                .takeIf(String::isNotBlank)
        webhookInboxRepository.insertIfAbsent(
            WebhookNotification(
                notificationId = notificationId,
                eventType = eventType,
                vendorTransactionId = vendorTransactionId,
                payload = String(payload, StandardCharsets.UTF_8),
                payloadHash = sha256Hex(payload),
                signature = signature,
                receivedAt = CoreDateTimes.now(clock),
            ),
        )
        return WebhookIngestionResult.ACCEPTED
    }

    private fun requiredText(
        value: String,
        field: String,
    ): String = value.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("webhook payload missing $field")

    private fun sha256Hex(payload: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }
}

enum class WebhookIngestionResult {
    ACCEPTED,
    INVALID_SIGNATURE,
}
