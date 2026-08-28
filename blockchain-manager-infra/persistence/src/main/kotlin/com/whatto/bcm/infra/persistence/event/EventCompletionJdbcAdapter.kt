package com.whatto.bcm.infra.persistence.event

import com.whatto.bcm.domain.event.EventCompletion
import com.whatto.bcm.domain.event.EventCompletionRepository
import com.whatto.bcm.domain.event.EventCompletionResult
import com.whatto.bcm.support.audit.SystemAudit
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class EventCompletionJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : EventCompletionRepository {
    override fun complete(
        eventId: String,
        consumer: String,
        completedAt: String,
    ): EventCompletionResult {
        val event = findForUpdate(eventId) ?: return EventCompletionResult.NotFound
        if (event.status != SUCCESS) return EventCompletionResult.NotPublished
        if (consumer != DAW_CORE || event.topic !in DAW_TOPICS) return EventCompletionResult.NotConsumable

        val inserted =
            jdbc.update(
                """
                INSERT INTO bcm_evnt_cmpl_l
                  (evnt_id, cnsmr_dvcd, cmpl_dttm,
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES
                  (:eventId, :consumer, :completedAt,
                   :employeeNo, :branchCode, :employeeNo, :branchCode)
                ON CONFLICT (evnt_id, cnsmr_dvcd) DO NOTHING
                """.trimIndent(),
                mapOf(
                    "eventId" to eventId,
                    "consumer" to consumer,
                    "completedAt" to completedAt,
                    "employeeNo" to SystemAudit.EMPNO,
                    "branchCode" to SystemAudit.BRCD,
                ),
            )
        val completion = findCompletion(event, consumer)
        return if (inserted == 1) {
            EventCompletionResult.Completed(completion)
        } else {
            EventCompletionResult.AlreadyCompleted(completion)
        }
    }

    private fun findForUpdate(eventId: String): PublishedEvent? =
        jdbc
            .query(
                """
                SELECT evnt_id, topic, evnt_stcd,
                       payload->>'txId' AS tx_id,
                       COALESCE(payload->>'status', payload->>'chainStatus') AS event_status,
                       payload->>'sweepRequestId' AS swp_req_id,
                       payload->>'sweepItemId' AS swp_item_id,
                       payload->>'executionId' AS swp_exec_id
                FROM bcm_outbox_l
                WHERE evnt_id = :eventId
                FOR UPDATE
                """.trimIndent(),
                mapOf("eventId" to eventId),
            ) { rs, _ ->
                PublishedEvent(
                    eventId = rs.getString("evnt_id"),
                    topic = rs.getString("topic"),
                    status = rs.getString("evnt_stcd"),
                    transactionId = rs.getString("tx_id"),
                    eventStatus = rs.getString("event_status"),
                    sweepRequestId = rs.getString("swp_req_id"),
                    sweepItemId = rs.getString("swp_item_id"),
                    executionId = rs.getString("swp_exec_id"),
                )
            }.firstOrNull()

    private fun findCompletion(
        event: PublishedEvent,
        consumer: String,
    ): EventCompletion =
        checkNotNull(
            jdbc.queryForObject(
                """
                SELECT cmpl_dttm
                FROM bcm_evnt_cmpl_l
                WHERE evnt_id = :eventId AND cnsmr_dvcd = :consumer
                """.trimIndent(),
                mapOf("eventId" to event.eventId, "consumer" to consumer),
            ) { rs, _ ->
                EventCompletion(
                    eventId = event.eventId,
                    consumer = consumer,
                    completedAt = rs.getString("cmpl_dttm"),
                    transactionId = event.transactionId,
                    status = event.eventStatus,
                    sweepRequestId = event.sweepRequestId,
                    sweepItemId = event.sweepItemId,
                    executionId = event.executionId,
                )
            },
        ) { "event completion insert was not visible: eventId=${event.eventId}" }

    private data class PublishedEvent(
        val eventId: String,
        val topic: String,
        val status: String,
        val transactionId: String?,
        val eventStatus: String?,
        val sweepRequestId: String?,
        val sweepItemId: String?,
        val executionId: String?,
    )

    private companion object {
        const val SUCCESS = "S"
        const val DAW_CORE = "DAW_CORE"
        val DAW_TOPICS = setOf("deposit-events", "withdrawal-events", "internal-events", "sweep-events")
    }
}
