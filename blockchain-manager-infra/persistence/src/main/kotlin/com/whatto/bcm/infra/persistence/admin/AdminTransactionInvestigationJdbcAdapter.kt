package com.whatto.bcm.infra.persistence.admin

import com.whatto.bcm.domain.admin.TransactionAllowance
import com.whatto.bcm.domain.admin.TransactionBoostAttempt
import com.whatto.bcm.domain.admin.TransactionFeeQuote
import com.whatto.bcm.domain.admin.TransactionInvestigation
import com.whatto.bcm.domain.admin.TransactionInvestigationRepository
import com.whatto.bcm.domain.admin.TransactionInvestigationSummary
import com.whatto.bcm.domain.admin.TransactionSweepExecution
import com.whatto.bcm.domain.admin.TransactionSweepItem
import com.whatto.bcm.domain.admin.TransactionTimelineEntry
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset

@Repository
class AdminTransactionInvestigationJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : TransactionInvestigationRepository {
    override fun findByIdentifier(identifier: String): TransactionInvestigation? {
        val summary = findSummary(identifier) ?: return null
        val boostRows = findBoosts(summary.rootTransactionId)
        val boosts = boostRows.items
        val physicalTransactionIds =
            buildSet {
                add(summary.rootTransactionId)
                add(summary.activeTransactionId)
                boosts.forEach { boost ->
                    add(boost.replacedTransactionId)
                    boost.newTransactionId?.let(::add)
                }
            }
        val webhookRows = findWebhooks(physicalTransactionIds)
        val outboxRows = findOutbox(summary.rootTransactionId)
        val sweepRows = summary.sweepExecutionId?.let(::findSweepExecution)
        val sweep = sweepRows?.execution
        val allowanceRows = sweep?.let(::findAllowances) ?: BoundedRows(emptyList(), false)
        val timeline = buildTimeline(summary, boosts, webhookRows.items, outboxRows.items, sweep)
        val truncatedSources =
            buildList {
                if (webhookRows.truncated) add("WEBHOOK")
                if (outboxRows.truncated) add("OUTBOX")
                if (boostRows.truncated) add("BOOST")
                if (sweepRows?.truncated == true) add("SWEEP_ITEM")
                if (allowanceRows.truncated) add("ALLOWANCE")
            }
        return TransactionInvestigation(
            summary = summary,
            timeline = timeline,
            boosts = boosts,
            sweepExecution = sweep,
            allowances = allowanceRows.items,
            feeQuotes = findFeeQuotes(summary, boosts),
            truncatedSources = truncatedSources,
        )
    }

    private fun findSummary(identifier: String): TransactionInvestigationSummary? =
        jdbc
            .query(
                SUMMARY_SQL,
                mapOf("identifier" to identifier),
                RowMapper { rs, _ -> rs.toSummary() },
            ).firstOrNull()

    private fun findBoosts(rootTransactionId: String): BoundedRows<TransactionBoostAttempt> =
        jdbc
            .query(
                """
                SELECT try_seq, ext_tx_id, bst_stcd, rplc_tx_id, rplc_tx_hash, new_tx_id,
                       fee_lvl, gasless_yn, req_dttm, rsp_dttm
                  FROM bcm_boost_l
                 WHERE orig_tx_id = :rootTransactionId
                 ORDER BY try_seq
                 LIMIT ${MAX_DETAIL_ROWS + 1}
                """.trimIndent(),
                mapOf("rootTransactionId" to rootTransactionId),
            ) { rs, _ ->
                TransactionBoostAttempt(
                    attemptSequence = rs.getInt("try_seq"),
                    externalTransactionId = rs.getString("ext_tx_id"),
                    status = rs.getString("bst_stcd"),
                    replacedTransactionId = rs.getString("rplc_tx_id"),
                    replacedTransactionHash = rs.getString("rplc_tx_hash"),
                    newTransactionId = rs.getString("new_tx_id"),
                    feeLevel = rs.getString("fee_lvl"),
                    gasless = rs.getString("gasless_yn") == "Y",
                    requestedAt = rs.instant("req_dttm"),
                    respondedAt = rs.nullableInstant("rsp_dttm"),
                )
            }.bounded()

