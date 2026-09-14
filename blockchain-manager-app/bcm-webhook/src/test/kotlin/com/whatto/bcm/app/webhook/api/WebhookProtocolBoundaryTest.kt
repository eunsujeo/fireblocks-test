package com.whatto.bcm.app.webhook.api

import com.whatto.bcm.app.webhook.application.webhook.WebhookIngestionService
import com.whatto.bcm.domain.monitoring.NoOpOperationalMetricsPort
import com.whatto.bcm.domain.webhook.WebhookEnvelope
import com.whatto.bcm.domain.webhook.WebhookInboxRepository
import com.whatto.bcm.domain.webhook.WebhookInsertResult
import com.whatto.bcm.domain.webhook.WebhookNotification
import com.whatto.bcm.domain.webhook.WebhookProtocol
import com.whatto.bcm.domain.webhook.WebhookSignatureVerifier
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** 대체 프로토콜은 경계 검사용 대역이며 Dfns payload/서명 모사가 아니다. */
class WebhookProtocolBoundaryTest {
    private val protocol = mockk<WebhookProtocol>()
    private val verifier = mockk<WebhookSignatureVerifier>()
    private val inbox = mockk<WebhookInboxRepository>()
    private val clock = Clock.fixed(Instant.parse("2026-09-14T00:00:00Z"), ZoneOffset.UTC)
    private val service = WebhookIngestionService(verifier, inbox, protocol, NoOpOperationalMetricsPort, clock)
    private val mvc = MockMvcBuilders.standaloneSetup(WebhookController(service, protocol)).build()
    private val payload = checkNotNull(javaClass.getResourceAsStream("/payload/transaction.created.json")).use { it.readAllBytes() }

    init {
        every { protocol.signatureHeaderName } returns "X-Test-Provider-Signature"
    }

    @Test
    fun `선택된 헤더와 envelope를 사용하고 검증 뒤 원문과 실제 서명을 보존한다`() {
        val signature = " original-signature "
        val notification = slot<WebhookNotification>()
        every { verifier.verify(signature, any()) } returns true
        every { protocol.parseEnvelope(any()) } returns WebhookEnvelope("adapter-notification", "adapter.event", "adapter-transaction")
        every { inbox.insertIfAbsent(capture(notification)) } returns WebhookInsertResult.INSERTED

        mvc
            .perform(
                post("/webhook")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload)
                    .header("x-test-provider-signature", signature),
            ).andExpect(status().isOk)

        assertThat(notification.captured.notificationId).isEqualTo("adapter-notification")
        assertThat(notification.captured.eventType).isEqualTo("adapter.event")
        assertThat(notification.captured.vendorTransactionId).isEqualTo("adapter-transaction")
        assertThat(notification.captured.payload.toByteArray(Charsets.UTF_8)).isEqualTo(payload)
        assertThat(notification.captured.signature).isEqualTo(signature)
        assertThat(notification.captured.payloadHash).isEqualTo(
            MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) },
        )
        verifyOrder {
            verifier.verify(signature, match { it.contentEquals(payload) })
            protocol.parseEnvelope(match { it.contentEquals(payload) })
            inbox.insertIfAbsent(any())
        }
    }

    @Test
    fun `비선택 제공자의 헤더만 있으면 검증이나 파싱 없이 거절한다`() {
        mvc
            .perform(
                post("/webhook")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload)
                    .header("Fireblocks-Webhook-Signature", "unselected"),
            ).andExpect(status().isUnauthorized)
        assertNoProcessing()
    }

    @Test
    fun `선택된 서명 헤더가 여러 번 있으면 임의로 하나를 선택하지 않는다`() {
        mvc
            .perform(
                post("/webhook")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload)
                    .header("X-Test-Provider-Signature", "first", "second"),
            ).andExpect(status().isUnauthorized)
        assertNoProcessing()
    }

    @Test
    fun `서명 헤더가 없으면 검증이나 파싱 없이 거절한다`() {
        mvc
            .perform(post("/webhook").contentType(MediaType.APPLICATION_JSON).content(payload))
            .andExpect(status().isUnauthorized)
        assertNoProcessing()
    }

    @Test
    fun `선택된 검증기가 거절하면 envelope를 파싱하지 않는다`() {
        every { verifier.verify("invalid", any()) } returns false
        mvc
            .perform(
                post("/webhook")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(payload)
                    .header("X-Test-Provider-Signature", "invalid"),
            ).andExpect(status().isUnauthorized)
        verify(exactly = 0) { protocol.parseEnvelope(any()) }
        verify(exactly = 0) { inbox.insertIfAbsent(any()) }
    }

    @Test
    fun `검증 후 envelope 오류는 성공으로 숨기거나 저장하지 않는다`() {
        val failure = IllegalArgumentException("invalid envelope")
        every { verifier.verify("valid", payload) } returns true
        every { protocol.parseEnvelope(payload) } throws failure
        assertThatThrownBy { service.ingest("valid", payload) }.isSameAs(failure)
        verify(exactly = 0) { inbox.insertIfAbsent(any()) }
    }

    private fun assertNoProcessing() {
        verify(exactly = 0) { verifier.verify(any(), any()) }
        verify(exactly = 0) { protocol.parseEnvelope(any()) }
        verify(exactly = 0) { inbox.insertIfAbsent(any()) }
    }
}
