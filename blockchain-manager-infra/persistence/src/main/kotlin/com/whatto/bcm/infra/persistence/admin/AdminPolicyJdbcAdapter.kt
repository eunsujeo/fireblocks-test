package com.whatto.bcm.infra.persistence.admin

import com.whatto.bcm.domain.admin.AdminActivationRecord
import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminChangeRequest
import com.whatto.bcm.domain.admin.AdminDecisionRecord
import com.whatto.bcm.domain.admin.AdminPolicyBinding
import com.whatto.bcm.domain.admin.AdminPolicyRepository
import com.whatto.bcm.domain.admin.AdminPolicyVersion
import com.whatto.bcm.domain.admin.AdminRole
import com.whatto.bcm.domain.admin.ChangeDecision
import com.whatto.bcm.domain.admin.ChangeRisk
import com.whatto.bcm.domain.admin.ChangeTargetType
import com.whatto.bcm.domain.admin.PolicyChangeRequest
import com.whatto.bcm.domain.admin.PolicyDecision
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Repository
class AdminPolicyJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : AdminPolicyRepository {
    override fun insertPolicyVersion(version: AdminPolicyVersion): AdminPolicyVersion {
        jdbc.update(
            """
            INSERT INTO bcm_plcy_vrsn_l
              (plcy_vrsn_id, plcy_scope_id, vrsn_no, plcy_schm_vrsn, base_plcy_vrsn_id,
               ctrt_vrsn_id, plcy_payload, plcy_hash, ceiling_snps, ceiling_hash,
               ceiling_pass_yn, reg_dttm, frst_reg_empno, frst_reg_brcd,
               last_chng_empno, last_chng_brcd)
            VALUES
              (:versionId, :scopeId, :versionNumber, :schemaVersion, :baseVersionId,
               :contractVersionId, CAST(:payload AS jsonb), :policyHash, CAST(:ceilingSnapshot AS jsonb),
               :ceilingHash, :ceilingPassed, :registeredAt, :employeeNo, :branchCode,
               :employeeNo, :branchCode)
            """.trimIndent(),
            mapOf(
                "versionId" to version.versionId,
                "scopeId" to version.scopeId,
                "versionNumber" to version.versionNumber,
                "schemaVersion" to version.schemaVersion,
                "baseVersionId" to version.baseVersionId,
                "contractVersionId" to version.contractVersionId,
                "payload" to version.payload,
                "policyHash" to version.policyHash,
                "ceilingSnapshot" to version.ceilingSnapshot,
                "ceilingHash" to version.ceilingHash,
                "ceilingPassed" to version.ceilingPassed.toYn(),
                "registeredAt" to version.registeredAt.coreDateTime(),
                "employeeNo" to version.registeredBy.employeeNo,
                "branchCode" to version.registeredBy.branchCode,
            ),
        )
        return version
    }

    override fun findPolicyVersion(versionId: String): AdminPolicyVersion? =
        jdbc
            .query(
                "$POLICY_SELECT WHERE plcy_vrsn_id = :versionId",
                mapOf("versionId" to versionId),
            ) { rs, _ -> rs.toPolicyVersion() }
            .firstOrNull()

    override fun findLatestPolicyVersion(scopeId: String): AdminPolicyVersion? =
        jdbc
            .query(
                "$POLICY_SELECT WHERE plcy_scope_id = :scopeId ORDER BY vrsn_no DESC LIMIT 1",
                mapOf("scopeId" to scopeId),
            ) { rs, _ -> rs.toPolicyVersion() }
            .firstOrNull()

