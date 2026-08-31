package com.whatto.bcm.infra.persistence.admin

import com.whatto.bcm.domain.admin.SweepExecutionInvestigation
import com.whatto.bcm.domain.admin.SweepLinkedEvent
import com.whatto.bcm.domain.admin.SweepOperationsOverview
import com.whatto.bcm.domain.admin.SweepRequestInvestigation
import com.whatto.bcm.domain.admin.SweepRequestInvestigationRepository
import com.whatto.bcm.domain.admin.SweepRequestItemInvestigation
import com.whatto.bcm.domain.admin.SweepRequestSummary
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset

@Repository
class AdminSweepRequestInvestigationJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : SweepRequestInvestigationRepository {
    override fun findByIdentifier(identifier: String): SweepRequestInvestigation? {
        val summary = findSummary(identifier) ?: return null
        val itemRows = findItems(summary.sweepRequestId)
        val items = itemRows.items
        val itemIds = items.map(ItemRow::sweepItemId)
        val sources = findEvents(itemIds, source = true)
        val results = findEvents(itemIds, source = false)
        val executions = findExecutions(itemIds)
        return SweepRequestInvestigation(
            summary = summary,
            items =
                items.map { item ->
                    SweepRequestItemInvestigation(
                        sweepItemId = item.sweepItemId,
                        sequence = item.sequence,
                        accountId = item.accountId,
                        status = item.status,
                        lastFailureCode = item.lastFailureCode,
                        retryable = item.status == "PENDING",
                        nextAction = itemNextAction(item.status),
                        sourceEvents = sources.items[item.sweepItemId].orEmpty(),
                        executions = executions.items[item.sweepItemId].orEmpty(),
                        resultEvents = results.items[item.sweepItemId].orEmpty(),
                    )
                },
            truncatedSources =
                buildList {
                    if (itemRows.truncated) add("SWEEP_ITEM")
                    if (sources.truncated) add("SOURCE_EVENT")
                    if (executions.truncated) add("SWEEP_EXECUTION")
                    if (results.truncated) add("RESULT_EVENT")
                },
        )
    }

    override fun operationsOverview(): SweepOperationsOverview =
        checkNotNull(
            jdbc.queryForObject(OVERVIEW_SQL, emptyMap<String, Any>()) { rs, _ ->
                SweepOperationsOverview(
                    acceptedRequestCount = rs.getLong("accepted_count"),
                    blockedRequestCount = rs.getLong("blocked_count"),
                    processingRequestCount = rs.getLong("processing_count"),
                    partialRequestCount = rs.getLong("partial_count"),
                    failedRequestCount = rs.getLong("failed_count"),
                    pendingItemCount = rs.getLong("pending_item_count"),
                    processingItemCount = rs.getLong("processing_item_count"),
                    oldestPendingRequestedAt = rs.nullableInstant("oldest_pending"),
                    pendingEventCount = rs.getLong("pending_event_count"),
                    failedEventCount = rs.getLong("failed_event_count"),
                    awaitingDawCompletionCount = rs.getLong("awaiting_completion_count"),
                    oldestAwaitingDawCompletionAt = rs.nullableInstant("oldest_awaiting_completion"),
                )
            },
        )

    private fun findSummary(identifier: String): SweepRequestSummary? =
        jdbc
            .query(SUMMARY_SQL, mapOf("identifier" to identifier)) { rs, _ ->
                val status = rs.getString("swp_req_stcd")
                SweepRequestSummary(
                    sweepRequestId = rs.getString("swp_req_id"),
                    externalSweepRequestId = rs.getString("ext_swp_req_id"),
                    requester = "DAW_CORE",
                    requesterEmployeeNo = rs.getString("frst_reg_empno"),
                    requesterBranchCode = rs.getString("frst_reg_brcd"),
                    network = rs.getString("ntwk_cd"),
                    symbol = rs.getString("tkn_smbl"),
                    status = status,
                    itemCount = rs.getInt("item_cnt"),
                    requestedAt = rs.instant("req_dttm"),
                    finishedAt = rs.nullableInstant("fnsh_dttm"),
                    retryable = status in RETRYABLE_REQUEST_STATUSES,
                    nextAction = requestNextAction(status),
                )
            }.firstOrNull()

