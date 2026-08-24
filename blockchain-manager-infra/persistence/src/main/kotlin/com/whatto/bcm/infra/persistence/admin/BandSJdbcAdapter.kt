package com.whatto.bcm.infra.persistence.admin

import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminChangeRequest
import com.whatto.bcm.domain.admin.BandSDirection
import com.whatto.bcm.domain.admin.BandSExecutionEvent
import com.whatto.bcm.domain.admin.BandSExecutionEventStatus
import com.whatto.bcm.domain.admin.BandSExecutionRecord
import com.whatto.bcm.domain.admin.BandSExecutionStatus
import com.whatto.bcm.domain.admin.BandSExecutionView
import com.whatto.bcm.domain.admin.BandSInputSnapshot
import com.whatto.bcm.domain.admin.BandSItemState
import com.whatto.bcm.domain.admin.BandSLegType
import com.whatto.bcm.domain.admin.BandSProposal
import com.whatto.bcm.domain.admin.BandSProposalItem
import com.whatto.bcm.domain.admin.BandSRepository
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Repository
class BandSJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : BandSRepository {
    override fun insertSnapshot(
        snapshot: BandSInputSnapshot,
        actor: AdminActor,
    ): BandSInputSnapshot {
        jdbc.update(
            """
            INSERT INTO bcm_bnds_snps_l
              (snps_id, src_req_id, plcy_vrsn_id, snps_hash, input_hash, base_dttm, expr_dttm,
               input_cmplt_yn, total_ast_krw_amt, obs_hot_krw_amt, obs_cold_krw_amt, efct_hot_krw_amt,
               hot_ratio, low_ratio, trgt_ratio, up_ratio, input_payload, issue_payload, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (:snapshotId, :sourceRequestId, :policyVersionId, :snapshotHash, :inputHash, :observedAt, :expiresAt,
               :complete, :totalAsset, :observedHot, :observedCold, :effectiveHot,
               :hotRatio, :lowerRatio, :targetRatio, :upperRatio, CAST(:inputPayload AS jsonb),
               CAST(:issuePayload AS jsonb), :registeredAt, :employeeNo, :branchCode, :employeeNo, :branchCode)
            """.trimIndent(),
            mapOf(
                "snapshotId" to snapshot.snapshotId,
                "sourceRequestId" to snapshot.sourceRequestId,
                "policyVersionId" to snapshot.policyVersionId,
                "snapshotHash" to snapshot.snapshotHash,
                "inputHash" to snapshot.inputHash,
                "observedAt" to snapshot.observedAt.coreDateTime(),
                "expiresAt" to snapshot.expiresAt.coreDateTime(),
                "complete" to snapshot.complete.toYn(),
                "totalAsset" to snapshot.totalAssetKrwAmount,
                "observedHot" to snapshot.observedHotKrwAmount,
                "observedCold" to snapshot.observedColdKrwAmount,
                "effectiveHot" to snapshot.effectiveHotKrwAmount,
                "hotRatio" to snapshot.hotRatio,
                "lowerRatio" to snapshot.lowerRatio,
                "targetRatio" to snapshot.targetRatio,
                "upperRatio" to snapshot.upperRatio,
                "inputPayload" to snapshot.inputPayload,
                "issuePayload" to snapshot.issueCodes.jsonArray(),
                "registeredAt" to snapshot.observedAt.coreDateTime(),
                "employeeNo" to actor.employeeNo,
                "branchCode" to actor.branchCode,
            ),
        )
        return snapshot
    }

    override fun findSnapshot(snapshotId: String): BandSInputSnapshot? = findSnapshot("snps_id = :value", snapshotId)

    override fun findSnapshotBySourceRequest(sourceRequestId: String): BandSInputSnapshot? =
        findSnapshot("src_req_id = :value", sourceRequestId)

