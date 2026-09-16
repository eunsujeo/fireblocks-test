package com.whatto.bcm.app.webhook.api

import com.whatto.bcm.app.webhook.application.webhook.WebhookIngestionService
import com.whatto.bcm.domain.monitoring.NoOpOperationalMetricsPort
import com.whatto.bcm.domain.webhook.WebhookInboxRepository
import com.whatto.bcm.domain.webhook.WebhookInsertResult
import com.whatto.bcm.domain.webhook.WebhookNotification
import com.whatto.bcm.infra.client.dfns.DfnsWebhookProtocol
import com.whatto.bcm.infra.client.dfns.DfnsWebhookSignatureVerifier
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import tools.jackson.databind.ObjectMapper
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Dfns 수신 경로 결합 — 실제 HMAC 검증기·envelope 해석 + 공통 컨트롤러/서비스. payload는 명세 WebhookEvent 형태의 시험 표기이며
 * 실제 Dfns 발송 원문·서명이 아니다(수용 항목). 인박스는 대역이다.
 */
class DfnsWebhookIngestionTest {
    private val now = Instant.parse("2026-09-16T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val objectMapper = ObjectMapper()
    private val inbox = mockk<WebhookInboxRepository>()
    private val protocol = DfnsWebhookProtocol(objectMapper)
    private val verifier = DfnsWebhookSignatureVerifier(listOf(SECRET), 300, clock, objectMapper)
    private val service = WebhookIngestionService(verifier, inbox, protocol, NoOpOperationalMetricsPort, clock)
    private val mvc = MockMvcBuilders.standaloneSetup(WebhookController(service, protocol)).build()
    private val payload =
        """{"id":"wh-1","date":"2026-09-16T00:00:00.000Z","kind":"wallet.transfer.confirmed","data":{"transferRequest":{"id":"xfr-1"}},"status":"200","timestampSent":${now.epochSecond}}"""
            .toByteArray()

    @Test
    fun `서명된 원문은 검증 뒤 id·kind envelope와 원문·해시·실제 서명 그대로 인박스에 적재되고 200이다`() {
        val signature = sign(payload)
        val notification = slot<WebhookNotification>()
        every { inbox.insertIfAbsent(capture(notification)) } returns WebhookInsertResult.INSERTED

        mvc
            .perform(
                post("/webhook").contentType(MediaType.APPLICATION_JSON).content(payload).header("x-dfns-webhook-signature", signature),
            ).andExpect(status().isOk)

        assertThat(notification.captured.notificationId).isEqualTo("wh-1")
        assertThat(notification.captured.eventType).isEqualTo("wallet.transfer.confirmed")
        assertThat(notification.captured.vendorTransactionId).isNull()
        assertThat(notification.captured.payload).isEqualTo(payload.toString(Charsets.UTF_8))
        assertThat(notification.captured.payloadHash).isEqualTo(
            MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") {
                "%02x".format(it)
            },
        )
        assertThat(notification.captured.signature).isEqualTo(signature)
    }

    @Test
    fun `서명 헤더가 없거나 둘이거나 본문이 변조되거나 timestampSent가 오래되면 적재 없이 401이다`() {
        val stale =
            """{"id":"wh-2","date":"2026-09-16T00:00:00.000Z","kind":"wallet.transfer.confirmed","data":{},"status":"200","timestampSent":${now.epochSecond - 600}}"""
                .toByteArray()
        mvc.perform(post("/webhook").contentType(MediaType.APPLICATION_JSON).content(payload)).andExpect(status().isUnauthorized)
        mvc
            .perform(
                post("/webhook")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload)
                    .header("X-DFNS-WEBHOOK-SIGNATURE", sign(payload))
                    .header("X-DFNS-WEBHOOK-SIGNATURE", sign(payload)),
            ).andExpect(status().isUnauthorized)
        mvc
            .perform(
                post(
                    "/webhook",
                ).contentType(
                    MediaType.APPLICATION_JSON,
                ).content(payload + ' '.code.toByte())
                    .header("X-DFNS-WEBHOOK-SIGNATURE", sign(payload)),
            ).andExpect(status().isUnauthorized)
        mvc
            .perform(
                post("/webhook").contentType(MediaType.APPLICATION_JSON).content(stale).header("X-DFNS-WEBHOOK-SIGNATURE", sign(stale)),
            ).andExpect(status().isUnauthorized)
        verify(exactly = 0) { inbox.insertIfAbsent(any()) }
    }

    private fun sign(body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(SECRET.toByteArray(), "HmacSHA256")) }
        return "sha256=" + mac.doFinal(body).joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val SECRET = "test-webhook-secret"
    }
}
