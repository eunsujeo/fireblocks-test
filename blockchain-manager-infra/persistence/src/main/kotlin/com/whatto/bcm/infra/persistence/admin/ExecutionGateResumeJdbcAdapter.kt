package com.whatto.bcm.infra.persistence.admin

import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminChangeRequest
import com.whatto.bcm.domain.admin.AdminPolicyRepository
import com.whatto.bcm.domain.admin.EmergencyContractObservation
import com.whatto.bcm.domain.admin.ExecutionGateResumeCheck
import com.whatto.bcm.domain.admin.ExecutionGateResumeCheckCandidate
import com.whatto.bcm.domain.admin.ExecutionGateResumeCheckEvaluation
import com.whatto.bcm.domain.admin.ExecutionGateResumeCheckStatus
import com.whatto.bcm.domain.admin.ExecutionGateResumeRepository
import com.whatto.bcm.domain.admin.ExecutionGateResumeSnapshot
import com.whatto.bcm.domain.admin.ExecutionGateResumeView
import com.whatto.bcm.domain.admin.ExecutionGateType
import com.whatto.bcm.domain.admin.TapBatchObservation
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Repository
class ExecutionGateResumeJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
    private val policies: AdminPolicyRepository,
) : ExecutionGateResumeRepository {
    override fun insertSnapshot(snapshot: ExecutionGateResumeSnapshot): ExecutionGateResumeSnapshot {
        val inserted =
            jdbc.update(
                """
                INSERT INTO bcm_exec_gate_rsm_l
                  (rsm_id, ntwk_cd, gate_dvcd, stop_gate_evt_id, ctrt_vrsn_id, ctrt_bind_rvsn,
                   ctrt_evdc_id, rvok_exec_id, exp_oprtr_hash, cause_evdc_uri, cause_evdc_hash,
                   tgt_snps_hash, reg_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES
                  (:resumeId, :network, :type, :stoppedEventId, :contractVersionId, :bindingRevision,
                   :contractEvidenceId, :revocationExecutionId, :expectedOperatorSetHash, :causeEvidenceUri,
                   :causeEvidenceHash, :targetSnapshotHash, :registeredAt, :employeeNo, :branchCode,
                   :employeeNo, :branchCode)
                ON CONFLICT DO NOTHING
                """.trimIndent(),
                snapshot.parameters(),
            )
        if (inserted != 1) throw ConflictException("executionGateResume", snapshot.stoppedEventId)
        return snapshot
    }

    override fun findSnapshot(resumeId: String): ExecutionGateResumeSnapshot? =
        jdbc
            .query("$SNAPSHOT_SELECT WHERE resume.rsm_id = :resumeId", mapOf("resumeId" to resumeId)) { rs, _ ->
                rs.toSnapshot()
            }.firstOrNull()

    override fun findViewByRequest(requestId: String): ExecutionGateResumeView? {
        val request = policies.findChangeRequest(requestId) ?: return null
        val snapshot = findSnapshot(request.lifecycle.targetVersionId) ?: return null
        return ExecutionGateResumeView(snapshot, request, findChecks(snapshot.resumeId))
    }

    override fun appendCheck(check: ExecutionGateResumeCheck): ExecutionGateResumeCheck {
        val issues = check.evaluation.issues.toIssueJson()
        val inserted =
            jdbc.update(
                """
                INSERT INTO bcm_exec_gate_rsm_chk_l
                  (rsm_chk_id, rsm_id, chk_seq, snps_hash, tap_src_id, tap_blck_yn, tap_obs_dttm,
                   pin_blck_no, rpc1_id, rpc1_blck_no, rpc1_pause_yn, rpc1_oprtr_hash, rpc1_obs_dttm,
                   rpc2_id, rpc2_blck_no, rpc2_pause_yn, rpc2_oprtr_hash, rpc2_obs_dttm,
                   chk_stcd, issue_payload, issue_hash, obs_dttm, vld_until_dttm, idmp_key,
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES
                  (:checkId, :resumeId, :sequence, :snapshotHash, :tapSourceId, :tapBlocked, :tapObservedAt,
                   :pinnedBlockNumber, :firstEndpointId, :firstBlockNumber, :firstPaused,
                   :firstOperatorSetHash, :firstObservedAt, :secondEndpointId, :secondBlockNumber,
                   :secondPaused, :secondOperatorSetHash, :secondObservedAt, :status,
                   CAST(:issuePayload AS jsonb), :issueHash, :observedAt, :validUntil, :idempotencyKey,
                   :employeeNo, :branchCode, :employeeNo, :branchCode)
                ON CONFLICT DO NOTHING
                """.trimIndent(),
                check.parameters(issues),
            )
        if (inserted != 1) throw ConflictException("executionGateResumeCheck", check.idempotencyKey)
        return check
    }

    override fun findCheckByIdempotency(
        employeeNo: String,
        idempotencyKey: String,
    ): ExecutionGateResumeCheck? =
        jdbc
            .query(
                "$CHECK_SELECT WHERE check_row.frst_reg_empno = :employeeNo AND check_row.idmp_key = :idempotencyKey",
                mapOf("employeeNo" to employeeNo, "idempotencyKey" to idempotencyKey),
            ) { rs, _ -> rs.toCheck() }
            .firstOrNull()

    override fun findLatestCheck(resumeId: String): ExecutionGateResumeCheck? =
        jdbc
            .query(
                "$CHECK_SELECT WHERE check_row.rsm_id = :resumeId ORDER BY check_row.chk_seq DESC LIMIT 1",
                mapOf("resumeId" to resumeId),
            ) { rs, _ -> rs.toCheck() }
            .firstOrNull()

    override fun insertResumeIntent(
        actionId: String,
        correlationId: String,
        request: AdminChangeRequest,
        idempotencyKey: String,
        requestHash: String,
        expectedState: String,
        expectedStateHash: String,
        actor: AdminActor,
        now: Instant,
    ) = insertAction(
        actionId,
        correlationId,
        request,
        "INTENT",
        idempotencyKey,
        requestHash,
        expectedState,
        expectedStateHash,
        null,
        null,
        actor,
        now,
    )

    override fun insertResumeSuccess(
        actionId: String,
        correlationId: String,
        request: AdminChangeRequest,
        idempotencyKey: String,
        requestHash: String,
        expectedState: String,
        expectedStateHash: String,
        observedState: String,
        observedStateHash: String,
        actor: AdminActor,
        now: Instant,
    ) = insertAction(
        actionId,
        correlationId,
        request,
        "SUCCEEDED",
        idempotencyKey,
        requestHash,
        expectedState,
        expectedStateHash,
        observedState,
        observedStateHash,
        actor,
        now,
    )

    private fun findChecks(resumeId: String): List<ExecutionGateResumeCheck> =
        jdbc.query(
            "$CHECK_SELECT WHERE check_row.rsm_id = :resumeId ORDER BY check_row.chk_seq",
            mapOf("resumeId" to resumeId),
        ) { rs, _ -> rs.toCheck() }

    private fun insertAction(
        actionId: String,
        correlationId: String,
        request: AdminChangeRequest,
        status: String,
        idempotencyKey: String,
        requestHash: String,
        expectedState: String,
        expectedStateHash: String,
        observedState: String?,
        observedStateHash: String?,
        actor: AdminActor,
        now: Instant,
    ) {
        jdbc.update(
            """
            INSERT INTO bcm_adm_actn_l
              (actn_id, corr_id, req_id, actn_dvcd, actn_stcd, try_seq, idmp_key,
               req_hash, rsp_cd, rsp_hash, exp_state, exp_state_hash, obs_state, obs_state_hash,
               occr_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (:actionId, :correlationId, :requestId, 'RESUME', :status, 1, :idempotencyKey,
               :requestHash, :responseCode, :responseHash, CAST(:expectedState AS jsonb), :expectedStateHash,
               CAST(:observedState AS jsonb), :observedStateHash, :now, :employeeNo, :branchCode,
               :employeeNo, :branchCode)
            """.trimIndent(),
            mapOf(
                "actionId" to actionId,
                "correlationId" to correlationId,
                "requestId" to request.lifecycle.requestId,
                "status" to status,
                "idempotencyKey" to idempotencyKey,
                "requestHash" to requestHash,
                "responseCode" to if (status == "SUCCEEDED") "RESUMED" else null,
                "responseHash" to observedStateHash,
                "expectedState" to expectedState,
                "expectedStateHash" to expectedStateHash,
                "observedState" to observedState,
                "observedStateHash" to observedStateHash,
                "now" to now.coreDateTime(),
                "employeeNo" to actor.employeeNo,
                "branchCode" to actor.branchCode,
            ),
        )
    }

    private fun ExecutionGateResumeSnapshot.parameters() =
        mapOf(
            "resumeId" to resumeId,
            "network" to network,
            "type" to type.name,
            "stoppedEventId" to stoppedEventId,
            "contractVersionId" to contractVersionId,
            "bindingRevision" to contractBindingRevision,
            "contractEvidenceId" to contractEvidenceId,
            "revocationExecutionId" to revocationExecutionId,
            "expectedOperatorSetHash" to expectedOperatorSetHash,
            "causeEvidenceUri" to causeEvidenceUri,
            "causeEvidenceHash" to causeEvidenceHash,
            "targetSnapshotHash" to targetSnapshotHash,
            "registeredAt" to registeredAt.coreDateTime(),
            "employeeNo" to registeredBy.employeeNo,
            "branchCode" to registeredBy.branchCode,
        )

    private fun ExecutionGateResumeCheck.parameters(issuePayload: String) =
        mapOf(
            "checkId" to checkId,
            "resumeId" to resumeId,
            "sequence" to sequence,
            "snapshotHash" to snapshotHash,
            "tapSourceId" to candidate.tapSourceId,
            "tapBlocked" to candidate.tap?.blocked.toYn(),
            "tapObservedAt" to candidate.tap?.observedAt.coreDateTime(),
            "pinnedBlockNumber" to candidate.pinnedBlockNumber,
            "firstEndpointId" to candidate.firstEndpointId,
            "firstBlockNumber" to candidate.first?.blockNumber,
            "firstPaused" to candidate.first?.paused.toYn(),
            "firstOperatorSetHash" to candidate.first?.operatorSetHash,
            "firstObservedAt" to candidate.first?.observedAt.coreDateTime(),
            "secondEndpointId" to candidate.secondEndpointId,
            "secondBlockNumber" to candidate.second?.blockNumber,
            "secondPaused" to candidate.second?.paused.toYn(),
            "secondOperatorSetHash" to candidate.second?.operatorSetHash,
            "secondObservedAt" to candidate.second?.observedAt.coreDateTime(),
            "status" to evaluation.status.name,
            "issuePayload" to issuePayload,
            "issueHash" to sha256(issuePayload),
            "observedAt" to candidate.observedAt.coreDateTime(),
            "validUntil" to candidate.validUntil.coreDateTime(),
            "idempotencyKey" to idempotencyKey,
            "employeeNo" to registeredBy.employeeNo,
            "branchCode" to registeredBy.branchCode,
        )

    private companion object {
        const val SNAPSHOT_SELECT =
            """
            SELECT resume.rsm_id, resume.ntwk_cd, resume.gate_dvcd, resume.stop_gate_evt_id,
                   resume.ctrt_vrsn_id, resume.ctrt_bind_rvsn, resume.ctrt_evdc_id, resume.rvok_exec_id,
                   resume.exp_oprtr_hash, resume.cause_evdc_uri, resume.cause_evdc_hash,
                   resume.tgt_snps_hash, resume.reg_dttm, resume.frst_reg_empno, resume.frst_reg_brcd
              FROM bcm_exec_gate_rsm_l resume
            """
        const val CHECK_SELECT =
            """
            SELECT check_row.rsm_chk_id, check_row.rsm_id, check_row.chk_seq, check_row.snps_hash,
                   check_row.tap_src_id, check_row.tap_blck_yn, check_row.tap_obs_dttm,
                   check_row.pin_blck_no, check_row.rpc1_id, check_row.rpc1_blck_no,
                   check_row.rpc1_pause_yn, check_row.rpc1_oprtr_hash, check_row.rpc1_obs_dttm,
                   check_row.rpc2_id, check_row.rpc2_blck_no, check_row.rpc2_pause_yn,
                   check_row.rpc2_oprtr_hash, check_row.rpc2_obs_dttm, check_row.chk_stcd,
                   ARRAY(SELECT jsonb_array_elements_text(check_row.issue_payload)) AS issue_codes,
                   check_row.obs_dttm, check_row.vld_until_dttm, check_row.idmp_key,
                   check_row.frst_reg_empno, check_row.frst_reg_brcd, resume.exp_oprtr_hash
              FROM bcm_exec_gate_rsm_chk_l check_row
              JOIN bcm_exec_gate_rsm_l resume ON resume.rsm_id = check_row.rsm_id
            """

        fun ResultSet.toSnapshot() =
            ExecutionGateResumeSnapshot(
                getString("rsm_id"),
                getString("ntwk_cd"),
                ExecutionGateType.valueOf(getString("gate_dvcd")),
                getString("stop_gate_evt_id"),
                getString("ctrt_vrsn_id"),
                getLong("ctrt_bind_rvsn"),
                getString("ctrt_evdc_id"),
                getString("rvok_exec_id"),
                getString("exp_oprtr_hash"),
                getString("cause_evdc_uri"),
                getString("cause_evdc_hash"),
                getString("tgt_snps_hash"),
                getString("reg_dttm").instant(),
                AdminActor(getString("frst_reg_empno"), getString("frst_reg_brcd"), emptySet()),
            )

        fun ResultSet.toCheck(): ExecutionGateResumeCheck {
            val status = ExecutionGateResumeCheckStatus.valueOf(getString("chk_stcd"))
            val issues = (getArray("issue_codes").array as Array<*>).map(Any?::toString)
            val candidate =
                ExecutionGateResumeCheckCandidate(
                    getString("tap_src_id"),
                    getString("tap_blck_yn")?.let { TapBatchObservation(it == "Y", getString("tap_obs_dttm").instant()) },
                    getBigDecimal("pin_blck_no").toBigIntegerExact(),
                    getString("exp_oprtr_hash"),
                    getString("rpc1_id"),
                    rpc("rpc1"),
                    getString("rpc2_id"),
                    rpc("rpc2"),
                    if (status == ExecutionGateResumeCheckStatus.ERROR) issues else emptyList(),
                    getString("obs_dttm").instant(),
                    getString("vld_until_dttm").instant(),
                )
            return ExecutionGateResumeCheck(
                getString("rsm_chk_id"),
                getString("rsm_id"),
                getInt("chk_seq"),
                getString("snps_hash"),
                candidate,
                ExecutionGateResumeCheckEvaluation(status, issues),
                getString("idmp_key"),
                AdminActor(getString("frst_reg_empno"), getString("frst_reg_brcd"), emptySet()),
            )
        }

        fun ResultSet.rpc(prefix: String): EmergencyContractObservation? {
            val block = getBigDecimal("${prefix}_blck_no")?.toBigIntegerExact() ?: return null
            val paused = getString("${prefix}_pause_yn") ?: return null
            val operators = getString("${prefix}_oprtr_hash") ?: return null
            val observedAt = getString("${prefix}_obs_dttm") ?: return null
            return EmergencyContractObservation(block, paused == "Y", operators, observedAt.instant())
        }

        fun List<String>.toIssueJson(): String {
            forEach { require(it.matches(Regex("[A-Z0-9_]+"))) { "invalid resume issue code" } }
            return joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        }

        fun Boolean?.toYn(): String? = this?.let { if (it) "Y" else "N" }

        fun Instant?.coreDateTime(): String? = this?.let { CoreDateTimes.format(LocalDateTime.ofInstant(it, ZoneOffset.UTC)) }

        fun String.instant(): Instant = CoreDateTimes.parse(this).toInstant(ZoneOffset.UTC)

        fun sha256(value: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