    private fun findWebhooks(physicalTransactionIds: Set<String>): BoundedRows<TransactionTimelineEntry> {
        if (physicalTransactionIds.isEmpty()) return BoundedRows(emptyList(), false)
        val rows =
            jdbc.query(
                """
                SELECT noti_id, evnt_typ, prcs_stcd, rcv_dttm
                  FROM bcm_whk_l
                 WHERE vndr_tx_id IN (:physicalTransactionIds)
                 ORDER BY rcv_dttm, noti_id
                 LIMIT ${MAX_DETAIL_ROWS + 1}
                """.trimIndent(),
                mapOf("physicalTransactionIds" to physicalTransactionIds),
            ) { rs, _ ->
                TransactionTimelineEntry(
                    source = "WEBHOOK",
                    code = rs.getString("evnt_typ"),
                    status = rs.getString("prcs_stcd"),
                    observedAt = rs.instant("rcv_dttm"),
                    identifier = rs.getString("noti_id"),
                )
            }
        return rows.bounded()
    }

    private fun findOutbox(rootTransactionId: String): BoundedRows<TransactionTimelineEntry> {
        val rows =
            jdbc.query(
                """
                SELECT evnt_id, evt_typ_dvcd, evnt_stcd, payload ->> 'status' AS payload_status,
                       pub_dttm, last_rtry_dttm
                  FROM bcm_outbox_l
                 WHERE vndr_tx_id = :rootTransactionId
                 ORDER BY evnt_id
                 LIMIT ${MAX_DETAIL_ROWS + 1}
                """.trimIndent(),
                mapOf("rootTransactionId" to rootTransactionId),
            ) { rs, _ ->
                TransactionTimelineEntry(
                    source = "OUTBOX",
                    code = rs.getString("evt_typ_dvcd"),
                    status = rs.getString("payload_status") ?: rs.getString("evnt_stcd"),
                    observedAt = rs.nullableInstant("pub_dttm") ?: rs.nullableInstant("last_rtry_dttm"),
                    identifier = rs.getString("evnt_id"),
                )
            }
        return rows.bounded()
    }

    private fun findSweepExecution(executionId: String): BoundedSweep? {
        val execution =
            jdbc
                .query(
                    """
                    SELECT swp_exec_id, ext_tx_id, swp_exec_stcd, opr_acnt_id, swp_ctrt_addr,
                           req_tot_amt, actl_tot_amt, vndr_tx_id, tx_hash, req_dttm, fnsh_dttm
                      FROM bcm_swp_exec_l
                     WHERE swp_exec_id = :executionId
                    """.trimIndent(),
                    mapOf("executionId" to executionId),
                ) { rs, _ ->
                    TransactionSweepExecution(
                        executionId = rs.getString("swp_exec_id"),
                        externalTransactionId = rs.getString("ext_tx_id"),
                        status = rs.getString("swp_exec_stcd"),
                        operatorAccountId = rs.getString("opr_acnt_id"),
                        contractAddress = rs.getString("swp_ctrt_addr"),
                        requestedTotalAmount = rs.decimal("req_tot_amt"),
                        actualTotalAmount = rs.nullableDecimal("actl_tot_amt"),
                        transactionId = rs.getString("vndr_tx_id"),
                        transactionHash = rs.getString("tx_hash"),
                        requestedAt = rs.instant("req_dttm"),
                        finishedAt = rs.nullableInstant("fnsh_dttm"),
                        items = emptyList(),
                    )
                }.firstOrNull() ?: return null
        val items =
            jdbc.query(
                """
                SELECT item_seq, acnt_id, src_addr, req_amt, actl_amt, swp_item_stcd, fail_cd, log_idx
                  FROM bcm_swp_item_l
                 WHERE swp_exec_id = :executionId
                 ORDER BY item_seq
                 LIMIT ${MAX_DETAIL_ROWS + 1}
                """.trimIndent(),
                mapOf("executionId" to executionId),
            ) { rs, _ ->
                TransactionSweepItem(
                    sequence = rs.getInt("item_seq"),
                    accountId = rs.getString("acnt_id"),
                    sourceAddress = rs.getString("src_addr"),
                    requestedAmount = rs.decimal("req_amt"),
                    actualAmount = rs.nullableDecimal("actl_amt"),
                    status = rs.getString("swp_item_stcd"),
                    failureCode = rs.getString("fail_cd"),
                    logIndex = rs.getObject("log_idx", Int::class.javaObjectType),
                )
            }
        val boundedItems = items.bounded()
        return BoundedSweep(execution.copy(items = boundedItems.items), boundedItems.truncated)
    }

