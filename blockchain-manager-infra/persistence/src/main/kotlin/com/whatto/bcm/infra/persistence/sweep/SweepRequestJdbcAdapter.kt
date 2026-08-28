package com.whatto.bcm.infra.persistence.sweep

import com.whatto.bcm.domain.sweep.SweepRequest
import com.whatto.bcm.domain.sweep.SweepRequestAcceptance
import com.whatto.bcm.domain.sweep.SweepRequestItem
import com.whatto.bcm.domain.sweep.SweepRequestItemStatus
import com.whatto.bcm.domain.sweep.SweepRequestRepository
import com.whatto.bcm.domain.sweep.SweepRequestStatus
import com.whatto.bcm.support.audit.SystemAudit
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class SweepRequestJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : SweepRequestRepository {
    override fun findByExternalRequestId(externalSweepRequestId: String): SweepRequest? =
        find("ext_swp_req_id = :id", mapOf("id" to externalSweepRequestId))

    override fun accept(request: SweepRequest): SweepRequestAcceptance {
        lockExternalRequest(request.externalSweepRequestId)
        findByExternalRequestId(request.externalSweepRequestId)?.let { existing ->
            return if (existing.requestHash == request.requestHash) {
                SweepRequestAcceptance.Existing(existing)
            } else {
                SweepRequestAcceptance.HashConflict
            }
        }

        val sourceIds = request.items.flatMap { it.sourceEventIds }.sorted()
        lockSourceEvents(sourceIds)
        val events = findSourceEventsForUpdate(sourceIds).associateBy { it.eventId }
        sourceIds.forEach { eventId ->
            val event = events[eventId] ?: return SweepRequestAcceptance.SourceEventNotFound(eventId)
            if (!event.matchesRequest(request)) return SweepRequestAcceptance.SourceEventInvalid(eventId)
            if (!event.completed) return SweepRequestAcceptance.SourceEventNotCompleted(eventId)
            if (event.consumed) return SweepRequestAcceptance.SourceEventConsumed(eventId)
        }

        insertRequest(request)
        request.items.forEach { item ->
            insertItem(request, item)
            item.sourceEventIds.forEach { eventId -> insertSource(item.sweepItemId, eventId) }
            insertTarget(request, item)
        }
        return SweepRequestAcceptance.Created(request)
    }

    private fun lockExternalRequest(externalRequestId: String) {
        jdbc.queryForObject(
            "SELECT pg_advisory_xact_lock(hashtextextended(:key, 0)) IS NULL",
            mapOf("key" to "BCM:SWEEP_REQUEST:$externalRequestId"),
            Boolean::class.java,
        )
    }

    private fun lockSourceEvents(eventIds: List<String>) {
        eventIds.forEach { eventId ->
            jdbc.queryForObject(
                "SELECT pg_advisory_xact_lock(hashtextextended(:key, 0)) IS NULL",
                mapOf("key" to "BCM:SWEEP_SOURCE_EVENT:$eventId"),
                Boolean::class.java,
            )
        }
    }

    private fun findSourceEventsForUpdate(eventIds: List<String>): List<SourceEventRow> =
        jdbc.query(
            """
            SELECT o.evnt_id, o.topic, o.evnt_stcd,
                   o.payload->>'type' AS event_type,
                   o.payload->>'status' AS event_status,
                   o.payload->>'accountId' AS account_id,
                   o.payload->>'network' AS network,
                   o.payload->>'symbol' AS symbol,
                   EXISTS (
                     SELECT 1 FROM bcm_evnt_cmpl_l c
                     WHERE c.evnt_id = o.evnt_id AND c.cnsmr_dvcd = 'DAW_CORE'
                   ) AS completed,
                   EXISTS (
                     SELECT 1 FROM bcm_swp_req_src_l s WHERE s.evnt_id = o.evnt_id
                   ) AS consumed
            FROM bcm_outbox_l o
            WHERE o.evnt_id IN (:eventIds)
            ORDER BY o.evnt_id
            FOR UPDATE OF o
            """.trimIndent(),
            mapOf("eventIds" to eventIds),
        ) { rs, _ ->
            SourceEventRow(
                eventId = rs.getString("evnt_id"),
                topic = rs.getString("topic"),
                outboxStatus = rs.getString("evnt_stcd"),
                eventType = rs.getString("event_type"),
                eventStatus = rs.getString("event_status"),
                accountId = rs.getString("account_id"),
                network = rs.getString("network"),
                symbol = rs.getString("symbol"),
                completed = rs.getBoolean("completed"),
                consumed = rs.getBoolean("consumed"),
            )
        }

    private fun insertRequest(request: SweepRequest) {
        jdbc.update(
            """
            INSERT INTO bcm_swp_req_l
              (swp_req_id, ext_swp_req_id, req_hash, ntwk_cd, tkn_smbl, swp_req_stcd,
               item_cnt, req_dttm, fnsh_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (:requestId, :externalId, :requestHash, :network, :symbol, :status,
               :itemCount, :requestedAt, :finishedAt,
               :employeeNo, :branchCode, :employeeNo, :branchCode)
            """.trimIndent(),
            auditParams() +
                mapOf(
                    "requestId" to request.sweepRequestId,
                    "externalId" to request.externalSweepRequestId,
                    "requestHash" to request.requestHash,
                    "network" to request.network,
                    "symbol" to request.symbol,
                    "status" to request.status.name,
                    "itemCount" to request.items.size,
                    "requestedAt" to request.requestedAt,
                    "finishedAt" to request.finishedAt,
                ),
        )
    }

    private fun insertItem(
        request: SweepRequest,
        item: SweepRequestItem,
    ) {
        jdbc.update(
            """
            INSERT INTO bcm_swp_req_item_l
              (swp_req_item_id, swp_req_id, item_seq, acnt_id, swp_req_item_stcd, last_fail_cd,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (:itemId, :requestId, :sequence, :accountId, :status, :failureCode,
               :employeeNo, :branchCode, :employeeNo, :branchCode)
            """.trimIndent(),
            auditParams() +
                mapOf(
                    "itemId" to item.sweepItemId,
                    "requestId" to request.sweepRequestId,
                    "sequence" to item.sequence,
                    "accountId" to item.accountId,
                    "status" to item.status.name,
                    "failureCode" to item.lastFailureCode,
                ),
        )
    }

    private fun insertSource(
        itemId: String,
        eventId: String,
    ) {
        jdbc.update(
            """
            INSERT INTO bcm_swp_req_src_l
              (evnt_id, swp_req_item_id, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (:eventId, :itemId, :employeeNo, :branchCode, :employeeNo, :branchCode)
            """.trimIndent(),
            auditParams() + mapOf("eventId" to eventId, "itemId" to itemId),
        )
    }

    private fun insertTarget(
        request: SweepRequest,
        item: SweepRequestItem,
    ) {
        jdbc.update(
            """
            INSERT INTO bcm_swp_trgt
              (acnt_id, ntwk_cd, tkn_smbl, reg_dttm, actv_swp_exec_id, actv_item_seq,
               try_cnt, last_try_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (:accountId, :network, :symbol, :registeredAt, NULL, NULL,
               0, NULL, :employeeNo, :branchCode, :employeeNo, :branchCode)
            ON CONFLICT (acnt_id, ntwk_cd, tkn_smbl) DO NOTHING
            """.trimIndent(),
            auditParams() +
                mapOf(
                    "accountId" to item.accountId,
                    "network" to request.network,
                    "symbol" to request.symbol,
                    "registeredAt" to request.requestedAt,
                ),
        )
    }

    private fun find(
        predicate: String,
        params: Map<String, Any>,
    ): SweepRequest? {
        val header =
            jdbc
                .query(
                    """
                    SELECT swp_req_id, ext_swp_req_id, req_hash, ntwk_cd, tkn_smbl,
                           swp_req_stcd, req_dttm, fnsh_dttm
                    FROM bcm_swp_req_l
                    WHERE $predicate
                    """.trimIndent(),
                    params,
                ) { rs, _ ->
                    RequestHeader(
                        rs.getString("swp_req_id"),
                        rs.getString("ext_swp_req_id"),
                        rs.getString("req_hash").trim(),
                        rs.getString("ntwk_cd"),
                        rs.getString("tkn_smbl"),
                        SweepRequestStatus.valueOf(rs.getString("swp_req_stcd")),
                        rs.getString("req_dttm"),
                        rs.getString("fnsh_dttm"),
                    )
                }.firstOrNull() ?: return null
        val items =
            jdbc
                .query(
                    """
                    SELECT i.swp_req_item_id, i.item_seq, i.acnt_id, i.swp_req_item_stcd, i.last_fail_cd,
                           s.evnt_id
                    FROM bcm_swp_req_item_l i
                    LEFT JOIN bcm_swp_req_src_l s ON s.swp_req_item_id = i.swp_req_item_id
                    WHERE i.swp_req_id = :requestId
                    ORDER BY i.item_seq, s.evnt_id
                    """.trimIndent(),
                    mapOf("requestId" to header.requestId),
                ) { rs, _ ->
                    ItemRow(
                        rs.getString("swp_req_item_id"),
                        rs.getInt("item_seq"),
                        rs.getString("acnt_id"),
                        SweepRequestItemStatus.valueOf(rs.getString("swp_req_item_stcd")),
                        rs.getString("last_fail_cd"),
                        rs.getString("evnt_id"),
                    )
                }.groupBy { it.itemId }
                .values
                .map { rows ->
                    val row = rows.first()
                    SweepRequestItem(
                        row.itemId,
                        row.sequence,
                        row.accountId,
                        row.status,
                        rows.mapNotNull { it.eventId },
                        row.failureCode,
                    )
                }
        return SweepRequest(
            header.requestId,
            header.externalId,
            header.requestHash,
            header.network,
            header.symbol,
            header.status,
            header.requestedAt,
            header.finishedAt,
            items,
        )
    }

    private fun auditParams(): Map<String, Any> = mapOf("employeeNo" to SystemAudit.EMPNO, "branchCode" to SystemAudit.BRCD)

    private data class SourceEventRow(
        val eventId: String,
        val topic: String,
        val outboxStatus: String,
        val eventType: String?,
        val eventStatus: String?,
        val accountId: String?,
        val network: String?,
        val symbol: String?,
        val completed: Boolean,
        val consumed: Boolean,
    ) {
        fun matchesRequest(request: SweepRequest): Boolean {
            val expectedAccount =
                request.items.singleOrNull { eventId in it.sourceEventIds }?.accountId
                    ?: return false
            return topic == "deposit-events" &&
                outboxStatus == "S" &&
                eventType == "DEPOSIT" &&
                eventStatus == "FINALIZED" &&
                accountId == expectedAccount &&
                network == request.network &&
                symbol == request.symbol
        }
    }

    private data class RequestHeader(
        val requestId: String,
        val externalId: String,
        val requestHash: String,
        val network: String,
        val symbol: String,
        val status: SweepRequestStatus,
        val requestedAt: String,
        val finishedAt: String?,
    )

    private data class ItemRow(
        val itemId: String,
        val sequence: Int,
        val accountId: String,
        val status: SweepRequestItemStatus,
        val failureCode: String?,
        val eventId: String?,
    )
}
