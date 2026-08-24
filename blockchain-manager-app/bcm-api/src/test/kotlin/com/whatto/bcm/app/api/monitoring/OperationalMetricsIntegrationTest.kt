package com.whatto.bcm.app.api.monitoring

import com.whatto.bcm.app.api.support.IntegrationTestSupport
import com.whatto.bcm.domain.event.OutboxEvent
import com.whatto.bcm.domain.event.OutboxEventRepository
import com.whatto.bcm.domain.event.OutboxEventType
import com.whatto.bcm.domain.job.JobStateRepository
import com.whatto.bcm.domain.monitoring.OperationalAlertChannel
import com.whatto.bcm.domain.monitoring.OperationalMetricsPort
import com.whatto.bcm.domain.monitoring.VendorCallMetricOutcome
import com.whatto.bcm.domain.monitoring.WebhookIngestionMetricOutcome
import com.whatto.bcm.domain.webhook.WebhookInboxRepository
import com.whatto.bcm.domain.webhook.WebhookNotification
import com.whatto.bcm.support.id.UuidV7Generator
import com.whatto.bcm.support.monitoring.OperationalAlertProperties
import com.whatto.bcm.support.monitoring.OperationalMetricsPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "bcm.operational-metrics.initial-delay-millis=3600000",
        "management.server.port=0",
        "management.server.address=127.0.0.1",
        "BCM_HTTP_MAX_CONNECTIONS=100",
    ],
)
@AutoConfigureMockMvc
class OperationalMetricsIntegrationTest : IntegrationTestSupport() {
    @LocalManagementPort
    var managementPort: Int = 0

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var inbox: WebhookInboxRepository

    @Autowired
    lateinit var outbox: OutboxEventRepository

    @Autowired
    lateinit var jobs: JobStateRepository

    @Autowired
    lateinit var publisher: OperationalMetricsPublisher

    @Autowired
    lateinit var operationalMetrics: OperationalMetricsPort

    @Autowired
    lateinit var operationalAlertChannel: OperationalAlertChannel

    @Autowired
    lateinit var operationalAlertProperties: OperationalAlertProperties

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var clock: Clock

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun setUp() {
        clearTables()
    }

    @AfterEach
    fun tearDown() {
        clearTables()
        publisher.refresh()
    }

    @Test
    fun `PostgreSQL 운영 신호를 actuator metrics endpoint에서 조회한다`() {
        inbox.insertIfAbsent(
            WebhookNotification(
                notificationId = "notification-metrics",
                eventType = "transaction.created",
                vendorTransactionId = "tx-metrics",
                payload = "{}",
                payloadHash = "a".repeat(64),
                signature = "signature",
                receivedAt = "20260817010000",
            ),
        )
        outbox.insertAll(
            listOf(
                OutboxEvent(
                    eventId = UuidV7Generator(clock).nextId(),
                    eventDate = "20260817",
                    vendorTransactionId = "tx-metrics",
                    eventType = OutboxEventType.CHECKING,
                    topic = "deposit-events",
                    payload = "{}",
                    maxRetryCount = 5,
                ),
            ),
        )
        jobs.markStarted("tx-reconciliation", "20260817010400")
        jobs.markSucceeded("tx-reconciliation", "20260817010500")

        publisher.refresh()

        assertMetric("bcm.webhook.inbox.pending", 1.0)
        assertMetric("bcm.outbox.pending", 1.0)
        assertTaggedMetric("bcm.job.last.success.timestamp.seconds", "job", "tx-reconciliation", 1_786_928_700.0)
        mockMvc.perform(get("/actuator/metrics")).andExpect(status().isNotFound)
    }

