package com.whatto.bcm.app.webhook.application.webhook

import com.whatto.bcm.domain.monitoring.OperationalMetricsPort
import com.whatto.bcm.domain.monitoring.WebhookIngestionMetricOutcome
import com.whatto.bcm.domain.webhook.WebhookInboxRepository
import com.whatto.bcm.domain.webhook.WebhookInsertResult
import com.whatto.bcm.domain.webhook.WebhookSignatureVerifier
import com.whatto.bcm.infra.client.fireblocks.FireblocksWebhookProtocol
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class WebhookIngestionServiceTest {
    private val signatureVerifier = mockk<WebhookSignatureVerifier>()
    private val inboxRepository = mockk<WebhookInboxRepository>()
    private val objectMapper = mockk<ObjectMapper>()
    private val metrics = mockk<OperationalMetricsPort>(relaxed = true)
    private val clock = Clock.fixed(Instant.parse("2026-08-06T03:00:00Z"), ZoneId.of("Asia/Seoul"))
    private val service =
        WebhookIngestionService(signatureVerifier, inboxRepository, FireblocksWebhookProtocol(objectMapper), metrics, clock)

    @Test
    fun `서명이 틀리면 payload를 파싱하거나 적재하지 않고 거절한다`() {
        val payload = realPayload()
        every { signatureVerifier.verify("invalid", payload) } returns false

        val result = service.ingest("invalid", payload)

        assertThat(result).isEqualTo(WebhookIngestionResult.INVALID_SIGNATURE)
        verify(exactly = 1) {
            metrics.recordWebhookIngestion(WebhookIngestionMetricOutcome.INVALID_SIGNATURE, null)
        }
        verify(exactly = 0) { objectMapper.readTree(any<ByteArray>()) }
        verify(exactly = 0) { inboxRepository.insertIfAbsent(any()) }
    }

    @Test
    fun `정상 수신은 accepted와 마지막 정상 수신 시각을 기록한다`() {
        val payload = realPayload()
        every { signatureVerifier.verify("valid", payload) } returns true
        every { objectMapper.readTree(payload) } returns ObjectMapper().readTree(payload)
        every { inboxRepository.insertIfAbsent(any()) } returns WebhookInsertResult.INSERTED

        val result = service.ingest("valid", payload)

        assertThat(result).isEqualTo(WebhookIngestionResult.ACCEPTED)
        verify(exactly = 1) {
            metrics.recordWebhookIngestion(WebhookIngestionMetricOutcome.ACCEPTED, "20260806030000")
        }
    }

    @Test
    fun `계측 실패는 이미 적재한 정상 웹훅의 응답을 실패시키지 않는다`() {
        val payload = realPayload()
        every { signatureVerifier.verify("valid", payload) } returns true
        every { objectMapper.readTree(payload) } returns ObjectMapper().readTree(payload)
        every { inboxRepository.insertIfAbsent(any()) } returns WebhookInsertResult.INSERTED
        every {
            metrics.recordWebhookIngestion(WebhookIngestionMetricOutcome.ACCEPTED, "20260806030000")
        } throws IllegalStateException("metrics unavailable")

        assertThat(service.ingest("valid", payload)).isEqualTo(WebhookIngestionResult.ACCEPTED)
        verify(exactly = 1) { inboxRepository.insertIfAbsent(any()) }
    }

    @Test
    fun `서명은 맞지만 알림 id가 결손이면 실패하고 인박스에 넣지 않는다`() {
        val payload = realPayload()
        val parsed = ObjectMapper().createObjectNode().put("eventType", "transaction.created")
        every { signatureVerifier.verify("valid", payload) } returns true
        every { objectMapper.readTree(payload) } returns parsed

        assertThatThrownBy { service.ingest("valid", payload) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("id")
        verify(exactly = 1) { metrics.recordWebhookIngestion(WebhookIngestionMetricOutcome.ERROR, null) }
        verify(exactly = 0) { inboxRepository.insertIfAbsent(any()) }
    }

    @Test
    fun `서명은 맞지만 eventType이 결손이면 실패하고 인박스에 넣지 않는다`() {
        val payload = realPayload()
        val parsed = ObjectMapper().createObjectNode().put("id", "434e8c65-9489-4621-830f-eebe03cb2b3d")
        every { signatureVerifier.verify("valid", payload) } returns true
        every { objectMapper.readTree(payload) } returns parsed

        assertThatThrownBy { service.ingest("valid", payload) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("eventType")
        verify(exactly = 0) { inboxRepository.insertIfAbsent(any()) }
    }

    private fun realPayload(): ByteArray =
        checkNotNull(javaClass.getResourceAsStream("/payload/transaction.created.json"))
            .use { it.readAllBytes() }
}