    override fun insertProposal(
        proposal: BandSProposal,
        actor: AdminActor,
        now: Instant,
    ): BandSProposal {
        jdbc.update(
            """
            INSERT INTO bcm_bnds_prop_l
              (prop_id, src_prop_id, snps_id, plcy_vrsn_id, drct_dvcd, prop_hash, input_hash,
               item_cnt, total_krw_amt, aft_hot_ratio, exec_able_yn, prop_payload, block_payload, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (:proposalId, :sourceProposalId, :snapshotId, :policyVersionId, :direction, :proposalHash, :inputHash,
               :itemCount, :totalKrwAmount, :afterHotRatio, :executable, CAST(:proposalPayload AS jsonb),
               CAST(:blockPayload AS jsonb), :now, :employeeNo, :branchCode, :employeeNo, :branchCode)
            """.trimIndent(),
            mapOf(
                "proposalId" to proposal.proposalId,
                "sourceProposalId" to proposal.sourceProposalId,
                "snapshotId" to proposal.snapshotId,
                "policyVersionId" to proposal.policyVersionId,
                "direction" to proposal.direction.name,
                "proposalHash" to proposal.proposalHash,
                "inputHash" to proposal.inputHash,
                "itemCount" to proposal.items.size,
                "totalKrwAmount" to proposal.totalKrwAmount,
                "afterHotRatio" to proposal.afterHotRatio,
                "executable" to proposal.executable.toYn(),
                "proposalPayload" to proposal.proposalPayload,
                "blockPayload" to proposal.blockReasons.jsonArray(),
                "now" to now.coreDateTime(),
                "employeeNo" to actor.employeeNo,
                "branchCode" to actor.branchCode,
            ),
        )
        proposal.items.forEach { insertProposalItem(proposal.proposalId, it, actor) }
        return proposal
    }

    override fun findProposal(proposalId: String): BandSProposal? = findProposal("prop_id = :value", proposalId)

    override fun findProposalBySourceId(sourceProposalId: String): BandSProposal? = findProposal("src_prop_id = :value", sourceProposalId)