    private fun findItems(requestId: String): BoundedRows<ItemRow> =
        jdbc
            .query(
                """
                SELECT swp_req_item_id, item_seq, acnt_id, swp_req_item_stcd, last_fail_cd
                  FROM bcm_swp_req_item_l
                 WHERE swp_req_id = :requestId
                 ORDER BY item_seq
                 LIMIT ${MAX_DETAIL_ROWS + 1}
                """.trimIndent(),
                mapOf("requestId" to requestId),
            ) { rs, _ ->
                ItemRow(
                    sweepItemId = rs.getString("swp_req_item_id"),
                    sequence = rs.getInt("item_seq"),
                    accountId = rs.getString("acnt_id"),
                    status = rs.getString("swp_req_item_stcd"),
                    lastFailureCode = rs.getString("last_fail_cd"),
                )
            }.bounded()

    private fun findEvents(
        itemIds: List<String>,
        source: Boolean,
    ): BoundedMap<SweepLinkedEvent> {
        if (itemIds.isEmpty()) return BoundedMap(emptyMap(), false)
        val rows =
            jdbc.query(
                if (source) SOURCE_EVENT_SQL else RESULT_EVENT_SQL,
                mapOf("itemIds" to itemIds),
            ) { rs, _ ->
                rs.getString("sweep_item_id") to
                    SweepLinkedEvent(
                        eventId = rs.getString("evnt_id"),
                        eventType = rs.getString("evt_typ_dvcd"),
                        outboxStatus = rs.getString("evnt_stcd"),
                        chainStatus = rs.getString("chain_status"),
                        itemOutcome = rs.getString("item_outcome"),
                        failureCode = rs.getString("failure_code"),
                        publishedAt = rs.nullableInstant("pub_dttm"),
                        dawCompletedAt = rs.nullableInstant("cmpl_dttm"),
                    )
            }
        return rows.boundedMap()
    }

    private fun findExecutions(itemIds: List<String>): BoundedMap<SweepExecutionInvestigation> {
        if (itemIds.isEmpty()) return BoundedMap(emptyMap(), false)
        val rows =
            jdbc.query(EXECUTION_SQL, mapOf("itemIds" to itemIds)) { rs, _ ->
                rs.getString("swp_req_item_id") to
                    SweepExecutionInvestigation(
                        executionId = rs.getString("swp_exec_id"),
                        externalTransactionId = rs.getString("ext_tx_id"),
                        status = rs.getString("swp_exec_stcd"),
                        operatorAccountId = rs.getString("opr_acnt_id"),
                        contractAddress = rs.getString("swp_ctrt_addr"),
                        policyVersionId = rs.getString("plcy_vrsn_id"),
                        policySnapshotHash = rs.getString("plcy_snps_hash"),
                        contractVersionId = rs.getString("ctrt_vrsn_id"),
                        contractEvidenceId = rs.getString("ctrt_evdc_id"),
                        requestedAmount = rs.decimal("req_amt"),
                        actualAmount = rs.nullableDecimal("actl_amt"),
                        itemStatus = rs.getString("swp_item_stcd"),
                        failureCode = rs.getString("fail_cd"),
                        logIndex = rs.getObject("log_idx", Int::class.javaObjectType),
                        transactionId = rs.getString("vndr_tx_id"),
                        transactionHash = rs.getString("tx_hash"),
                        requestedAt = rs.instant("req_dttm"),
                        finishedAt = rs.nullableInstant("fnsh_dttm"),
                    )
            }
        return rows.boundedMap()
    }

    private fun ResultSet.instant(column: String): Instant = CoreDateTimes.parse(getString(column)).toInstant(ZoneOffset.UTC)

    private fun ResultSet.nullableInstant(column: String): Instant? =
        getString(column)?.let {
            CoreDateTimes.parse(it).toInstant(ZoneOffset.UTC)
        }