    override fun initializePolicyBinding(
        scopeId: String,
        actor: AdminActor,
        now: Instant,
    ): AdminPolicyBinding {
        jdbc.update(
            """
            INSERT INTO bcm_plcy_bind_m
              (plcy_scope_id, bind_rvsn, bind_snps_hash, reg_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (:scopeId, 0, :snapshotHash, :now, :now, :employeeNo, :branchCode, :employeeNo, :branchCode)
            ON CONFLICT (plcy_scope_id) DO NOTHING
            """.trimIndent(),
            mapOf(
                "scopeId" to scopeId,
                "snapshotHash" to EMPTY_SNAPSHOT_HASH,
                "now" to now.coreDateTime(),
                "employeeNo" to actor.employeeNo,
                "branchCode" to actor.branchCode,
            ),
        )
        return requireNotNull(findPolicyBinding(scopeId, forUpdate = false))
    }

    override fun insertChangeRequest(request: AdminChangeRequest): AdminChangeRequest {
        val policyTarget = request.targetType == ChangeTargetType.POLICY
        val contractTarget = request.targetType == ChangeTargetType.CONTRACT
        val bandTarget = request.targetType == ChangeTargetType.BAND_S
        val allowanceRevokeTarget = request.targetType == ChangeTargetType.ALLOWANCE_REVOKE
        val executionGateTarget = request.targetType == ChangeTargetType.EXECUTION_GATE
        jdbc.update(
            """
            INSERT INTO bcm_chng_req_l
              (req_id, tgt_dvcd, scope_id, bfr_ctrt_vrsn_id, aft_ctrt_vrsn_id,
               bfr_plcy_vrsn_id, aft_plcy_vrsn_id, aft_bnds_prop_id, aft_alwnc_rvok_id, aft_gate_rsm_id,
               evdc_id, risk_dvcd, base_bind_rvsn,
               tgt_snps_hash, diff_payload, diff_hash, impact_payload, impact_hash,
               req_rsn, work_tckt, idmp_key, req_role_dvcd, req_dttm, expr_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (:requestId, :targetType, :scopeId, :beforeContractVersionId, :afterContractVersionId,
               :beforePolicyVersionId, :afterPolicyVersionId, :afterBandProposalId, :afterAllowanceRevokeId,
               :afterExecutionGateResumeId,
               :evidenceId, :risk, :baseRevision,
               :snapshotHash, CAST(:diffPayload AS jsonb), :diffHash, CAST(:impactPayload AS jsonb), :impactHash,
               :reason, :workTicket, :idempotencyKey, :requestedRole, :requestedAt, :expiresAt,
               :employeeNo, :branchCode, :employeeNo, :branchCode)
            """.trimIndent(),
            mapOf(
                "requestId" to request.lifecycle.requestId,
                "targetType" to request.targetType.name,
                "scopeId" to request.scopeId,
                "beforeContractVersionId" to request.beforeVersionId.takeIf { contractTarget },
                "afterContractVersionId" to request.lifecycle.targetVersionId.takeIf { contractTarget },
                "beforePolicyVersionId" to request.beforeVersionId.takeIf { policyTarget },
                "afterPolicyVersionId" to request.lifecycle.targetVersionId.takeIf { policyTarget },
                "afterBandProposalId" to request.lifecycle.targetVersionId.takeIf { bandTarget },
                "afterAllowanceRevokeId" to request.lifecycle.targetVersionId.takeIf { allowanceRevokeTarget },
                "afterExecutionGateResumeId" to request.lifecycle.targetVersionId.takeIf { executionGateTarget },
                "evidenceId" to request.evidenceId,
                "risk" to request.lifecycle.risk.name,
                "baseRevision" to request.lifecycle.baseBindingRevision,
                "snapshotHash" to request.lifecycle.targetSnapshotHash,
                "diffPayload" to request.diffPayload,
                "diffHash" to request.diffHash,
                "impactPayload" to request.impactPayload,
                "impactHash" to request.impactHash,
                "reason" to request.reason,
                "workTicket" to request.workTicket,
                "idempotencyKey" to request.idempotencyKey,
                "requestedRole" to request.requestedRole.name,
                "requestedAt" to request.requestedAt.coreDateTime(),
                "expiresAt" to request.lifecycle.expiresAt.coreDateTime(),
                "employeeNo" to request.lifecycle.requester.employeeNo,
                "branchCode" to request.lifecycle.requester.branchCode,
            ),
        )
        return request
    }

