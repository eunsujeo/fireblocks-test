package com.whatto.bcm.app.webhook.application.event

import com.ninjasquad.springmockk.MockkBean
import com.whatto.bcm.app.webhook.BcmWebhookApplication
import com.whatto.bcm.domain.event.EventPublisher
import com.whatto.bcm.domain.event.OutboxEvent
import com.whatto.bcm.domain.event.OutboxEventRepository
import com.whatto.bcm.domain.event.OutboxEventType
import com.whatto.bcm.domain.event.PoisonOutboxAlertPort
import com.whatto.bcm.testsupport.integration.IntegrationTestSupport
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@SpringBootTest(
    classes = [BcmWebhookApplication::class],
    properties = ["bcm.outbox-relay.enabled=false"],
)
class OutboxRelayProcessorIntegrationTest : IntegrationTestSupport() {
    @Autowired
    lateinit var processor: OutboxRelayProcessor

    @Autowired
    lateinit var outbox: OutboxEventRepository

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @MockkBean
    lateinit var publisher: EventPublisher

    @MockkBean
    lateinit var poisonAlert: PoisonOutboxAlertPort

    @BeforeEach
    fun setUp() {
        jdbc.update("DELETE FROM bcm_outbox_l")
        every { publisher.publish(any(), any(), any()) } just Runs
        every { poisonAlert.alert(any(), any()) } just Runs
    }

    @AfterEach
    fun tearDown() {
        jdbc.update("DELETE FROM bcm_outbox_l")
    }

    @Test
    fun `같은 계정 이벤트는 evnt_id 순서로 발행되고 S 처리된다`() {
        val first = event("0198c0de-0000-7000-8000-000000000001", "CONFIRMED")
        val second = event("0198c0de-0000-7000-8000-000000000002", "FINALIZED")
        outbox.insertAll(listOf(second, first))

        processor.relayNext()
        processor.relayNext()

        verifyOrder {
            publisher.publish("deposit-events", "acct-deposit", match { it.contains(first.eventId) })
            publisher.publish("deposit-events", "acct-deposit", match { it.contains(second.eventId) })
        }
        assertThat(jdbc.queryForList("SELECT evnt_stcd FROM bcm_outbox_l ORDER BY evnt_id", String::class.java))
            .containsExactly("S", "S")
    }

    @Test
    fun `Kafka 실패는 재시도 뒤 F로 격리하고 식별자만 알림한다`() {
        val event = event("0198c0de-0000-7000-8000-000000000001", "CONFIRMED", maxRetryCount = 2)
        outbox.insertAll(listOf(event))
        every { publisher.publish(any(), any(), any()) } throws IllegalStateException("broker detail")

        val first = processor.relayNext()
        val second = processor.relayNext()

        assertThat(first).isEqualTo(OutboxRelayOutcome.Retrying(event.eventId, 1))
        assertThat(second).isEqualTo(OutboxRelayOutcome.Quarantined(event.eventId, 2))
        val row = jdbc.queryForMap("SELECT * FROM bcm_outbox_l WHERE evnt_id = ?", event.eventId)
        assertThat(row["evnt_stcd"]).isEqualTo("F")
        assertThat(row["err_msg"]).isEqualTo("Kafka publish failed")
        verify(exactly = 1) { poisonAlert.alert(event.eventId, 2) }
    }

    @Test
    fun `동시에 실행된 릴레이는 전역 순서를 건너뛰지 않는다`() {
        val first = event("0198c0de-0000-7000-8000-000000000001", "CONFIRMED")
        val second = event("0198c0de-0000-7000-8000-000000000002", "FINALIZED")
        outbox.insertAll(listOf(first, second))
        val firstPublishStarted = CountDownLatch(1)
        val releaseFirstPublish = CountDownLatch(1)
        val publishCount = AtomicInteger()
        val executor = Executors.newSingleThreadExecutor()
        every { publisher.publish(any(), any(), any()) } answers {
            if (publishCount.incrementAndGet() == 1) {
                firstPublishStarted.countDown()
                check(releaseFirstPublish.await(5, TimeUnit.SECONDS))
            }
        }

        try {
            val firstResult = executor.submit<OutboxRelayOutcome> { processor.relayNext() }
            assertThat(firstPublishStarted.await(5, TimeUnit.SECONDS)).isTrue()

            assertThat(processor.relayNext()).isEqualTo(OutboxRelayOutcome.NoWork)
            assertThat(publishCount.get()).isEqualTo(1)

            releaseFirstPublish.countDown()
            assertThat(firstResult.get(5, TimeUnit.SECONDS)).isEqualTo(OutboxRelayOutcome.Published(first.eventId))
            assertThat(processor.relayNext()).isEqualTo(OutboxRelayOutcome.Published(second.eventId))
        } finally {
            releaseFirstPublish.countDown()
            executor.shutdownNow()
        }

        verifyOrder {
            publisher.publish("deposit-events", "acct-deposit", match { it.contains(first.eventId) })
            publisher.publish("deposit-events", "acct-deposit", match { it.contains(second.eventId) })
        }
    }

    @Test
    fun `선행 감지 이벤트가 F면 같은 거래의 후속 확정 이벤트를 발행하지 않는다`() {
        val detection = event("0198c0de-0000-7000-8000-000000000001", "CONFIRMED", maxRetryCount = 1)
        val finalized = event("0198c0de-0000-7000-8000-000000000002", "FINALIZED")
        outbox.insertAll(listOf(detection, finalized))
        every { publisher.publish(any(), any(), any()) } throws IllegalStateException("broker detail")

        assertThat(processor.relayNext()).isEqualTo(OutboxRelayOutcome.Quarantined(detection.eventId, 1))
        assertThat(processor.relayNext()).isEqualTo(OutboxRelayOutcome.NoWork)

        assertThat(jdbc.queryForList("SELECT evnt_stcd FROM bcm_outbox_l ORDER BY evnt_id", String::class.java))
            .containsExactly("F", "P")
        verify(exactly = 1) { publisher.publish(any(), any(), any()) }
    }

    private fun event(
        id: String,
        status: String,
        maxRetryCount: Int = 5,
    ) = OutboxEvent(
        eventId = id,
        eventDate = "20260807",
        vendorTransactionId = "tx-91c",
        eventType = if (status == "FINALIZED") OutboxEventType.CONFIRMED else OutboxEventType.CHECKING,
        topic = "deposit-events",
        payload =
            """{"eventId":"$id","type":"DEPOSIT","txId":"tx-91c","txHash":null,"externalTxId":null,"accountId":"acct-deposit","network":"ETHEREUM","symbol":"USDC","to":"0xTo","from":"0xFrom","amount":"100","status":"$status","numOfConfirmations":1}""",
        maxRetryCount = maxRetryCount,
    )
}