    private fun ResultSet.decimal(column: String): BigDecimal = getBigDecimal(column).normalized()

    private fun ResultSet.nullableDecimal(column: String): BigDecimal? = getBigDecimal(column)?.normalized()

    private fun BigDecimal.normalized(): BigDecimal = stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }

    private fun <T> List<T>.bounded(): BoundedRows<T> = BoundedRows(take(MAX_DETAIL_ROWS), size > MAX_DETAIL_ROWS)

    private fun <T> List<Pair<String, T>>.boundedMap(): BoundedMap<T> =
        BoundedMap(take(MAX_DETAIL_ROWS).groupBy({ it.first }, { it.second }), size > MAX_DETAIL_ROWS)

    private data class ItemRow(
        val sweepItemId: String,
        val sequence: Int,
        val accountId: String,
        val status: String,
        val lastFailureCode: String?,
    )

    private data class BoundedRows<T>(
        val items: List<T>,
        val truncated: Boolean,
    )

    private data class BoundedMap<T>(
        val items: Map<String, List<T>>,
        val truncated: Boolean,
    )

    companion object {
        private const val MAX_DETAIL_ROWS = 100
        private val RETRYABLE_REQUEST_STATUSES = setOf("ACCEPTED", "PARTIAL")

        private fun requestNextAction(status: String): String =
            when (status) {
                "ACCEPTED" -> "WAIT_FOR_BATCH"
                "BLOCKED" -> "WAIT_FOR_GATE_RESUME"
                "PROCESSING" -> "WAIT_FOR_RECONCILIATION"
                "PARTIAL" -> "RETRY_PENDING_ITEMS"
                "FAILED" -> "OPERATIONS_REVIEW"
                "COMPLETED" -> "NONE"
                else -> "OPERATIONS_REVIEW"
            }

        private fun itemNextAction(status: String): String =
            when (status) {
                "PENDING" -> "WAIT_FOR_BATCH"
                "PROCESSING" -> "WAIT_FOR_RECONCILIATION"
                "FAILED" -> "OPERATIONS_REVIEW"
                "COMPLETED" -> "NONE"
                else -> "OPERATIONS_REVIEW"
            }

        private val SUMMARY_SQL =
            """
            WITH candidates AS (
              SELECT request.swp_req_id
                FROM bcm_swp_req_l request
               WHERE request.swp_req_id = :identifier OR request.ext_swp_req_id = :identifier
              UNION
              SELECT item.swp_req_id
                FROM bcm_swp_req_item_l item
               WHERE item.swp_req_item_id = :identifier
              UNION
              SELECT item.swp_req_id
                FROM bcm_swp_item_l execution_item
                JOIN bcm_swp_req_item_l item ON item.swp_req_item_id = execution_item.swp_req_item_id
                JOIN bcm_swp_exec_l execution ON execution.swp_exec_id = execution_item.swp_exec_id
               WHERE execution.swp_exec_id = :identifier
                  OR execution.ext_tx_id = :identifier
                  OR execution.vndr_tx_id = :identifier
                  OR execution.tx_hash = :identifier
              UNION
              SELECT request_item.swp_req_id
                FROM bcm_swp_req_src_l source
                JOIN bcm_swp_req_item_l request_item ON request_item.swp_req_item_id = source.swp_req_item_id
               WHERE source.evnt_id = :identifier
              UNION
              SELECT payload ->> 'sweepRequestId'
                FROM bcm_outbox_l
               WHERE topic = 'sweep-events'
                 AND (evnt_id = :identifier OR payload ->> 'sweepItemId' = :identifier)
            )
            SELECT request.*
              FROM bcm_swp_req_l request
              JOIN candidates ON candidates.swp_req_id = request.swp_req_id
             ORDER BY request.req_dttm, request.swp_req_id
             LIMIT 1
            """.trimIndent()

        private val SOURCE_EVENT_SQL =
            """
            SELECT source.swp_req_item_id AS sweep_item_id, outbox.evnt_id, outbox.evt_typ_dvcd,
                   outbox.evnt_stcd, outbox.payload ->> 'status' AS chain_status,
                   NULL AS item_outcome, NULL AS failure_code, outbox.pub_dttm, completion.cmpl_dttm
              FROM bcm_swp_req_src_l source
              JOIN bcm_outbox_l outbox ON outbox.evnt_id = source.evnt_id
              LEFT JOIN bcm_evnt_cmpl_l completion
                ON completion.evnt_id = outbox.evnt_id AND completion.cnsmr_dvcd = 'DAW_CORE'
             WHERE source.swp_req_item_id IN (:itemIds)
             ORDER BY source.swp_req_item_id, outbox.evnt_id
             LIMIT ${MAX_DETAIL_ROWS + 1}
            """.trimIndent()

        private val RESULT_EVENT_SQL =
            """
            SELECT outbox.payload ->> 'sweepItemId' AS sweep_item_id, outbox.evnt_id, outbox.evt_typ_dvcd,
                   outbox.evnt_stcd, outbox.payload ->> 'chainStatus' AS chain_status,
                   outbox.payload ->> 'itemOutcome' AS item_outcome,
                   outbox.payload ->> 'failureCode' AS failure_code, outbox.pub_dttm, completion.cmpl_dttm
              FROM bcm_outbox_l outbox
              LEFT JOIN bcm_evnt_cmpl_l completion
                ON completion.evnt_id = outbox.evnt_id AND completion.cnsmr_dvcd = 'DAW_CORE'
             WHERE outbox.topic = 'sweep-events'
               AND outbox.payload ->> 'sweepItemId' IN (:itemIds)
             ORDER BY outbox.payload ->> 'sweepItemId', outbox.evnt_id
             LIMIT ${MAX_DETAIL_ROWS + 1}
            """.trimIndent()

        private val EXECUTION_SQL =
            """
            SELECT item.swp_req_item_id, execution.swp_exec_id, execution.ext_tx_id, execution.swp_exec_stcd,
                   execution.opr_acnt_id, execution.swp_ctrt_addr, execution.plcy_vrsn_id,
                   execution.plcy_snps_hash, execution.ctrt_vrsn_id, execution.ctrt_evdc_id,
                   item.req_amt, item.actl_amt, item.swp_item_stcd, item.fail_cd, item.log_idx,
                   execution.vndr_tx_id, execution.tx_hash, execution.req_dttm, execution.fnsh_dttm
              FROM bcm_swp_item_l item
              JOIN bcm_swp_exec_l execution ON execution.swp_exec_id = item.swp_exec_id
             WHERE item.swp_req_item_id IN (:itemIds)
             ORDER BY item.swp_req_item_id, execution.req_dttm, execution.swp_exec_id
             LIMIT ${MAX_DETAIL_ROWS + 1}
            """.trimIndent()

        private val OVERVIEW_SQL =
            """
            SELECT
              count(*) FILTER (WHERE request.swp_req_stcd = 'ACCEPTED') AS accepted_count,
              count(*) FILTER (WHERE request.swp_req_stcd = 'BLOCKED') AS blocked_count,
              count(*) FILTER (WHERE request.swp_req_stcd = 'PROCESSING') AS processing_count,
              count(*) FILTER (WHERE request.swp_req_stcd = 'PARTIAL') AS partial_count,
              count(*) FILTER (WHERE request.swp_req_stcd = 'FAILED') AS failed_count,
              (SELECT count(*) FROM bcm_swp_req_item_l WHERE swp_req_item_stcd = 'PENDING') AS pending_item_count,
              (SELECT count(*) FROM bcm_swp_req_item_l WHERE swp_req_item_stcd = 'PROCESSING') AS processing_item_count,
              min(request.req_dttm) FILTER (WHERE request.swp_req_stcd IN ('ACCEPTED', 'BLOCKED', 'PROCESSING', 'PARTIAL')) AS oldest_pending,
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
                LIMIT 1) AS oldest_awaiting_completion
            FROM bcm_swp_req_l request
            """.trimIndent()
    }
}
