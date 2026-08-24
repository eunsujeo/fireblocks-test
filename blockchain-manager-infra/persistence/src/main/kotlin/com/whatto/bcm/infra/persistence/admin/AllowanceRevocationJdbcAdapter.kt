package com.whatto.bcm.infra.persistence.admin

import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminChangeRequest
import com.whatto.bcm.domain.admin.AllowanceRevocationEvent
import com.whatto.bcm.domain.admin.AllowanceRevocationEventStatus
import com.whatto.bcm.domain.admin.AllowanceRevocationExecution
import com.whatto.bcm.domain.admin.AllowanceRevocationLifecycle
import com.whatto.bcm.domain.admin.AllowanceRevocationRepository
import com.whatto.bcm.domain.admin.AllowanceRevocationTarget
import com.whatto.bcm.domain.admin.AllowanceRevocationView
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Repository
class AllowanceRevocationJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : AllowanceRevocationRepository {
    override fun insertExecution(
        execution: AllowanceRevocationExecution,
        targets: List<AllowanceRevocationTarget>,
    ): AllowanceRevocationExecution {
        require(targets.size == execution.itemCount) { "allowance revocation target count does not match execution" }
        try {
            jdbc.update(
                """
                INSERT INTO bcm_alwnc_rvok_exec_l
                  (rvok_exec_id, ctrt_vrsn_id, ctrt_bind_rvsn, ntwk_cd, swp_ctrt_addr,
                   tgt_snps_hash, item_cnt, idmp_key, reg_dttm,
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES
                  (:executionId, :contractVersionId, :bindingRevision, :network, :sweepContractAddress,
                   :snapshotHash, :itemCount, :idempotencyKey, :registeredAt,
                   :employeeNo, :branchCode, :employeeNo, :branchCode)
                """.trimIndent(),
                execution.params(),
            )
            targets.forEach(::insertTarget)
        } catch (exception: DuplicateKeyException) {
            throw ConflictException("allowanceRevocationExecution", execution.executionId, exception)
        }
        return execution.copy(registeredBy = execution.registeredBy.copy(roles = emptySet()))
    }

    override fun findExecution(executionId: String): AllowanceRevocationView? {
        val execution = findExecution("rvok_exec_id = :value", executionId) ?: return null
        val targets =
            jdbc.query(
                "$TARGET_SELECT WHERE rvok_exec_id = :executionId ORDER BY item_seq",
                mapOf("executionId" to executionId),
            ) { rs, _ -> rs.toTarget() }
        val events =
            jdbc.query(
                "$EVENT_SELECT WHERE rvok_exec_id = :executionId ORDER BY item_seq, evt_seq",
                mapOf("executionId" to executionId),
            ) { rs, _ -> rs.toEvent() }
        return AllowanceRevocationView(
            execution,
            targets,
            events,
            AllowanceRevocationLifecycle.executionStatus(execution.itemCount, events),
        )
    }

