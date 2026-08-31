package com.whatto.bcm.infra.persistence.webhook

import com.whatto.bcm.domain.webhook.WebhookInboxItem
import com.whatto.bcm.domain.webhook.WebhookNotification
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DataJdbcTest
@Import(WebhookInboxJdbcAdapter::class)
class WebhookInboxPersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var inbox: WebhookInboxJdbcAdapter

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `성공 처리는 S와 처리시각 및 vendor COMPLETED 표식을 함께 기록한다`() {
        inbox.insertIfAbsent(
            notification(
                "noti-success",
                "20260807120000",
                payload = """{"data":{"status":"COMPLETED"}}""",
            ),
        )

        inbox.markProcessed("noti-success", "20260807120100")

        val row = jdbc.queryForMap("SELECT * FROM bcm_whk_l WHERE noti_id = 'noti-success'")
        assertThat(row["prcs_stcd"]).isEqualTo("S")
        assertThat(row["prcs_dttm"]).isEqualTo("20260807120100")
        assertThat(row["vndr_cmpl_yn"]).isEqualTo("Y")
        assertThat(row["err_msg"]).isNull()
    }

    @Test
    fun `미지원 event의 파싱 불가 원문도 완료 원본으로 표식하지 않고 처리한다`() {
        inbox.insertIfAbsent(
            notification(
                "noti-unsupported",
                "20260807120000",
                eventType = "unsupported.event",
                payload = "not-json",
            ),
        )

        inbox.markProcessed("noti-unsupported", "20260807120100")

        val row = jdbc.queryForMap("SELECT * FROM bcm_whk_l WHERE noti_id = 'noti-unsupported'")
        assertThat(row["prcs_stcd"]).isEqualTo("S")
        assertThat(row["vndr_cmpl_yn"]).isEqualTo("N")
    }

    @Test
    fun `실패는 횟수와 안전한 사유를 남기고 상한에 닿으면 F로 격리한다`() {
        inbox.insertIfAbsent(notification("noti-poison", "20260807120000"))

        val first = inbox.recordFailure("noti-poison", "missing data.assetId", 2)
        val second = inbox.recordFailure("noti-poison", "missing data.assetId", 2)

        assertThat(first.retryCount).isEqualTo(1)
        assertThat(first.quarantined).isFalse()
        assertThat(second.retryCount).isEqualTo(2)
        assertThat(second.quarantined).isTrue()
        assertThat(jdbc.queryForMap("SELECT * FROM bcm_whk_l WHERE noti_id = 'noti-poison'")["prcs_stcd"])
            .isEqualTo("F")
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `SKIP LOCKED 집기는 다른 워커가 잠근 가장 오래된 행을 건너뛴다`() {
        inbox.insertIfAbsent(notification("noti-old", "20260807120000"))
        inbox.insertIfAbsent(notification("noti-new", "20260807120100"))
        val firstLocked = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val transaction =
            TransactionTemplate(transactionManager).apply {
                propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            }

        try {
            val first =
                executor.submit<WebhookInboxItem?> {
                    transaction.execute<WebhookInboxItem?> {
                        inbox.findNextPendingForUpdate().also {
                            firstLocked.countDown()
                            check(releaseFirst.await(5, TimeUnit.SECONDS))
                        }
                    }
                }
            assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue()

            val second = transaction.execute { inbox.findNextPendingForUpdate() }

            assertThat(second?.notificationId).isEqualTo("noti-new")
            releaseFirst.countDown()
            assertThat(first.get(5, TimeUnit.SECONDS)?.notificationId).isEqualTo("noti-old")
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
            jdbc.update("DELETE FROM bcm_whk_l WHERE noti_id IN ('noti-old', 'noti-new')")
        }
    }

    private fun notification(
        id: String,
        receivedAt: String,
        eventType: String = "transaction.created",
        payload: String = """{"data":{"status":"CONFIRMING"}}""",
    ) = WebhookNotification(
        notificationId = id,
        eventType = eventType,
        vendorTransactionId = "tx-$id",
        payload = payload,
        payloadHash = "a".repeat(64),
        signature = "verified-signature",
        receivedAt = receivedAt,
    )
}