    override fun insertExecutionIntent(
        actionId: String,
        correlationId: String,
        request: AdminChangeRequest,
        idempotencyKey: String,
        requestHash: String,
        expectedState: String,
        expectedStateHash: String,
        actor: AdminActor,
        now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO bcm_adm_actn_l
              (actn_id, corr_id, req_id, actn_dvcd, actn_stcd, try_seq, idmp_key,
               req_hash, exp_state, exp_state_hash, occr_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (:actionId, :correlationId, :requestId, 'EXECUTE', 'INTENT', 1, :idempotencyKey,
               :requestHash, CAST(:expectedState AS jsonb), :expectedStateHash, :now,
               :employeeNo, :branchCode, :employeeNo, :branchCode)
            """.trimIndent(),
            mapOf(
                "actionId" to actionId,
                "correlationId" to correlationId,
                "requestId" to request.lifecycle.requestId,
                "idempotencyKey" to idempotencyKey,
                "requestHash" to requestHash,
                "expectedState" to expectedState,
                "expectedStateHash" to expectedStateHash,
                "now" to now.coreDateTime(),
                "employeeNo" to actor.employeeNo,
                "branchCode" to actor.branchCode,
            ),
        )
    }

    override fun insertExecution(
        record: BandSExecutionRecord,
        items: List<BandSProposalItem>,
    ): BandSExecutionRecord {
        jdbc.update(
            """
            INSERT INTO bcm_bnds_exec_l
              (exec_id, req_id, prop_id, snps_id, plcy_vrsn_id, prop_hash, input_hash,
               idmp_key, exec_hash, rsv_dttm, frst_reg_empno, frst_reg_brcd,
               last_chng_empno, last_chng_brcd)
            VALUES
              (:executionId, :requestId, :proposalId, :snapshotId, :policyVersionId, :proposalHash, :inputHash,
               :idempotencyKey, :executionHash, :reservedAt, :employeeNo, :branchCode, :employeeNo, :branchCode)
            """.trimIndent(),
            record.params(),
        )
        items.forEach { item ->
            jdbc.update(
                """
                INSERT INTO bcm_bnds_exec_item_key
                  (exec_id, item_seq, prop_id, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES (:executionId, :itemSequence, :proposalId, :employeeNo, :branchCode, :employeeNo, :branchCode)
                """.trimIndent(),
                record.params() + ("itemSequence" to item.sequence),
            )
            appendEvent(
                BandSExecutionEvent(
                    record.executionId,
                    item.sequence,
                    1,
                    BandSExecutionEventStatus.RESERVED,
                    null,
                    null,
                    "{}",
                    sha256("{}"),
                    record.reservedAt,
                    record.reservedBy,
                ),
            )
        }
        return record.copy(reservedBy = record.reservedBy.copy(roles = emptySet()))
    }

    override fun findExecution(
        requestId: String,
        idempotencyKey: String,
    ): BandSExecutionRecord? =
        jdbc
            .query(
                "$EXECUTION_SELECT WHERE req_id = :requestId AND idmp_key = :idempotencyKey",
                mapOf("requestId" to requestId, "idempotencyKey" to idempotencyKey),
            ) { rs, _ -> rs.toExecution() }
            .firstOrNull()

    override fun findExecution(executionId: String): BandSExecutionView? {
        val execution =
            jdbc
                .query(
                    "$EXECUTION_SELECT WHERE exec_id = :executionId",
                    mapOf("executionId" to executionId),
                ) { rs, _ -> rs.toExecution() }
                .firstOrNull() ?: return null
        val states =
            jdbc.query(
                """
                SELECT DISTINCT ON (item_seq) item_seq, exec_stcd
                  FROM bcm_bnds_exec_evt_l
                 WHERE exec_id = :executionId
                 ORDER BY item_seq, evt_seq DESC
                """.trimIndent(),
                mapOf("executionId" to executionId),
            ) { rs, _ -> BandSItemState(rs.getInt("item_seq"), BandSExecutionEventStatus.valueOf(rs.getString("exec_stcd"))) }
        val itemCount = findProposal(execution.proposalId)?.items?.size ?: 0
        return BandSExecutionView(execution, states, BandSExecutionStatus.derive(states, itemCount))
    }

    override fun appendEvent(event: BandSExecutionEvent): BandSExecutionEvent {
        try {
            jdbc.update(
                """
                INSERT INTO bcm_bnds_exec_evt_l
                  (exec_id, item_seq, evt_seq, exec_stcd, ext_tx_id, vndr_tx_id,
                   obs_payload, obs_hash, occr_dttm,
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES
                  (:executionId, :itemSequence, :eventSequence, :status, :externalTransactionId, :vendorTransactionId,
                   CAST(:observationPayload AS jsonb), :observationHash, :occurredAt,
                   :employeeNo, :branchCode, :employeeNo, :branchCode)
                """.trimIndent(),
                mapOf(
                    "executionId" to event.executionId,
                    "itemSequence" to event.itemSequence,
                    "eventSequence" to event.eventSequence,
                    "status" to event.status.name,
                    "externalTransactionId" to event.externalTransactionId,
                    "vendorTransactionId" to event.vendorTransactionId,
                    "observationPayload" to event.observationPayload,
                    "observationHash" to event.observationHash,
                    "occurredAt" to event.occurredAt.coreDateTime(),
                    "employeeNo" to event.actor.employeeNo,
                    "branchCode" to event.actor.branchCode,
                ),
            )
        } catch (exception: DuplicateKeyException) {
            throw ConflictException("bandSExecutionEvent", "${event.executionId}:${event.itemSequence}:${event.eventSequence}", exception)
        }
        return event
    }

    private fun findSnapshot(
        condition: String,
        value: String,
    ): BandSInputSnapshot? =
        jdbc
            .query(
                """
                SELECT snps_id, src_req_id, plcy_vrsn_id, snps_hash, input_hash, base_dttm, expr_dttm,
                       input_cmplt_yn, total_ast_krw_amt, obs_hot_krw_amt, obs_cold_krw_amt, efct_hot_krw_amt,
                       hot_ratio, low_ratio, trgt_ratio, up_ratio, input_payload::text AS input_payload,
                       ARRAY(SELECT jsonb_array_elements_text(issue_payload)) AS issue_codes
                  FROM bcm_bnds_snps_l WHERE $condition
                """.trimIndent(),
                mapOf("value" to value),
            ) { rs, _ -> rs.toSnapshot() }
            .firstOrNull()

    private fun findProposal(
        condition: String,
        value: String,
    ): BandSProposal? =
        jdbc
            .query(
                """
                SELECT prop_id, src_prop_id, snps_id, plcy_vrsn_id, drct_dvcd, prop_hash, input_hash,
                       total_krw_amt, aft_hot_ratio, exec_able_yn, prop_payload::text AS prop_payload,
                       ARRAY(SELECT jsonb_array_elements_text(block_payload)) AS block_reasons
                  FROM bcm_bnds_prop_l WHERE $condition
                """.trimIndent(),
                mapOf("value" to value),
            ) { rs, _ -> rs.toProposal(findItems(rs.getString("prop_id"))) }
            .firstOrNull()

    private fun findItems(proposalId: String): List<BandSProposalItem> =
        jdbc.query(
            """
            SELECT item_seq, dep_item_seq, leg_dvcd, ntwk_cd, tkn_smbl, src_vlt_id, dst_vlt_id,
                   dst_addr, amt, krw_amt, exp_fee_amt, item_hash, exec_able_yn, block_rsn_cd
              FROM bcm_bnds_prop_item_l WHERE prop_id = :proposalId ORDER BY item_seq
            """.trimIndent(),
            mapOf("proposalId" to proposalId),
        ) { rs, _ -> rs.toProposalItem() }

    private fun insertProposalItem(
        proposalId: String,
        item: BandSProposalItem,
        actor: AdminActor,
    ) {
        jdbc.update(
            """
            INSERT INTO bcm_bnds_prop_item_l
              (prop_id, item_seq, dep_item_seq, leg_dvcd, ntwk_cd, tkn_smbl, src_vlt_id,
               dst_vlt_id, dst_addr, amt, krw_amt, exp_fee_amt, item_hash, exec_able_yn, block_rsn_cd,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (:proposalId, :sequence, :dependsOn, :legType, :network, :tokenSymbol, :sourceVaultId,
               :destinationVaultId, :destinationAddress, :amount, :krwAmount, :expectedFeeAmount,
               :itemHash, :executable, :blockReason, :employeeNo, :branchCode, :employeeNo, :branchCode)
            """.trimIndent(),
            mapOf(
                "proposalId" to proposalId,
                "sequence" to item.sequence,
                "dependsOn" to item.dependsOnSequence,
                "legType" to item.legType.name,
                "network" to item.network,
                "tokenSymbol" to item.tokenSymbol,
                "sourceVaultId" to item.sourceVaultId,
                "destinationVaultId" to item.destinationVaultId,
                "destinationAddress" to item.destinationAddress,
                "amount" to item.amount,
                "krwAmount" to item.krwAmount,
                "expectedFeeAmount" to item.expectedFeeAmount,
                "itemHash" to item.itemHash,
                "executable" to item.executable.toYn(),
                "blockReason" to item.blockReason,
                "employeeNo" to actor.employeeNo,
                "branchCode" to actor.branchCode,
            ),
        )
    }

    private fun ResultSet.toSnapshot() =
        BandSInputSnapshot(
            getString("snps_id"),
            getString("src_req_id"),
            getString("plcy_vrsn_id"),
            getString("snps_hash"),
            getString("input_hash"),
            getString("base_dttm").instant(),
            getString("expr_dttm").instant(),
            getString("input_cmplt_yn") == "Y",
            getBigDecimal("total_ast_krw_amt"),
            getBigDecimal("obs_hot_krw_amt"),
            getBigDecimal("obs_cold_krw_amt"),
            getBigDecimal("efct_hot_krw_amt"),
            getBigDecimal("hot_ratio"),
            getBigDecimal("low_ratio"),
            getBigDecimal("trgt_ratio"),
            getBigDecimal("up_ratio"),
            getString("input_payload"),
            getArray("issue_codes").array.asStringList(),
        )

    private fun ResultSet.toProposal(items: List<BandSProposalItem>) =
        BandSProposal(
            getString("prop_id"),
            getString("src_prop_id"),
            getString("snps_id"),
            getString("plcy_vrsn_id"),
            BandSDirection.valueOf(getString("drct_dvcd")),
            getString("prop_hash"),
            getString("input_hash"),
            getBigDecimal("total_krw_amt"),
            getBigDecimal("aft_hot_ratio"),
            getString("exec_able_yn") == "Y",
            getArray("block_reasons").array.asStringList(),
            getString("prop_payload"),
            items,
        )

    private fun ResultSet.toProposalItem() =
        BandSProposalItem(
            getInt("item_seq"),
            getInt("dep_item_seq").takeUnless { wasNull() },
            BandSLegType.valueOf(getString("leg_dvcd")),
            getString("ntwk_cd"),
            getString("tkn_smbl"),
            getString("src_vlt_id"),
            getString("dst_vlt_id"),
            getString("dst_addr"),
            getBigDecimal("amt"),
            getBigDecimal("krw_amt"),
            getBigDecimal("exp_fee_amt"),
            getString("item_hash"),
            getString("exec_able_yn") == "Y",
            getString("block_rsn_cd"),
        )

    private fun ResultSet.toExecution() =
        BandSExecutionRecord(
            getString("exec_id"),
            getString("req_id"),
            getString("prop_id"),
            getString("snps_id"),
            getString("plcy_vrsn_id"),
            getString("prop_hash"),
            getString("input_hash"),
            getString("idmp_key"),
            getString("exec_hash"),
            getString("rsv_dttm").instant(),
            AdminActor(getString("frst_reg_empno"), getString("frst_reg_brcd"), emptySet()),
        )

    private fun BandSExecutionRecord.params() =
        mapOf(
            "executionId" to executionId,
            "requestId" to requestId,
            "proposalId" to proposalId,
            "snapshotId" to snapshotId,
            "policyVersionId" to policyVersionId,
            "proposalHash" to proposalHash,
            "inputHash" to inputHash,
            "idempotencyKey" to idempotencyKey,
            "executionHash" to executionHash,
            "reservedAt" to reservedAt.coreDateTime(),
            "employeeNo" to reservedBy.employeeNo,
            "branchCode" to reservedBy.branchCode,
        )

    private fun Any.asStringList(): List<String> = (this as Array<*>).map { it.toString() }

    private fun List<String>.jsonArray(): String = joinToString(prefix = "[", postfix = "]") { "\"${it.jsonEscape()}\"" }

    private fun String.jsonEscape(): String = replace("\\", "\\\\").replace("\"", "\\\"")

    private fun Boolean.toYn(): String = if (this) "Y" else "N"

    private fun Instant.coreDateTime(): String = CoreDateTimes.format(LocalDateTime.ofInstant(this, ZoneOffset.UTC))

    private fun String.instant(): Instant = CoreDateTimes.parse(this).toInstant(ZoneOffset.UTC)

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object {
        private val EXECUTION_SELECT =
            """
            SELECT exec_id, req_id, prop_id, snps_id, plcy_vrsn_id, prop_hash, input_hash,
                   idmp_key, exec_hash, rsv_dttm, frst_reg_empno, frst_reg_brcd
              FROM bcm_bnds_exec_l
            """.trimIndent()
    }
}
