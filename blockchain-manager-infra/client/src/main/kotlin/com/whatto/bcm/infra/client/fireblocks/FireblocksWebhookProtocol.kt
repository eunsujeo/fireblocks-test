package com.whatto.bcm.infra.client.fireblocks

import com.whatto.bcm.domain.webhook.WebhookEnvelope
import com.whatto.bcm.domain.webhook.WebhookProtocol
import com.whatto.bcm.infra.client.config.ConditionalOnFireblocksProtocol
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/** Fireblocks v2 수신 계약 — 필드 근거는 evidence/96-payload-sample.md의 실물이다. */
@Component
@ConditionalOnFireblocksProtocol
class FireblocksWebhookProtocol(
    private val objectMapper: ObjectMapper,
) : WebhookProtocol {
    override val signatureHeaderName: String = "Fireblocks-Webhook-Signature"

    override fun parseEnvelope(payload: ByteArray): WebhookEnvelope {
        val root = objectMapper.readTree(payload)
        return WebhookEnvelope(
            notificationId = requiredText(root.path("id").asString(), "id"),
            eventType = requiredText(root.path("eventType").asString(), "eventType"),
            vendorTransactionId =
                root
                    .path("data")
                    .path("id")
                    .asString()
                    .takeIf(String::isNotBlank),
        )
    }

    private fun requiredText(
        value: String,
        field: String,
    ): String = value.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("webhook payload missing $field")
}