    @Test
    fun `수신과 벤더 호출 및 대사 누락 신호를 actuator metrics endpoint에서 조회한다`() {
        operationalMetrics.recordWebhookIngestion(WebhookIngestionMetricOutcome.ACCEPTED, "20260817010900")
        operationalMetrics.recordWebhookIngestion(WebhookIngestionMetricOutcome.INVALID_SIGNATURE, null)
        operationalMetrics.recordWebhookIngestion(WebhookIngestionMetricOutcome.ERROR, null)
        operationalMetrics.recordVendorCall("transactions", VendorCallMetricOutcome.SUCCESS)
        operationalMetrics.recordVendorCall("transactions", VendorCallMetricOutcome.RATE_LIMITED)
        operationalMetrics.recordVendorCall("transactions", VendorCallMetricOutcome.ERROR)
        operationalMetrics.recordReconciliation(2)

        assertMetric("bcm.webhook.last.received.timestamp.seconds", 1_786_928_940.0)
        assertTaggedMetric("bcm.webhook.ingestion", "outcome", "accepted", 1.0)
        assertTaggedMetric("bcm.webhook.ingestion", "outcome", "invalid_signature", 1.0)
        assertTaggedMetric("bcm.webhook.ingestion", "outcome", "error", 1.0)
        assertTaggedMetric("bcm.vendor.calls", "outcome", "rate_limited", 1.0)
        assertMetric("bcm.tx.reconciliation.missing", 2.0)
        assertMetric("bcm.tx.reconciliation.recovered", 2.0)
    }

    @Test
    fun `자동 추적 중단과 미보관 완료 원문 잔량을 actuator metrics endpoint에서 조회한다`() {
        insertTransaction("tx-stopped", "CONFIRMED", "20260801000000", "20260817000000")
        jdbc.update("UPDATE bcm_tx_l SET rcnc_stop_dttm = '20260808000000' WHERE vndr_tx_id = 'tx-stopped'")
        insertTransaction("tx-finalized", "FINALIZED", "20260816000000", "20260816010000")
        inbox.insertIfAbsent(
            WebhookNotification(
                notificationId = "completed-unarchived",
                eventType = "transaction.status.updated",
                vendorTransactionId = "tx-finalized",
                payload = """{"data":{"status":"COMPLETED"}}""",
                payloadHash = "a".repeat(64),
                signature = "signature",
                receivedAt = "20260816010000",
            ),
        )
        inbox.markProcessed("completed-unarchived", "20260816010100")

        publisher.refresh()

        assertMetric("bcm.tx.reconciliation.stopped", 1.0)
        assertMetric("bcm.webhook.completed.unarchived", 1.0)
    }

    @Test
    fun `API 애플리케이션은 운영 경보 채널을 기본 비활성으로 조립한다`() {
        assertThat(operationalAlertChannel).isNotNull
        assertThat(operationalAlertProperties.enabled).isFalse()
        assertThat(operationalAlertProperties.endpoint).isEmpty()
        assertThat(operationalAlertProperties.bearerToken).isEmpty()
    }

    private fun assertMetric(
        name: String,
        value: Double,
    ) {
        assertThat(metricValue(name)).isEqualTo(value)
    }

    private fun assertTaggedMetric(
        name: String,
        tagKey: String,
        tagValue: String,
        value: Double,
    ) {
        assertThat(metricValue(name, "$tagKey:$tagValue")).isEqualTo(value)
    }

    private fun metricValue(
        name: String,
        tag: String? = null,
    ): Double {
        val query = tag?.let { "?tag=$it" }.orEmpty()
        val response =
            HTTP_CLIENT.send(
                HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:$managementPort/actuator/metrics/$name$query"))
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertThat(response.statusCode()).isEqualTo(200)
        return objectMapper
            .readTree(response.body())
            .path("measurements")
            .path(0)
            .path("value")
            .doubleValue()
    }

    private fun clearTables() {
        jdbc.update("DELETE FROM bcm_outbox_l")
        jdbc.update("DELETE FROM bcm_whk_l")
        jdbc.update("DELETE FROM bcm_job_m")
        jdbc.update("DELETE FROM bcm_tx_l")
    }

    private fun insertTransaction(
        vendorTransactionId: String,
        status: String,
        firstDetectedAt: String,
        lastChangedAt: String,
    ) {
        jdbc.update(
            """
            INSERT INTO bcm_tx_l
              (vndr_tx_id, actv_tx_id, ext_tx_id, acnt_id, ntwk_cd, tkn_smbl, tx_hash,
               last_pub_stcd, cnfm_cnt, vndr_sub_stcd, vndr_ntwk_stcd, stall_alrt_dttm,
               vndr_crt_dttm, frst_dtct_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, NULL, 'account-1', 'ETHEREUM', 'USDC', NULL,
                    ?, 0, NULL, NULL, NULL, ?, ?, ?,
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            vendorTransactionId,
            vendorTransactionId,
            status,
            firstDetectedAt,
            firstDetectedAt,
            lastChangedAt,
        )
    }

    private companion object {
        val HTTP_CLIENT: HttpClient = HttpClient.newHttpClient()
    }
}
