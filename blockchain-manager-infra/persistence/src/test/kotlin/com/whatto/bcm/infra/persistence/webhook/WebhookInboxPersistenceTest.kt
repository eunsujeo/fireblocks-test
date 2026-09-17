package com.whatto.bcm.infra.persistence.webhook

import com.whatto.bcm.domain.webhook.WebhookInboxItem
import com.whatto.bcm.domain.webhook.WebhookNotification
import com.whatto.bcm.domain.webhook.WebhookRetryBackoff
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
    /** 대기 시각이 지난 행만 집으므로, 조회 기준 시각은 항상 지난 값을 쓴다(03 V29). */
    private val pickAt = "29991231235959"

    @Autowired
    lateinit var inbox: WebhookInboxJdbcAdapter

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `대기는 시도마다 두 배로 늘고 도메인 규칙과 같은 값을 쓴다`() {
        // 대기 계산이 SQL에 있으므로 도메인 규칙(WebhookRetryBackoff)과 어긋나지 않는지 고정한다.
        inbox.insertIfAbsent(notification("noti-backoff-grow", "20260917085900"))
        val base = 30L

        inbox.recordFailure("noti-backoff-grow", "transient", 5, "20260917090000", base)
        assertThat(nextAttemptOf("noti-backoff-grow"))
            .isEqualTo("20260917" + "0900" + "%02d".format(WebhookRetryBackoff.delaySeconds(1, base)))

        inbox.recordFailure("noti-backoff-grow", "transient", 5, "20260917090000", base)
        assertThat(nextAttemptOf("noti-backoff-grow"))
            .isEqualTo("20260917090" + "1" + "00")
        assertThat(WebhookRetryBackoff.delaySeconds(2, base)).isEqualTo(60)
    }

    @Test
    fun `대기는 상한에서 멈추고 큰 기준 대기에서도 넘치지 않는다`() {
        // bigint로 먼저 곱하면 상한(LEAST)에 닿기 전에 overflow가 난다 — numeric으로 계산해야 한다.
        inbox.insertIfAbsent(notification("noti-backoff-cap", "20260917085900"))

        // 기준 대기가 상한을 훌쩍 넘어도 상한으로 끊긴다.
        inbox.recordFailure("noti-backoff-cap", "transient", 99, "20260917090000", WebhookRetryBackoff.MAX_SECONDS * 10)
        assertThat(nextAttemptOf("noti-backoff-cap")).isEqualTo("20260917091000")

        // 시도가 아주 많이 쌓여 2^n이 커져도 같은 상한을 돌려준다.
        jdbc.update("UPDATE bcm_whk_l SET rtry_cnt = 80 WHERE noti_id = ?", "noti-backoff-cap")
        inbox.recordFailure("noti-backoff-cap", "transient", 999, "20260917090000", 30)
        assertThat(nextAttemptOf("noti-backoff-cap")).isEqualTo("20260917091000")
        assertThat(WebhookRetryBackoff.delaySeconds(81, 30)).isEqualTo(WebhookRetryBackoff.MAX_SECONDS)

        // rtry_cnt 는 INT 라 이론상 이만큼 커질 수 있다 — 지수를 제한하지 않으면 2^n 계산 자체가 numeric 범위를 넘는다.
        // 상한에 닿지 않도록 maxAttempts 를 더 크게 두어 격리가 아니라 대기 계산 경로를 타게 한다.
        val huge = 2_000_000_000
        jdbc.update("UPDATE bcm_whk_l SET rtry_cnt = ? WHERE noti_id = ?", huge, "noti-backoff-cap")
        inbox.recordFailure("noti-backoff-cap", "transient", Int.MAX_VALUE, "20260917090000", 1)
        assertThat(nextAttemptOf("noti-backoff-cap")).isEqualTo("20260917091000")
        assertThat(WebhookRetryBackoff.delaySeconds(huge + 1, 1)).isEqualTo(WebhookRetryBackoff.MAX_SECONDS)
    }

    @Test
    fun `격리되는 시도에는 대기 시각을 두지 않는다`() {
        // 재시도가 결과를 바꾸지 못하는 행에 대기 시각을 남기면 의미 없이 조회 조건만 복잡해진다.
        inbox.insertIfAbsent(notification("noti-backoff-final", "20260917085900"))

        val result = inbox.recordFailure("noti-backoff-final", "transient", 1, "20260917090000", 30)

        assertThat(result.quarantined).isTrue()
        assertThat(nextAttemptOf("noti-backoff-final")).isNull()
    }

    @Test
    fun `대기 시각이 지나지 않은 행은 집지 않는다`() {
        // backoff가 있어도 워커가 그 시각을 무시하면 무의미하다 — 조회 조건이 그 값을 봐야 한다(03 V29).
        inbox.insertIfAbsent(notification("noti-backoff", "20260917085900"))
        inbox.recordFailure("noti-backoff", "transient", 5, "20301231235900", baseSeconds = 30)

        assertThat(inbox.findNextPendingForUpdate("20260917090000")).isNull()
        assertThat(inbox.findNextPendingForUpdate("20310101000000")?.notificationId).isEqualTo("noti-backoff")
    }

    @Test
    fun `성공 처리는 S와 처리시각 및 vendor COMPLETED 표식을 함께 기록한다`() {
        inbox.insertIfAbsent(
            notification(
                "noti-success",
                "20260807120000",
                payload = """{"data":{"status":"COMPLETED"}}""",
            ),
        )

        inbox.markProcessed("noti-success", "20260807120100", vendorCompleted = true)

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

        inbox.markProcessed("noti-unsupported", "20260807120100", vendorCompleted = false)

        val row = jdbc.queryForMap("SELECT * FROM bcm_whk_l WHERE noti_id = 'noti-unsupported'")
        assertThat(row["prcs_stcd"]).isEqualTo("S")
        assertThat(row["vndr_cmpl_yn"]).isEqualTo("N")
    }

    @Test
    fun `실패는 횟수와 안전한 사유를 남기고 상한에 닿으면 F로 격리한다`() {
        inbox.insertIfAbsent(notification("noti-poison", "20260807120000"))

        val first = inbox.recordFailure("noti-poison", "missing data.assetId", 2, pickAt, baseSeconds = 0)
        val second = inbox.recordFailure("noti-poison", "missing data.assetId", 2, pickAt, baseSeconds = 0)

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
                        inbox.findNextPendingForUpdate(pickAt).also {
                            firstLocked.countDown()
                            check(releaseFirst.await(5, TimeUnit.SECONDS))
                        }
                    }
                }
            assertThat(firstLocked.await(5, TimeUnit.SECONDS)).isTrue()

            val second = transaction.execute { inbox.findNextPendingForUpdate(pickAt) }

            assertThat(second?.notificationId).isEqualTo("noti-new")
            releaseFirst.countDown()
            assertThat(first.get(5, TimeUnit.SECONDS)?.notificationId).isEqualTo("noti-old")
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
            jdbc.update("DELETE FROM bcm_whk_l WHERE noti_id IN ('noti-old', 'noti-new')")
        }
    }

    private fun nextAttemptOf(notificationId: String): String? =
        jdbc.queryForObject(
            "SELECT next_attmpt_dttm FROM bcm_whk_l WHERE noti_id = ?",
            String::class.java,
            notificationId,
        )

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