    private fun findAllowances(execution: TransactionSweepExecution): BoundedRows<TransactionAllowance> {
        val accountIds = execution.items.map(TransactionSweepItem::accountId).toSet()
        if (accountIds.isEmpty()) return BoundedRows(emptyList(), false)
        val rows =
            jdbc.query(
                """
                SELECT acnt_id, ntwk_cd, tkn_smbl, swp_ctrt_addr, alwnc_cap, obs_alwnc,
                       auth_stcd, last_chck_dttm
                  FROM bcm_swp_auth_m
                 WHERE acnt_id IN (:accountIds)
                   AND swp_ctrt_addr = :contractAddress
                 ORDER BY acnt_id
                 LIMIT ${MAX_DETAIL_ROWS + 1}
                """.trimIndent(),
                mapOf(
                    "accountIds" to accountIds,
                    "contractAddress" to execution.contractAddress,
                ),
            ) { rs, _ ->
                TransactionAllowance(
                    accountId = rs.getString("acnt_id"),
                    network = rs.getString("ntwk_cd"),
                    symbol = rs.getString("tkn_smbl"),
                    contractAddress = rs.getString("swp_ctrt_addr"),
                    cap = rs.decimal("alwnc_cap"),
                    observedAllowance = rs.decimal("obs_alwnc"),
                    status = rs.getString("auth_stcd"),
                    checkedAt = rs.instant("last_chck_dttm"),
                )
            }
        return rows.bounded()
    }

    private fun findFeeQuotes(
        summary: TransactionInvestigationSummary,
        boosts: List<TransactionBoostAttempt>,
    ): List<TransactionFeeQuote> =
        jdbc
            .query(
                FEE_QUOTE_SQL,
                mapOf(
                    "rootTransactionId" to summary.rootTransactionId,
                    "externalTransactionId" to summary.externalTransactionId,
                ),
            ) { rs, _ ->
                TransactionFeeQuote(
                    context = rs.getString("context"),
                    level = rs.getString("fee_lvl"),
                    observedAt = rs.instant("obs_dttm"),
                    feePerByte = rs.nullableDecimal("fee_per_byte"),
                    gasPrice = rs.nullableDecimal("gas_price"),
                    networkFee = rs.nullableDecimal("ntwk_fee"),
                    baseFee = rs.nullableDecimal("base_fee"),
                    priorityFee = rs.nullableDecimal("priority_fee"),
                )
            }.filter { quote ->
                quote.context == "SUBMISSION" || boosts.any { quote.context == "BOOST_${it.attemptSequence}" }
            }.sortedBy { quote ->
                if (quote.context == "SUBMISSION") {
                    0
                } else {
                    quote.context.removePrefix("BOOST_").toIntOrNull() ?: Int.MAX_VALUE
                }
            }

    private fun buildTimeline(
        summary: TransactionInvestigationSummary,
        boosts: List<TransactionBoostAttempt>,
        webhooks: List<TransactionTimelineEntry>,
        outbox: List<TransactionTimelineEntry>,
        sweep: TransactionSweepExecution?,
    ): List<TransactionTimelineEntry> =
        buildList {
            summary.externalTransactionId?.let { externalTransactionId ->
                summary.submissionRequestedAt?.let {
                    add(TransactionTimelineEntry("SUBMISSION", "INTENT", summary.submissionStatus, it, externalTransactionId))
                }
                summary.submissionRespondedAt?.let {
                    add(TransactionTimelineEntry("SUBMISSION", "RESPONDED", summary.submissionStatus, it, externalTransactionId))
                }
            }
            add(TransactionTimelineEntry("STATE", "DETECTED", null, summary.firstDetectedAt, summary.rootTransactionId))
            addAll(webhooks)
            addAll(outbox)
            summary.reconciliationCheckedAt?.let {
                add(TransactionTimelineEntry("RECONCILIATION", "CHECKED", summary.status, it, summary.rootTransactionId))
            }
            summary.reconciliationStoppedAt?.let {
                add(TransactionTimelineEntry("RECONCILIATION", "STOPPED", summary.status, it, summary.rootTransactionId))
            }
            boosts.forEach { boost ->
                add(TransactionTimelineEntry("BOOST", "REQUESTED", boost.status, boost.requestedAt, boost.externalTransactionId))
                boost.respondedAt?.let {
                    add(TransactionTimelineEntry("BOOST", "RESPONDED", boost.status, it, boost.newTransactionId))
                }
            }
            sweep?.let {
                add(TransactionTimelineEntry("SWEEP", "REQUESTED", it.status, it.requestedAt, it.executionId))
                it.finishedAt?.let { finishedAt ->
                    add(TransactionTimelineEntry("SWEEP", "FINISHED", it.status, finishedAt, it.executionId))
                }
            }
        }.sortedWith(compareBy<TransactionTimelineEntry>({ it.observedAt ?: Instant.MAX }, { it.source }, { it.code }, { it.identifier }))