    override fun findChangeRequest(requestId: String): AdminChangeRequest? =
        jdbc
            .query(
                "$REQUEST_SELECT WHERE req_id = :requestId",
                mapOf("requestId" to requestId),
            ) { rs, _ -> rs.toChangeRequest() }
            .firstOrNull()

    override fun findChangeRequestByIdempotency(
        employeeNo: String,
        idempotencyKey: String,
    ): AdminChangeRequest? =
        jdbc
            .query(
                "$REQUEST_SELECT WHERE frst_reg_empno = :employeeNo AND idmp_key = :idempotencyKey",
                mapOf("employeeNo" to employeeNo, "idempotencyKey" to idempotencyKey),
            ) { rs, _ -> rs.toChangeRequest() }
            .firstOrNull()

    override fun findDecisions(requestId: String): List<AdminDecisionRecord> =
        jdbc.query(
            """
            SELECT req_id, aprv_empno, aprv_brcd, aprv_role_dvcd, dcsn_dvcd,
                   dcsn_snps_hash, dcsn_opin, dcsn_dttm
              FROM bcm_chng_dcsn_l
             WHERE req_id = :requestId
             ORDER BY dcsn_dttm, aprv_empno
            """.trimIndent(),
            mapOf("requestId" to requestId),
        ) { rs, _ -> rs.toDecision() }

    override fun insertDecision(decision: AdminDecisionRecord): AdminDecisionRecord {
        val value = decision.decision
        val role = value.actor.decisionRole()
        jdbc.update(
            """
            INSERT INTO bcm_chng_dcsn_l
              (req_id, aprv_empno, aprv_brcd, aprv_role_dvcd, dcsn_dvcd, dcsn_snps_hash,
               dcsn_opin, dcsn_dttm, frst_reg_empno, frst_reg_brcd,
               last_chng_empno, last_chng_brcd)
            VALUES
              (:requestId, :employeeNo, :branchCode, :role, :decision, :snapshotHash,
               :opinion, :decidedAt, :employeeNo, :branchCode, :employeeNo, :branchCode)
            """.trimIndent(),
            mapOf(
                "requestId" to value.requestId,
                "employeeNo" to value.actor.employeeNo,
                "branchCode" to value.actor.branchCode,
                "role" to role.name,
                "decision" to value.decision.name,
                "snapshotHash" to value.snapshotHash,
                "opinion" to decision.opinion,
                "decidedAt" to value.decidedAt.coreDateTime(),
            ),
        )
        return decision
    }

    override fun lockPolicyBinding(scopeId: String): AdminPolicyBinding? = findPolicyBinding(scopeId, forUpdate = true)

