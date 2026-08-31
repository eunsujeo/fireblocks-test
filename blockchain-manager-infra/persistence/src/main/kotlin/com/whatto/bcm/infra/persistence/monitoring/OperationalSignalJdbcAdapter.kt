package com.whatto.bcm.infra.persistence.monitoring

import com.whatto.bcm.domain.monitoring.JobHeartbeat
import com.whatto.bcm.domain.monitoring.OperationalBacklog
import com.whatto.bcm.domain.monitoring.OperationalSignalRepository
import com.whatto.bcm.domain.monitoring.SweepOperationalSignals
import com.whatto.bcm.infra.persistence.webhook.COMPLETED_WEBHOOK_PREDICATE
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class OperationalSignalJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
    @param:Value("\${bcm.sweep.repeated-failure-alert-threshold:3}")
    private val repeatedFailureAlertThreshold: Int,
) : OperationalSignalRepository {
    init {
        require(repeatedFailureAlertThreshold > 0) { "sweep repeated failure alert threshold must be positive" }
    }

    override fun pendingWebhookBacklog(): OperationalBacklog =
        checkNotNull(
            jdbc.queryForObject(
                "SELECT COUNT(*) AS depth, MIN(rcv_dttm) AS oldest FROM bcm_whk_l WHERE prcs_stcd = 'P'",
                emptyMap<String, Any>(),
            ) { rs, _ -> OperationalBacklog(rs.getLong("depth"), rs.getString("oldest")) },
        )

    override fun pendingOutboxBacklog(): OperationalBacklog =
        checkNotNull(
            jdbc.queryForObject(
                "SELECT COUNT(*) AS depth, MIN(evnt_id) AS oldest FROM bcm_outbox_l WHERE evnt_stcd = 'P'",
                emptyMap<String, Any>(),
            ) { rs, _ ->
                OperationalBacklog(
                    count = rs.getLong("depth"),
                    oldestAt = rs.getString("oldest")?.let(::uuidV7Timestamp),
                )
            },
        )

    override fun stoppedReconciliationCount(): Long =
        checkNotNull(
            jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM bcm_tx_l
                WHERE last_pub_stcd IN ('SUBMITTED', 'CONFIRMED')
                  AND rcnc_stop_dttm IS NOT NULL
                """.trimIndent(),
                emptyMap<String, Any>(),
                Long::class.java,
            ),
        )

    override fun unarchivedCompletedWebhookCount(): Long =
        checkNotNull(
            jdbc.queryForObject(
                """
                SELECT COUNT(DISTINCT webhook.vndr_tx_id)
                FROM bcm_whk_l webhook
                JOIN bcm_tx_l transaction
                  ON transaction.actv_tx_id = webhook.vndr_tx_id
                 AND transaction.last_pub_stcd = 'FINALIZED'
                WHERE $COMPLETED_WEBHOOK_PREDICATE
                  AND NOT EXISTS (
                    SELECT 1
                    FROM bcm_raw_tx_l archived
                    WHERE archived.vndr_tx_id = webhook.vndr_tx_id
                      AND archived.rcv_dttm >= webhook.rcv_dttm
                  )
                """.trimIndent(),
                emptyMap<String, Any>(),
                Long::class.java,
            ),
        )

    override fun sweepOperationalSignals(): SweepOperationalSignals =
        checkNotNull(
            jdbc.queryForObject(
                """
                SELECT
                  (SELECT count(*) FROM bcm_swp_req_l WHERE swp_req_stcd IN ('ACCEPTED', 'BLOCKED', 'PROCESSING', 'PARTIAL')) AS pending_request_count,
                  (SELECT min(req_dttm) FROM bcm_swp_req_l WHERE swp_req_stcd IN ('ACCEPTED', 'BLOCKED', 'PROCESSING', 'PARTIAL')) AS oldest_request,
                  (SELECT count(*) FROM bcm_swp_req_l WHERE swp_req_stcd = 'BLOCKED') AS blocked_request_count,
                  (SELECT count(*) FROM bcm_swp_req_l WHERE swp_req_stcd = 'FAILED') AS failed_request_count,
                  (SELECT count(*) FROM bcm_swp_trgt WHERE try_cnt >= :repeatedFailureAlertThreshold) AS repeated_failure_target_count,
                  (SELECT count(*) FROM bcm_outbox_l WHERE topic = 'sweep-events' AND evnt_stcd = 'P') AS pending_event_count,
                  (SELECT count(*) FROM bcm_outbox_l WHERE topic = 'sweep-events' AND evnt_stcd = 'F') AS failed_event_count,
                  (SELECT count(*) FROM bcm_outbox_l outbox
                    WHERE outbox.topic = 'sweep-events' AND outbox.evnt_stcd = 'S'
                      AND NOT EXISTS (SELECT 1 FROM bcm_evnt_cmpl_l completion
                                       WHERE completion.evnt_id = outbox.evnt_id AND completion.cnsmr_dvcd = 'DAW_CORE')) AS awaiting_completion_count,
                  (SELECT outbox.pub_dttm FROM bcm_outbox_l outbox
                    WHERE outbox.topic = 'sweep-events' AND outbox.evnt_stcd = 'S'
                      AND outbox.pub_dttm IS NOT NULL
                      AND NOT EXISTS (SELECT 1 FROM bcm_evnt_cmpl_l completion
                                       WHERE completion.evnt_id = outbox.evnt_id AND completion.cnsmr_dvcd = 'DAW_CORE')
                    ORDER BY outbox.pub_dttm, outbox.evnt_id
                    LIMIT 1) AS oldest_completion
                """.trimIndent(),
                mapOf("repeatedFailureAlertThreshold" to repeatedFailureAlertThreshold),
            ) { rs, _ ->
                SweepOperationalSignals(
                    pendingRequestCount = rs.getLong("pending_request_count"),
                    oldestPendingRequestAt = rs.getString("oldest_request"),
                    blockedRequestCount = rs.getLong("blocked_request_count"),
                    failedRequestCount = rs.getLong("failed_request_count"),
                    repeatedFailureTargetCount = rs.getLong("repeated_failure_target_count"),
                    pendingEventCount = rs.getLong("pending_event_count"),
                    failedEventCount = rs.getLong("failed_event_count"),
                    awaitingCompletionCount = rs.getLong("awaiting_completion_count"),
                    oldestAwaitingCompletionAt = rs.getString("oldest_completion"),
                )
            },
        )

    override fun heartbeats(): List<JobHeartbeat> =
        jdbc.query(
            """
            SELECT job_nm, last_run_dttm, last_scs_dttm
            FROM bcm_job_m
            ORDER BY job_nm
            """.trimIndent(),
            emptyMap<String, Any>(),
        ) { rs, _ ->
            JobHeartbeat(
                jobName = rs.getString("job_nm"),
                lastRunAt = rs.getString("last_run_dttm"),
                lastSucceededAt = rs.getString("last_scs_dttm"),
            )
        }

    private fun uuidV7Timestamp(eventId: String): String {
        val timestampMillis = UUID.fromString(eventId).mostSignificantBits ushr 16
        return CoreDateTimes.fromEpochMillis(timestampMillis)
    }
}