    private fun ResultSet.toSummary() =
        TransactionInvestigationSummary(
            rootTransactionId = getString("vndr_tx_id"),
            activeTransactionId = getString("actv_tx_id"),
            externalTransactionId = getString("ext_tx_id"),
            transactionHash = getString("tx_hash"),
            accountId = getString("acnt_id"),
            network = getString("ntwk_cd"),
            symbol = getString("tkn_smbl"),
            transactionType = getString("tx_dvcd"),
            status = getString("last_pub_stcd"),
            confirmationCount = getInt("cnfm_cnt"),
            vendorSubStatus = getString("vndr_sub_stcd"),
            vendorNetworkStatus = getString("vndr_ntwk_stcd"),
            submissionStatus = getString("sbmt_stcd"),
            amount = nullableDecimal("trsf_amt"),
            senderAccountId = getString("snd_acnt_id"),
            receiverType = getString("rcv_dvcd"),
            receiverValue = getString("rcv_vl"),
            sweepExecutionId = getString("swp_exec_id"),
            submissionRequestedAt = nullableInstant("req_dttm"),
            submissionRespondedAt = nullableInstant("rsp_dttm"),
            vendorCreatedAt = instant("vndr_crt_dttm"),
            firstDetectedAt = instant("frst_dtct_dttm"),
            lastChangedAt = instant("last_chng_dttm"),
            reconciliationCheckedAt = nullableInstant("rcnc_chck_dttm"),
            reconciliationCheckCount = getInt("rcnc_chck_cnt"),
            reconciliationStoppedAt = nullableInstant("rcnc_stop_dttm"),
        )

    private fun ResultSet.instant(column: String): Instant = CoreDateTimes.parse(getString(column)).toInstant(ZoneOffset.UTC)

    private fun ResultSet.nullableInstant(column: String): Instant? =
        getString(column)?.let { CoreDateTimes.parse(it).toInstant(ZoneOffset.UTC) }

    private fun ResultSet.decimal(column: String): BigDecimal = getBigDecimal(column).normalized()

    private fun ResultSet.nullableDecimal(column: String): BigDecimal? = getBigDecimal(column)?.normalized()