    override fun isPolicyReady(versionId: String): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1
                FROM bcm_plcy_vrsn_l policy
               WHERE policy.plcy_vrsn_id = :versionId
                 AND policy.ceiling_pass_yn = 'Y'
                 AND (
                   policy.ctrt_vrsn_id IS NULL OR EXISTS (
                     SELECT 1
                       FROM bcm_ctrt_vrsn_l version
                       JOIN bcm_ctrt_bind_m binding
                         ON binding.ctrt_scope_id = version.ctrt_scope_id
                        AND binding.actv_ctrt_vrsn_id = version.ctrt_vrsn_id
                      WHERE version.ctrt_vrsn_id = policy.ctrt_vrsn_id
                   )
                 )
            )
            """.trimIndent(),
            mapOf("versionId" to versionId),
            Boolean::class.java,
        ) == true

    override fun findSuccessfulActivation(
        requestId: String,
        idempotencyKey: String,
    ): AdminActivationRecord? =
        jdbc
            .query(
                """
                SELECT action.actn_id, action.corr_id, action.req_id, action.idmp_key,
                       COALESCE(request.aft_plcy_vrsn_id, request.aft_ctrt_vrsn_id) AS target_vrsn_id,
                       COALESCE(policy_binding.bind_rvsn, contract_binding.bind_rvsn) AS bind_rvsn,
                       COALESCE(policy_binding.bind_snps_hash, contract_binding.bind_snps_hash) AS bind_snps_hash,
                       action.occr_dttm
                  FROM bcm_adm_actn_l action
                  JOIN bcm_chng_req_l request ON request.req_id = action.req_id
                  LEFT JOIN bcm_plcy_bind_m policy_binding
                    ON policy_binding.last_req_id = request.req_id
                   AND policy_binding.actv_plcy_vrsn_id = request.aft_plcy_vrsn_id
                  LEFT JOIN bcm_ctrt_bind_m contract_binding
                    ON contract_binding.last_req_id = request.req_id
                   AND contract_binding.actv_ctrt_vrsn_id = request.aft_ctrt_vrsn_id
                 WHERE action.req_id = :requestId
                   AND action.actn_dvcd = 'ACTIVATE'
                   AND action.actn_stcd = 'SUCCEEDED'
                   AND action.idmp_key = :idempotencyKey
                 ORDER BY action.occr_dttm DESC
                 LIMIT 1
                """.trimIndent(),
                mapOf("requestId" to requestId, "idempotencyKey" to idempotencyKey),
            ) { rs, _ -> rs.toActivation() }
            .firstOrNull()

    override fun insertActivationIntent(
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
        insertAction(
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
    }

    override fun activatePolicyBinding(
        request: AdminChangeRequest,
        actor: AdminActor,
        now: Instant,
    ): AdminPolicyBinding {
        val changed =
            jdbc.update(
                """
                UPDATE bcm_plcy_bind_m
                   SET actv_plcy_vrsn_id = :versionId,
                       bind_rvsn = bind_rvsn + 1,
                       last_req_id = :requestId,
                       bind_snps_hash = :snapshotHash,
                       last_chng_dttm = :now,
                       last_chng_empno = :employeeNo,
                       last_chng_brcd = :branchCode
                 WHERE plcy_scope_id = :scopeId
                   AND bind_rvsn = :baseRevision
                """.trimIndent(),
                mapOf(
                    "versionId" to request.lifecycle.targetVersionId,
                    "requestId" to request.lifecycle.requestId,
                    "snapshotHash" to request.lifecycle.targetSnapshotHash,
                    "now" to now.coreDateTime(),
                    "employeeNo" to actor.employeeNo,
                    "branchCode" to actor.branchCode,
                    "scopeId" to request.scopeId,
                    "baseRevision" to request.lifecycle.baseBindingRevision,
                ),
            )
        check(changed == 1) { "policy binding changed concurrently for ${request.scopeId}" }
        return requireNotNull(findPolicyBinding(request.scopeId, forUpdate = false))
    }

    override fun insertActivationSuccess(
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
    ): AdminActivationRecord {
        insertAction(
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
        val binding = requireNotNull(findActivatedBinding(request))
        return AdminActivationRecord(
            actionId,
            correlationId,
            request.lifecycle.requestId,
            idempotencyKey,
            request.lifecycle.targetVersionId,
            binding.revision,
            binding.snapshotHash,
            now,
        )
    }

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
               req_hash, rsp_cd, rsp_hash, exp_state, exp_state_hash,
               obs_state, obs_state_hash, occr_dttm, frst_reg_empno, frst_reg_brcd,
               last_chng_empno, last_chng_brcd)
            VALUES
              (:actionId, :correlationId, :requestId, 'ACTIVATE', :status, 1, :idempotencyKey,
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
                "responseCode" to if (status == "SUCCEEDED") "ACTIVATED" else null,
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

    private fun findPolicyBinding(
        scopeId: String,
        forUpdate: Boolean,
    ): AdminPolicyBinding? =
        jdbc
            .query(
                """
                SELECT plcy_scope_id, actv_plcy_vrsn_id, bind_rvsn, bind_snps_hash
                  FROM bcm_plcy_bind_m
                 WHERE plcy_scope_id = :scopeId
                ${if (forUpdate) " FOR UPDATE" else ""}
                """.trimIndent(),
                mapOf("scopeId" to scopeId),
            ) { rs, _ ->
                AdminPolicyBinding(
                    rs.getString("plcy_scope_id"),
                    rs.getString("actv_plcy_vrsn_id"),
                    rs.getLong("bind_rvsn"),
                    rs.getString("bind_snps_hash"),
                )
            }.firstOrNull()

    private fun findActivatedBinding(request: AdminChangeRequest): AdminPolicyBinding? =
        when (request.targetType) {
            ChangeTargetType.POLICY -> findPolicyBinding(request.scopeId, forUpdate = false)
            ChangeTargetType.CONTRACT ->
                jdbc
                    .query(
                        """
                        SELECT ctrt_scope_id, actv_ctrt_vrsn_id, bind_rvsn, bind_snps_hash
                          FROM bcm_ctrt_bind_m
                         WHERE ctrt_scope_id = :scopeId
                        """.trimIndent(),
                        mapOf("scopeId" to request.scopeId),
                    ) { rs, _ ->
                        AdminPolicyBinding(
                            rs.getString("ctrt_scope_id"),
                            rs.getString("actv_ctrt_vrsn_id"),
                            rs.getLong("bind_rvsn"),
                            rs.getString("bind_snps_hash"),
                        )
                    }.firstOrNull()
            ChangeTargetType.BAND_S,
            ChangeTargetType.ALLOWANCE_REVOKE,
            ChangeTargetType.EXECUTION_GATE,
            -> error("execution requests do not activate a binding")
        }

    private fun ResultSet.toChangeRequest(): AdminChangeRequest {
        val role = AdminRole.valueOf(getString("req_role_dvcd"))
        val targetType = ChangeTargetType.valueOf(getString("tgt_dvcd"))
        val targetIdColumn =
            when (targetType) {
                ChangeTargetType.POLICY -> "aft_plcy_vrsn_id"
                ChangeTargetType.CONTRACT -> "aft_ctrt_vrsn_id"
                ChangeTargetType.BAND_S -> "aft_bnds_prop_id"
                ChangeTargetType.ALLOWANCE_REVOKE -> "aft_alwnc_rvok_id"
                ChangeTargetType.EXECUTION_GATE -> "aft_gate_rsm_id"
            }
        val beforeIdColumn =
            when (targetType) {
                ChangeTargetType.POLICY -> "bfr_plcy_vrsn_id"
                ChangeTargetType.CONTRACT -> "bfr_ctrt_vrsn_id"
                ChangeTargetType.BAND_S -> null
                ChangeTargetType.ALLOWANCE_REVOKE -> null
                ChangeTargetType.EXECUTION_GATE -> null
            }
        val requester = AdminActor(getString("frst_reg_empno"), getString("frst_reg_brcd"), setOf(role))
        return AdminChangeRequest(
            lifecycle =
                PolicyChangeRequest(
                    requestId = getString("req_id"),
                    requester = requester,
                    risk = ChangeRisk.valueOf(getString("risk_dvcd")),
                    targetSnapshotHash = getString("tgt_snps_hash"),
                    baseBindingRevision = getLong("base_bind_rvsn"),
                    targetVersionId = getString(targetIdColumn),
                    expiresAt = getString("expr_dttm").instant(),
                ),
            targetType = targetType,
            scopeId = getString("scope_id"),
            beforeVersionId = beforeIdColumn?.let(::getString),
            evidenceId = getString("evdc_id"),
            diffPayload = getString("diff_payload"),
            diffHash = getString("diff_hash"),
            impactPayload = getString("impact_payload"),
            impactHash = getString("impact_hash"),
            reason = getString("req_rsn"),
            workTicket = getString("work_tckt"),
            idempotencyKey = getString("idmp_key"),
            requestedRole = role,
            requestedAt = getString("req_dttm").instant(),
        )
    }

    private fun ResultSet.toPolicyVersion(): AdminPolicyVersion {
        val actor = AdminActor(getString("frst_reg_empno"), getString("frst_reg_brcd"), emptySet())
        return AdminPolicyVersion(
            versionId = getString("plcy_vrsn_id"),
            scopeId = getString("plcy_scope_id"),
            versionNumber = getInt("vrsn_no"),
            schemaVersion = getString("plcy_schm_vrsn"),
            baseVersionId = getString("base_plcy_vrsn_id"),
            contractVersionId = getString("ctrt_vrsn_id"),
            payload = getString("plcy_payload"),
            policyHash = getString("plcy_hash"),
            ceilingSnapshot = getString("ceiling_snps"),
            ceilingHash = getString("ceiling_hash"),
            ceilingPassed = getString("ceiling_pass_yn") == "Y",
            registeredAt = getString("reg_dttm").instant(),
            registeredBy = actor,
        )
    }

    private fun ResultSet.toDecision(): AdminDecisionRecord {
        val role = AdminRole.valueOf(getString("aprv_role_dvcd"))
        return AdminDecisionRecord(
            PolicyDecision(
                requestId = getString("req_id"),
                actor = AdminActor(getString("aprv_empno"), getString("aprv_brcd"), setOf(role)),
                decision = ChangeDecision.valueOf(getString("dcsn_dvcd")),
                snapshotHash = getString("dcsn_snps_hash"),
                decidedAt = getString("dcsn_dttm").instant(),
            ),
            getString("dcsn_opin"),
        )
    }

    private fun ResultSet.toActivation() =
        AdminActivationRecord(
            actionId = getString("actn_id"),
            correlationId = getString("corr_id"),
            requestId = getString("req_id"),
            idempotencyKey = getString("idmp_key"),
            targetVersionId = getString("target_vrsn_id"),
            bindingRevision = getLong("bind_rvsn"),
            snapshotHash = getString("bind_snps_hash"),
            activatedAt = getString("occr_dttm").instant(),
        )

    private fun AdminActor.decisionRole(): AdminRole =
        when {
            AdminRole.BCM_SECURITY_APPROVER in roles -> AdminRole.BCM_SECURITY_APPROVER
            AdminRole.BCM_APPROVER in roles -> AdminRole.BCM_APPROVER
            else -> error("decision actor does not have an approver role")
        }

    private fun Instant.coreDateTime(): String = CoreDateTimes.format(LocalDateTime.ofInstant(this, ZoneOffset.UTC))

    private fun String.instant(): Instant = CoreDateTimes.parse(this).toInstant(ZoneOffset.UTC)

    private fun Boolean.toYn(): String = if (this) "Y" else "N"

    companion object {
        private val EMPTY_SNAPSHOT_HASH = "0".repeat(64)
        private val REQUEST_SELECT =
            """
            SELECT req_id, tgt_dvcd, scope_id, bfr_ctrt_vrsn_id, aft_ctrt_vrsn_id,
                   bfr_plcy_vrsn_id, aft_plcy_vrsn_id, aft_bnds_prop_id, aft_alwnc_rvok_id, aft_gate_rsm_id,
                   evdc_id, risk_dvcd, base_bind_rvsn,
                   tgt_snps_hash, diff_payload::text AS diff_payload, diff_hash,
                   impact_payload::text AS impact_payload, impact_hash, req_rsn, work_tckt,
                   idmp_key, req_role_dvcd, req_dttm, expr_dttm,
                   frst_reg_empno, frst_reg_brcd
              FROM bcm_chng_req_l
            """.trimIndent()
        private val POLICY_SELECT =
            """
            SELECT plcy_vrsn_id, plcy_scope_id, vrsn_no, plcy_schm_vrsn,
                   base_plcy_vrsn_id, ctrt_vrsn_id, plcy_payload::text AS plcy_payload,
                   plcy_hash, ceiling_snps::text AS ceiling_snps, ceiling_hash,
                   ceiling_pass_yn, reg_dttm, frst_reg_empno, frst_reg_brcd
              FROM bcm_plcy_vrsn_l
            """.trimIndent()
    }
}
