package com.whatto.bcm.infra.persistence.monitoring

import com.whatto.bcm.domain.monitoring.JobHeartbeat
import com.whatto.bcm.domain.monitoring.OperationalBacklog
import com.whatto.bcm.domain.monitoring.OperationalSignalRepository
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class OperationalSignalJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : OperationalSignalRepository {
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
                WHERE webhook.prcs_stcd = 'S'
                  AND webhook.vndr_tx_id IS NOT NULL
                  AND webhook.payload::json #>> '{data,status}' = 'COMPLETED'
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
