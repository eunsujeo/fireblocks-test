package com.whatto.bcm.infra.persistence.event

import com.whatto.bcm.domain.event.OutboxEvent
import com.whatto.bcm.domain.event.OutboxEventRepository
import com.whatto.bcm.domain.event.OutboxFailureResult
import com.whatto.bcm.domain.event.PendingOutboxEvent
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.support.audit.SystemAudit
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class OutboxJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : OutboxEventRepository {
    override fun insertAll(events: List<OutboxEvent>) {
        events.forEach(::insert)
    }

    override fun tryAcquireRelayLock(): Boolean =
        jdbc.queryForObject(
            "SELECT pg_try_advisory_xact_lock(:lockKey)",
            mapOf("lockKey" to RELAY_LOCK_KEY),
            Boolean::class.java,
        ) == true

    override fun findNextPendingForUpdate(): PendingOutboxEvent? =
        jdbc
            .query(
                """
                SELECT candidate.evnt_id, candidate.topic, candidate.payload::text AS payload
                FROM bcm_outbox_l candidate
                WHERE candidate.evnt_stcd = 'P'
                  AND NOT EXISTS (
                    SELECT 1
                    FROM bcm_outbox_l preceding
                    WHERE preceding.vndr_tx_id = candidate.vndr_tx_id
                      AND preceding.evnt_id < candidate.evnt_id
                      AND preceding.evnt_stcd = 'F'
                  )
                ORDER BY candidate.evnt_id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """.trimIndent(),
                emptyMap<String, Any>(),
            ) { rs, _ ->
                PendingOutboxEvent(
                    eventId = rs.getString("evnt_id"),
                    topic = rs.getString("topic"),
                    payload = rs.getString("payload"),
                )
            }.firstOrNull()

    override fun markDispatched(
        eventId: String,
        publishedAt: String,
    ) {
        val updated =
            jdbc.update(
                """
                UPDATE bcm_outbox_l
                SET evnt_stcd = 'D',
                    pub_dttm = COALESCE(pub_dttm, :publishedAt),
                    last_chng_empno = :employeeNo,
                    last_chng_brcd = :branchCode
                WHERE evnt_id = :eventId AND evnt_stcd = 'P'
                """.trimIndent(),
                systemParameters(eventId) + ("publishedAt" to publishedAt),
            )
        check(updated == 1) { "pending outbox event not found: eventId=$eventId" }
    }

    override fun markSuccess(eventId: String) {
        val updated =
            jdbc.update(
                """
                UPDATE bcm_outbox_l
                SET evnt_stcd = 'S',
                    err_msg = NULL,
                    last_chng_empno = :employeeNo,
                    last_chng_brcd = :branchCode
                WHERE evnt_id = :eventId AND evnt_stcd = 'D'
                """.trimIndent(),
                systemParameters(eventId),
            )
        check(updated == 1) { "dispatched outbox event not found: eventId=$eventId" }
    }

    override fun recordFailure(
        eventId: String,
        safeReason: String,
        attemptedAt: String,
    ): OutboxFailureResult =
        checkNotNull(
            jdbc.queryForObject(
                """
                UPDATE bcm_outbox_l
                SET rtry_cnt = rtry_cnt + 1,
                    evnt_stcd = CASE WHEN rtry_cnt + 1 >= max_rtry_cnt THEN 'F' ELSE 'P' END,
                    last_rtry_dttm = :attemptedAt,
                    err_msg = LEFT(:safeReason, 1000),
                    last_chng_empno = :employeeNo,
                    last_chng_brcd = :branchCode
                WHERE evnt_id = :eventId AND evnt_stcd IN ('P', 'D')
                RETURNING rtry_cnt, evnt_stcd
                """.trimIndent(),
                systemParameters(eventId) +
                    mapOf(
                        "safeReason" to safeReason,
                        "attemptedAt" to attemptedAt,
                    ),
            ) { rs, _ ->
                OutboxFailureResult(
                    retryCount = rs.getInt("rtry_cnt"),
                    quarantined = rs.getString("evnt_stcd") == "F",
                )
            },
        ) { "publishable outbox event not found: eventId=$eventId" }

    private fun insert(event: OutboxEvent) {
        try {
            jdbc.update(
                """
                INSERT INTO bcm_outbox_l
                  (evnt_id, evnt_dt, vndr_tx_id, agg_typ_dvcd, evt_typ_dvcd, topic, payload,
                   evnt_stcd, rtry_cnt, max_rtry_cnt, orgn_id, trace_id,
                   pub_dttm, last_rtry_dttm, err_msg,
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES
                  (:eventId, :eventDate, :vendorTransactionId, 'TX', :eventType, :topic, CAST(:payload AS jsonb),
                   'P', 0, :maxRetryCount, :originEventId, :traceId,
                   NULL, NULL, NULL,
                   :employeeNo, :branchCode, :employeeNo, :branchCode)
                """.trimIndent(),
                mapOf(
                    "eventId" to event.eventId,
                    "eventDate" to event.eventDate,
                    "vendorTransactionId" to event.vendorTransactionId,
                    "eventType" to event.eventType.code,
                    "topic" to event.topic,
                    "payload" to event.payload,
                    "maxRetryCount" to event.maxRetryCount,
                    "originEventId" to event.originEventId,
                    "traceId" to event.traceId,
                    "employeeNo" to SystemAudit.EMPNO,
                    "branchCode" to SystemAudit.BRCD,
                ),
            )
        } catch (exception: DuplicateKeyException) {
            throw ConflictException("outboxEvent", event.eventId, exception)
        }
    }

    private fun systemParameters(eventId: String): Map<String, Any> =
        mapOf(
            "eventId" to eventId,
            "employeeNo" to SystemAudit.EMPNO,
            "branchCode" to SystemAudit.BRCD,
        )

    private companion object {
        // ASCII "BCMRELAY". pg advisory lock namespace에서 outbox relay 전역 직렬화에만 사용한다.
        const val RELAY_LOCK_KEY = 0x42434D52454C4159L
    }
}