    override fun findExecutionByIdempotency(
        employeeNo: String,
        idempotencyKey: String,
    ): AllowanceRevocationExecution? =
        jdbc
            .query(
                "$EXECUTION_SELECT WHERE frst_reg_empno = :employeeNo AND idmp_key = :idempotencyKey",
                mapOf("employeeNo" to employeeNo, "idempotencyKey" to idempotencyKey),
            ) { rs, _ -> rs.toExecution() }
            .firstOrNull()

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
        try {
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
        } catch (exception: DuplicateKeyException) {
            throw ConflictException("allowanceRevocationAction", "${request.lifecycle.requestId}:$idempotencyKey", exception)
        }
    }

    override fun appendEvent(event: AllowanceRevocationEvent): AllowanceRevocationEvent {
        try {
            jdbc.update(
                """
                INSERT INTO bcm_alwnc_rvok_evt_l
                  (rvok_exec_id, item_seq, evt_seq, rvok_stcd, ext_tx_id, vndr_tx_id,
                   obs_alwnc, obs_payload, obs_hash, err_cd, occr_dttm,
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES
                  (:executionId, :itemSequence, :eventSequence, :status, :externalTransactionId, :vendorTransactionId,
                   :observedAllowance, CAST(:observationPayload AS jsonb), :observationHash, :errorCode, :occurredAt,
                   :employeeNo, :branchCode, :employeeNo, :branchCode)
                """.trimIndent(),
                mapOf(
                    "executionId" to event.executionId,
                    "itemSequence" to event.itemSequence,
                    "eventSequence" to event.eventSequence,
                    "status" to event.status.name,
                    "externalTransactionId" to event.externalTransactionId,
                    "vendorTransactionId" to event.vendorTransactionId,
                    "observedAllowance" to event.observedAllowance,
                    "observationPayload" to event.observationPayload,
                    "observationHash" to event.observationHash,
                    "errorCode" to event.errorCode,
                    "occurredAt" to event.occurredAt.coreDateTime(),
                    "employeeNo" to event.actor.employeeNo,
                    "branchCode" to event.actor.branchCode,
                ),
            )
        } catch (exception: DuplicateKeyException) {
            throw ConflictException(
                "allowanceRevocationEvent",
                "${event.executionId}:${event.itemSequence}:${event.eventSequence}",
                exception,
            )
        }
        return event.copy(actor = event.actor.copy(roles = emptySet()))
    }

    private fun findExecution(
        condition: String,
        value: String,
    ): AllowanceRevocationExecution? =
        jdbc
            .query("$EXECUTION_SELECT WHERE $condition", mapOf("value" to value)) { rs, _ -> rs.toExecution() }
            .firstOrNull()

    private fun insertTarget(target: AllowanceRevocationTarget) {
        jdbc.update(
            """
            INSERT INTO bcm_alwnc_rvok_item_l
              (rvok_exec_id, item_seq, acnt_id, ntwk_cd, tkn_smbl, swp_ctrt_addr,
               src_vlt_id, ownr_addr, tkn_ctrt_addr, bfr_obs_alwnc, ext_tx_id, req_hash,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            SELECT
              :executionId, :itemSequence, :accountId, :network, :symbol, :sweepContractAddress,
              :sourceVaultId, :ownerAddress, :tokenContractAddress, :beforeObservedAllowance,
              :externalTransactionId, :requestHash,
              execution.frst_reg_empno, execution.frst_reg_brcd,
              execution.frst_reg_empno, execution.frst_reg_brcd
              FROM bcm_alwnc_rvok_exec_l execution
             WHERE execution.rvok_exec_id = :executionId
            """.trimIndent(),
            mapOf(
                "executionId" to target.executionId,
                "itemSequence" to target.itemSequence,
                "accountId" to target.accountId,
                "network" to target.network,
                "symbol" to target.symbol,
                "sweepContractAddress" to target.sweepContractAddress,
                "sourceVaultId" to target.sourceVaultId,
                "ownerAddress" to target.ownerAddress,
                "tokenContractAddress" to target.tokenContractAddress,
                "beforeObservedAllowance" to target.beforeObservedAllowance,
                "externalTransactionId" to target.externalTransactionId,
                "requestHash" to target.requestHash,
            ),
        )
    }

    private fun ResultSet.toExecution() =
        AllowanceRevocationExecution(
            getString("rvok_exec_id"),
            getString("ctrt_vrsn_id"),
            getLong("ctrt_bind_rvsn"),
            getString("ntwk_cd"),
            getString("swp_ctrt_addr"),
            getString("tgt_snps_hash"),
            getInt("item_cnt"),
            getString("idmp_key"),
            getString("reg_dttm").instant(),
            AdminActor(getString("frst_reg_empno"), getString("frst_reg_brcd"), emptySet()),
        )

    private fun ResultSet.toTarget() =
        AllowanceRevocationTarget(
            getString("rvok_exec_id"),
            getInt("item_seq"),
            getString("acnt_id"),
            getString("ntwk_cd"),
            getString("tkn_smbl"),
            getString("swp_ctrt_addr"),
            getString("src_vlt_id"),
            getString("ownr_addr"),
            getString("tkn_ctrt_addr"),
            getBigDecimal("bfr_obs_alwnc"),
            getString("ext_tx_id"),
            getString("req_hash"),
        )

    private fun ResultSet.toEvent() =
        AllowanceRevocationEvent(
            getString("rvok_exec_id"),
            getInt("item_seq"),
            getInt("evt_seq"),
            AllowanceRevocationEventStatus.valueOf(getString("rvok_stcd")),
            getString("ext_tx_id"),
            getString("vndr_tx_id"),
            getBigDecimal("obs_alwnc"),
            getString("obs_payload"),
            getString("obs_hash"),
            getString("err_cd"),
            getString("occr_dttm").instant(),
            AdminActor(getString("frst_reg_empno"), getString("frst_reg_brcd"), emptySet()),
        )

    private fun AllowanceRevocationExecution.params() =
        mapOf(
            "executionId" to executionId,
            "contractVersionId" to contractVersionId,
            "bindingRevision" to contractBindingRevision,
            "network" to network,
            "sweepContractAddress" to sweepContractAddress,
            "snapshotHash" to targetSnapshotHash,
            "itemCount" to itemCount,
            "idempotencyKey" to idempotencyKey,
            "registeredAt" to registeredAt.coreDateTime(),
            "employeeNo" to registeredBy.employeeNo,
            "branchCode" to registeredBy.branchCode,
        )

    private fun Instant.coreDateTime(): String = CoreDateTimes.format(LocalDateTime.ofInstant(this, ZoneOffset.UTC))

    private fun String.instant(): Instant = CoreDateTimes.parse(this).toInstant(ZoneOffset.UTC)

    companion object {
        private val EXECUTION_SELECT =
            """
            SELECT rvok_exec_id, ctrt_vrsn_id, ctrt_bind_rvsn, ntwk_cd, swp_ctrt_addr,
                   tgt_snps_hash, item_cnt, idmp_key, reg_dttm, frst_reg_empno, frst_reg_brcd
              FROM bcm_alwnc_rvok_exec_l
            """.trimIndent()

        private val TARGET_SELECT =
            """
            SELECT rvok_exec_id, item_seq, acnt_id, ntwk_cd, tkn_smbl, swp_ctrt_addr,
                   src_vlt_id, ownr_addr, tkn_ctrt_addr, bfr_obs_alwnc, ext_tx_id, req_hash
              FROM bcm_alwnc_rvok_item_l
            """.trimIndent()

        private val EVENT_SELECT =
            """
            SELECT rvok_exec_id, item_seq, evt_seq, rvok_stcd, ext_tx_id, vndr_tx_id,
                   obs_alwnc, obs_payload::text AS obs_payload, obs_hash, err_cd, occr_dttm,
                   frst_reg_empno, frst_reg_brcd
              FROM bcm_alwnc_rvok_evt_l
            """.trimIndent()
    }
}