    private fun BigDecimal.normalized(): BigDecimal = stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }

    private fun <T> List<T>.bounded(): BoundedRows<T> = BoundedRows(take(MAX_DETAIL_ROWS), size > MAX_DETAIL_ROWS)

    private data class BoundedRows<T>(
        val items: List<T>,
        val truncated: Boolean,
    )

    private data class BoundedSweep(
        val execution: TransactionSweepExecution,
        val truncated: Boolean,
    )

    companion object {
        private const val MAX_DETAIL_ROWS = 100

        private val SUMMARY_SQL =
            """
            WITH roots AS (
              SELECT tx.vndr_tx_id AS root_tx_id
                FROM bcm_tx_l tx
               WHERE tx.vndr_tx_id = :identifier
                  OR tx.actv_tx_id = :identifier
                  OR tx.ext_tx_id = :identifier
              UNION
              SELECT boost.orig_tx_id
                FROM bcm_boost_l boost
               WHERE boost.ext_tx_id = :identifier
                  OR boost.new_tx_id = :identifier
                  OR boost.rplc_tx_id = :identifier
              UNION
              SELECT tx.vndr_tx_id
                FROM bcm_sbmt_l submission
                JOIN bcm_tx_l tx
                  ON tx.ext_tx_id = submission.ext_tx_id
                  OR tx.vndr_tx_id = submission.vndr_tx_id
               WHERE submission.ext_tx_id = :identifier
                  OR submission.vndr_tx_id = :identifier
                  OR submission.swp_exec_id = :identifier
              UNION
              SELECT tx.vndr_tx_id
                FROM bcm_swp_exec_l execution
                JOIN bcm_sbmt_l submission ON submission.swp_exec_id = execution.swp_exec_id
                JOIN bcm_tx_l tx
                  ON tx.ext_tx_id = submission.ext_tx_id
                  OR tx.vndr_tx_id = submission.vndr_tx_id
               WHERE execution.swp_exec_id = :identifier
                  OR execution.ext_tx_id = :identifier
                  OR execution.vndr_tx_id = :identifier
            )
            SELECT tx.*,
                   submission.tx_dvcd, submission.sbmt_stcd,
                   COALESCE(submission.trsf_amt, event_amount.trsf_amt) AS trsf_amt,
                   submission.snd_acnt_id, submission.rcv_dvcd, submission.rcv_vl,
                   submission.swp_exec_id, submission.req_dttm, submission.rsp_dttm
              FROM bcm_tx_l tx
              LEFT JOIN LATERAL (
                SELECT submission.*
                  FROM bcm_sbmt_l submission
                 WHERE submission.ext_tx_id = tx.ext_tx_id
                    OR submission.vndr_tx_id = tx.vndr_tx_id
                 ORDER BY CASE WHEN submission.ext_tx_id = tx.ext_tx_id THEN 0 ELSE 1 END
                 LIMIT 1
              ) submission ON TRUE
              LEFT JOIN LATERAL (
                SELECT CASE
                         WHEN outbox.payload ->> 'amount' ~ '^(0|[1-9][0-9]*)(\.[0-9]+)?$'
                         THEN (outbox.payload ->> 'amount')::numeric
                       END AS trsf_amt
                  FROM bcm_outbox_l outbox
                 WHERE outbox.vndr_tx_id = tx.vndr_tx_id
                   AND jsonb_extract_path_text(outbox.payload, 'amount') IS NOT NULL
                 ORDER BY outbox.evnt_id DESC
                 LIMIT 1
              ) event_amount ON TRUE
             WHERE tx.vndr_tx_id = (SELECT root_tx_id FROM roots ORDER BY root_tx_id LIMIT 1)
            """.trimIndent()

        private val FEE_QUOTE_SQL =
            """
            SELECT 'SUBMISSION' AS context, quote.*
              FROM bcm_sbmt_l submission
              JOIN LATERAL (
                SELECT fee.*
                  FROM bcm_fee_qt_l fee
                 WHERE fee.ntwk_cd = submission.ntwk_cd
                   AND fee.tkn_smbl = submission.tkn_smbl
                   AND fee.fee_lvl = 'MEDIUM'
                   AND fee.obs_dttm <= submission.req_dttm
                 ORDER BY fee.obs_dttm DESC
                 LIMIT 1
              ) quote ON TRUE
             WHERE submission.ext_tx_id = :externalTransactionId
            UNION ALL
            SELECT 'BOOST_' || boost.try_seq AS context, quote.*
              FROM bcm_boost_l boost
              JOIN bcm_tx_l tx ON tx.vndr_tx_id = boost.orig_tx_id
              JOIN LATERAL (
                SELECT fee.*
                  FROM bcm_fee_qt_l fee
                 WHERE fee.ntwk_cd = tx.ntwk_cd
                   AND fee.tkn_smbl = tx.tkn_smbl
                   AND fee.fee_lvl = boost.fee_lvl
                   AND fee.obs_dttm <= boost.req_dttm
                 ORDER BY fee.obs_dttm DESC
                 LIMIT 1
              ) quote ON TRUE
             WHERE boost.orig_tx_id = :rootTransactionId
             ORDER BY context
            """.trimIndent()
    }
}
