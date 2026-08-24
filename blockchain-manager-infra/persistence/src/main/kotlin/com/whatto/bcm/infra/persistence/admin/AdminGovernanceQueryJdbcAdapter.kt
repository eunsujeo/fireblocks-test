package com.whatto.bcm.infra.persistence.admin

import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminAllowanceRevocationItemSummary
import com.whatto.bcm.domain.admin.AdminAllowanceRevocationSummary
import com.whatto.bcm.domain.admin.AdminBandSItemSummary
import com.whatto.bcm.domain.admin.AdminBandSSummary
import com.whatto.bcm.domain.admin.AdminChangeRequestDetail
import com.whatto.bcm.domain.admin.AdminContractSummary
import com.whatto.bcm.domain.admin.AdminExecutionGateResumeSummary
import com.whatto.bcm.domain.admin.AdminExecutionGateScope
import com.whatto.bcm.domain.admin.AdminExternalControlEvidenceSummary
import com.whatto.bcm.domain.admin.AdminGovernanceQueryRepository
import com.whatto.bcm.domain.admin.AdminPolicyRepository
import com.whatto.bcm.domain.admin.AdminPolicySummary
import com.whatto.bcm.domain.admin.AdminRole
import com.whatto.bcm.domain.admin.AdminWebhookRuntimeObservation
import com.whatto.bcm.domain.admin.AllowanceRevocationEventStatus
import com.whatto.bcm.domain.admin.AllowanceRevocationExecutionStatus
import com.whatto.bcm.domain.admin.AllowanceRevocationLifecycle
import com.whatto.bcm.domain.admin.BandSDirection
import com.whatto.bcm.domain.admin.BandSExecutionEventStatus
import com.whatto.bcm.domain.admin.BandSExecutionStatus
import com.whatto.bcm.domain.admin.BandSItemState
import com.whatto.bcm.domain.admin.BandSLegType
import com.whatto.bcm.domain.admin.ChangeDecision
import com.whatto.bcm.domain.admin.ChangeTargetType
import com.whatto.bcm.domain.admin.EmergencyExternalControlStatus
import com.whatto.bcm.domain.admin.ExecutionGateEvent
import com.whatto.bcm.domain.admin.ExecutionGateResumeCheckStatus
import com.whatto.bcm.domain.admin.ExecutionGateStatus
import com.whatto.bcm.domain.admin.ExecutionGateType
import com.whatto.bcm.domain.admin.SweepPolicyHardCeiling
import com.whatto.bcm.domain.admin.WebhookRecoveryRepository
import com.whatto.bcm.domain.admin.WebhookRecoveryView
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Repository
class AdminGovernanceQueryJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
    private val policies: AdminPolicyRepository,
    private val webhookRecoveries: WebhookRecoveryRepository,
) : AdminGovernanceQueryRepository {
    override fun findContracts(now: Instant): List<AdminContractSummary> =
        jdbc.query(
            """
            SELECT version.ctrt_vrsn_id, version.ctrt_scope_id, version.ntwk_cd,
                   version.use_dvcd, version.vrsn, version.ctrt_addr,
                   version.runtime_code_hash, evidence.evdc_stcd, evidence.vld_until_dttm,
                   binding.actv_ctrt_vrsn_id = version.ctrt_vrsn_id AS active,
                   CASE
                     WHEN binding.actv_ctrt_vrsn_id = version.ctrt_vrsn_id THEN 'ACTIVE'
                     WHEN evidence.evdc_stcd = 'VALID' AND evidence.vld_until_dttm > :now THEN 'VERIFIED'
                     ELSE 'CANDIDATE'
                   END AS derived_state
              FROM bcm_ctrt_vrsn_l version
              LEFT JOIN bcm_ctrt_bind_m binding ON binding.ctrt_scope_id = version.ctrt_scope_id
              LEFT JOIN LATERAL (
                SELECT evdc_stcd, vld_until_dttm
                  FROM bcm_ctrt_evdc_l evidence
                 WHERE evidence.ctrt_vrsn_id = version.ctrt_vrsn_id
                 ORDER BY obs_dttm DESC, evdc_id DESC
                 LIMIT 1
              ) evidence ON true
             ORDER BY version.ctrt_scope_id, version.reg_dttm DESC
            """.trimIndent(),
            mapOf("now" to now.coreDateTime()),
        ) { rs, _ ->
            AdminContractSummary(
                versionId = rs.getString("ctrt_vrsn_id"),
                scopeId = rs.getString("ctrt_scope_id"),
                network = rs.getString("ntwk_cd"),
                use = rs.getString("use_dvcd"),
                version = rs.getString("vrsn"),
                address = rs.getString("ctrt_addr"),
                state = rs.getString("derived_state"),
                runtimeCodeHash = rs.getString("runtime_code_hash"),
                evidenceStatus = rs.getString("evdc_stcd"),
                evidenceValidUntil = rs.getString("vld_until_dttm")?.instant(),
                active = rs.getBoolean("active"),
            )
        }

    override fun findPolicies(now: Instant): List<AdminPolicySummary> =
        jdbc.query(
            """
            SELECT version.plcy_vrsn_id, version.plcy_scope_id, version.vrsn_no,
                   version.plcy_schm_vrsn, version.plcy_hash, version.ceiling_pass_yn,
                   version.reg_dttm,
                   binding.actv_plcy_vrsn_id = version.plcy_vrsn_id AS active,
                   CASE
                     WHEN binding.actv_plcy_vrsn_id = version.plcy_vrsn_id THEN 'ACTIVE'
                     WHEN EXISTS (
                       SELECT 1 FROM bcm_adm_actn_l action
                       JOIN bcm_chng_req_l request ON request.req_id = action.req_id
                        WHERE request.aft_plcy_vrsn_id = version.plcy_vrsn_id
                          AND action.actn_dvcd = 'ACTIVATE' AND action.actn_stcd = 'SUCCEEDED'
                     ) THEN 'SUPERSEDED'
                     WHEN review.req_id IS NOT NULL AND review.rejected = 0
                          AND review.approvals >= review.required_approvals
                          AND (review.security_required = false OR review.security_approvals > 0) THEN 'APPROVED'
                     WHEN review.req_id IS NOT NULL THEN 'IN_REVIEW'
                     ELSE 'DRAFT'
                   END AS derived_state
              FROM bcm_plcy_vrsn_l version
              LEFT JOIN bcm_plcy_bind_m binding ON binding.plcy_scope_id = version.plcy_scope_id
              LEFT JOIN LATERAL (
                SELECT request.req_id,
                       CASE WHEN request.risk_dvcd IN ('SECURITY','RESUME') THEN 2 ELSE 1 END AS required_approvals,
                       request.risk_dvcd IN ('SECURITY','RESUME') AS security_required,
                       count(decision.aprv_empno) FILTER (WHERE decision.dcsn_dvcd = 'APPROVE') AS approvals,
                       count(decision.aprv_empno) FILTER (
                         WHERE decision.dcsn_dvcd = 'APPROVE'
                           AND decision.aprv_role_dvcd = 'BCM_SECURITY_APPROVER'
                       ) AS security_approvals,
                       count(decision.aprv_empno) FILTER (WHERE decision.dcsn_dvcd = 'REJECT') AS rejected
                  FROM bcm_chng_req_l request
                  LEFT JOIN bcm_chng_dcsn_l decision ON decision.req_id = request.req_id
                 WHERE request.aft_plcy_vrsn_id = version.plcy_vrsn_id
                   AND request.expr_dttm > :now
                   AND NOT EXISTS (
                     SELECT 1 FROM bcm_adm_actn_l action
                      WHERE action.req_id = request.req_id
                        AND action.actn_dvcd IN ('ACTIVATE','CANCEL')
                        AND action.actn_stcd = 'SUCCEEDED'
                   )
                 GROUP BY request.req_id
                 ORDER BY request.req_dttm DESC
                 LIMIT 1
              ) review ON true
             ORDER BY version.plcy_scope_id, version.vrsn_no DESC
            """.trimIndent(),
            mapOf("now" to now.coreDateTime()),
        ) { rs, _ ->
            AdminPolicySummary(
                versionId = rs.getString("plcy_vrsn_id"),
                scopeId = rs.getString("plcy_scope_id"),
                versionNumber = rs.getInt("vrsn_no"),
                schemaVersion = rs.getString("plcy_schm_vrsn"),
                state = rs.getString("derived_state"),
                policyHash = rs.getString("plcy_hash"),
                ceilingPassed = rs.getString("ceiling_pass_yn") == "Y",
                active = rs.getBoolean("active"),
                registeredAt = rs.getString("reg_dttm").instant(),
            )
        }

    override fun findChangeRequest(
        requestId: String,
        now: Instant,
    ): AdminChangeRequestDetail? {
        val request = policies.findChangeRequest(requestId) ?: return null
        val decisions = policies.findDecisions(requestId)
        val bandS = request.targetType == ChangeTargetType.BAND_S
        val allowanceRevoke = request.targetType == ChangeTargetType.ALLOWANCE_REVOKE
        val executionGateResume = request.targetType == ChangeTargetType.EXECUTION_GATE
        val executionTarget = bandS || allowanceRevoke || executionGateResume
        val succeeded =
            when {
                bandS -> bandExecutionExists(requestId)
                allowanceRevoke -> allowanceRevocationCompleted(request.lifecycle.targetVersionId)
                executionGateResume -> actionSucceeded(requestId, "RESUME")
                else -> actionSucceeded(requestId, "ACTIVATE")
            }
        val cancelled = actionSucceeded(requestId, "CANCEL")
        val rejected = decisions.any { it.decision.decision == ChangeDecision.REJECT }
        val approvals = decisions.filter { it.decision.decision == ChangeDecision.APPROVE }.distinctBy { it.decision.actor.employeeNo }
        val securityApprovals = approvals.count { AdminRole.BCM_SECURITY_APPROVER in it.decision.actor.roles }
        val risk = request.lifecycle.risk
        val expired = !now.isBefore(request.lifecycle.expiresAt)
        val quorum = approvals.size >= risk.requiredApprovals && (!risk.securityApproverRequired || securityApprovals > 0)
        val targetReady = targetReady(requestId, now)
        val state =
            when {
                succeeded -> if (executionTarget) "EXECUTED" else "ACTIVATED"
                cancelled -> "CANCELLED"
                rejected -> "REJECTED"
                expired -> "EXPIRED"
                quorum -> "APPROVED"
                else -> "PENDING"
            }
        val disabledReasons =
            buildList {
                if (rejected) add("REQUEST_REJECTED")
                if (expired) add("REQUEST_EXPIRED")
                if (!quorum) add("APPROVAL_QUORUM_NOT_SATISFIED")
                if (risk.securityApproverRequired && securityApprovals == 0) add("SECURITY_APPROVER_REQUIRED")
                if (!targetReady && !succeeded) add("TARGET_NOT_READY")
                if (succeeded) add(if (executionTarget) "ALREADY_EXECUTED" else "ALREADY_ACTIVATED")
                if (cancelled) add("REQUEST_CANCELLED")
            }.distinct()
        return AdminChangeRequestDetail(
            request = request,
            state = state,
            decisions = decisions,
            requiredApprovals = risk.requiredApprovals,
            approvalCount = approvals.size,
            securityApprovalRequired = risk.securityApproverRequired,
            securityApprovalCount = securityApprovals,
            activationReady = disabledReasons.isEmpty(),
            disabledReasons = disabledReasons,
        )
    }

    override fun findBandS(now: Instant): List<AdminBandSSummary> =
        jdbc
            .query(
                """
                SELECT proposal.prop_id, proposal.src_prop_id, proposal.snps_id,
                       proposal.plcy_vrsn_id, proposal.drct_dvcd, proposal.prop_hash,
                       proposal.input_hash AS proposal_input_hash, proposal.total_krw_amt,
                       proposal.aft_hot_ratio, proposal.exec_able_yn AS proposal_executable,
                       ARRAY(SELECT jsonb_array_elements_text(proposal.block_payload)) AS block_reasons,
                       snapshot.src_req_id, snapshot.snps_hash,
                       snapshot.input_hash AS snapshot_input_hash, snapshot.base_dttm,
                       snapshot.expr_dttm, snapshot.input_cmplt_yn,
                       snapshot.total_ast_krw_amt, snapshot.obs_hot_krw_amt,
                       snapshot.obs_cold_krw_amt, snapshot.efct_hot_krw_amt,
                       snapshot.hot_ratio, snapshot.low_ratio, snapshot.trgt_ratio, snapshot.up_ratio,
                       ARRAY(SELECT jsonb_array_elements_text(snapshot.issue_payload)) AS issue_codes,
                       EXISTS (
                         SELECT 1 FROM bcm_plcy_bind_m binding
                          WHERE binding.actv_plcy_vrsn_id = proposal.plcy_vrsn_id
                       ) AS policy_active,
                       request.req_id, request.expr_dttm AS request_expires_at,
                       request.risk_dvcd, request.approvals, request.rejections,
                       execution.exec_id, execution.rsv_dttm
                  FROM bcm_bnds_prop_l proposal
                  JOIN bcm_bnds_snps_l snapshot ON snapshot.snps_id = proposal.snps_id
                  LEFT JOIN LATERAL (
                    SELECT change.req_id, change.expr_dttm, change.risk_dvcd,
                           count(decision.aprv_empno) FILTER (WHERE decision.dcsn_dvcd = 'APPROVE') AS approvals,
                           count(decision.aprv_empno) FILTER (WHERE decision.dcsn_dvcd = 'REJECT') AS rejections
                      FROM bcm_chng_req_l change
                      LEFT JOIN bcm_chng_dcsn_l decision ON decision.req_id = change.req_id
                     WHERE change.tgt_dvcd = 'BAND_S' AND change.aft_bnds_prop_id = proposal.prop_id
                     GROUP BY change.req_id
                     ORDER BY change.req_dttm DESC
                     LIMIT 1
                  ) request ON true
                  LEFT JOIN bcm_bnds_exec_l execution ON execution.prop_id = proposal.prop_id
                 ORDER BY proposal.reg_dttm DESC, proposal.prop_id
                 LIMIT 100
                """.trimIndent(),
                emptyMap<String, Any>(),
            ) { rs, _ -> rs.toBandSRow() }
            .map { row -> row.toSummary(findBandSItems(row.proposalId, row.executionId), now) }

    override fun findExecutionGateScopes(
        now: Instant,
        hardCeiling: SweepPolicyHardCeiling,
        limit: Int,
    ): List<AdminExecutionGateScope> =
        jdbc.query(
            """
            WITH gate_types(gate_dvcd, sort_no) AS (
              VALUES ('WITHDRAWAL', 1), ('SWEEP', 2), ('APPROVE', 3)
            )
            SELECT network.ntwk_cd, gate_type.gate_dvcd,
                   event.gate_evt_id, event.evt_seq, event.gate_stcd, event.req_rsn,
                   event.work_tckt, event.idmp_key, event.occr_dttm,
                   event.frst_reg_empno, event.frst_reg_brcd, event.rsm_req_id,
                   EXISTS (
                     SELECT 1
                       FROM bcm_plcy_bind_m policy_binding
                       JOIN bcm_plcy_vrsn_l policy
                         ON policy.plcy_vrsn_id = policy_binding.actv_plcy_vrsn_id
                       JOIN bcm_ctrt_vrsn_l contract ON contract.ctrt_vrsn_id = policy.ctrt_vrsn_id
                       JOIN bcm_ctrt_bind_m contract_binding
                         ON contract_binding.ctrt_scope_id = contract.ctrt_scope_id
                        AND contract_binding.actv_ctrt_vrsn_id = contract.ctrt_vrsn_id
                       JOIN LATERAL (
                         SELECT evidence.evdc_stcd, evidence.launch_gate_yn, evidence.vld_until_dttm
                           FROM bcm_ctrt_evdc_l evidence
                          WHERE evidence.ctrt_vrsn_id = contract.ctrt_vrsn_id
                          ORDER BY evidence.obs_dttm DESC, evidence.evdc_id DESC
                          LIMIT 1
                       ) evidence ON true
                      WHERE policy_binding.plcy_scope_id LIKE ('POLICY:' || network.ntwk_cd || ':%')
                        AND policy.ceiling_pass_yn = 'Y'
                        AND policy.plcy_payload->>'enabled' = 'true'
                        AND :executionEnabled
                        AND (policy.plcy_payload->>'minimumAmount')::numeric >= 0
                        AND (policy.plcy_payload->>'batchSize')::integer BETWEEN 1 AND :maximumBatchSize
                        AND (policy.plcy_payload->>'allowanceCap')::numeric BETWEEN 0 AND :maximumAllowance
                        AND (policy.plcy_payload->>'itemAmountCap')::numeric BETWEEN 0 AND :maximumItemAmount
                        AND (policy.plcy_payload->>'batchAmountCap')::numeric BETWEEN 0 AND :maximumBatchAmount
                        AND (policy.plcy_payload->>'boostAttempts')::integer BETWEEN 0 AND :maximumBoostAttempts
                        AND (policy.plcy_payload->>'minimumAmount')::numeric <=
                            (policy.plcy_payload->>'itemAmountCap')::numeric
                        AND (policy.plcy_payload->>'itemAmountCap')::numeric <=
                            (policy.plcy_payload->>'batchAmountCap')::numeric
                        AND contract.ntwk_cd = network.ntwk_cd
                        AND contract.use_dvcd = 'SWEEP'
                        AND evidence.evdc_stcd = 'VALID'
                        AND evidence.launch_gate_yn = 'Y'
                        AND evidence.vld_until_dttm > :observedAt
                   ) AS release_context_ready
              FROM bcm_blkc_m network
              CROSS JOIN gate_types gate_type
              LEFT JOIN LATERAL (
                SELECT gate_evt_id, evt_seq, gate_stcd, req_rsn, work_tckt, idmp_key,
                       occr_dttm, frst_reg_empno, frst_reg_brcd, rsm_req_id
                  FROM bcm_exec_gate_evt_l event
                 WHERE event.ntwk_cd = network.ntwk_cd
                   AND event.gate_dvcd = gate_type.gate_dvcd
                 ORDER BY event.evt_seq DESC
                 LIMIT 1
              ) event ON true
             WHERE network.ntwk_cd IS NOT NULL
             ORDER BY network.ntwk_cd, gate_type.sort_no
             LIMIT :limit
            """.trimIndent(),
            mapOf(
                "observedAt" to now.coreDateTime(),
                "executionEnabled" to hardCeiling.executionEnabled,
                "maximumBatchSize" to hardCeiling.maximumBatchSize,
                "maximumAllowance" to hardCeiling.maximumAllowance,
                "maximumItemAmount" to hardCeiling.maximumItemAmount,
                "maximumBatchAmount" to hardCeiling.maximumBatchAmount,
                "maximumBoostAttempts" to hardCeiling.maximumBoostAttempts,
                "limit" to limit,
            ),
        ) { rs, _ ->
            val network = rs.getString("ntwk_cd")
            val type = ExecutionGateType.valueOf(rs.getString("gate_dvcd"))
            AdminExecutionGateScope(
                network = network,
                type = type,
                releaseContextReady = rs.getBoolean("release_context_ready"),
                current =
                    rs.getString("gate_evt_id")?.let { eventId ->
                        ExecutionGateEvent(
                            eventId = eventId,
                            network = network,
                            type = type,
                            sequence = rs.getInt("evt_seq"),
                            status = ExecutionGateStatus.valueOf(rs.getString("gate_stcd")),
                            reason = rs.getString("req_rsn"),
                            workTicket = rs.getString("work_tckt"),
                            idempotencyKey = rs.getString("idmp_key"),
                            occurredAt = rs.getString("occr_dttm").instant(),
                            actor = AdminActor(rs.getString("frst_reg_empno"), rs.getString("frst_reg_brcd"), emptySet()),
                            resumeRequestId = rs.getString("rsm_req_id"),
                        )
                    },
            )
        }

    override fun findLatestExternalControls(limit: Int): List<AdminExternalControlEvidenceSummary> =
        jdbc.query(
            """
            SELECT DISTINCT ON (ntwk_cd)
                   ext_ctrl_evdc_id, ntwk_cd, ctrt_vrsn_id, snps_hash,
                   tap_src_id, tap_blck_yn, pin_blck_no, exp_oprtr_hash,
                   rpc1_id, rpc1_pause_yn, rpc1_oprtr_hash,
                   rpc2_id, rpc2_pause_yn, rpc2_oprtr_hash,
                   evdc_stcd, ARRAY(SELECT jsonb_array_elements_text(issue_payload)) AS issue_codes,
                   obs_dttm, vld_until_dttm, req_rsn, work_tckt, frst_reg_empno
              FROM bcm_ext_ctrl_evdc_l
             ORDER BY ntwk_cd, obs_dttm DESC, ext_ctrl_evdc_id DESC
             LIMIT :limit
            """.trimIndent(),
            mapOf("limit" to limit),
        ) { rs, _ ->
            val status = EmergencyExternalControlStatus.valueOf(rs.getString("evdc_stcd"))
            AdminExternalControlEvidenceSummary(
                evidenceId = rs.getString("ext_ctrl_evdc_id"),
                network = rs.getString("ntwk_cd"),
                contractVersionId = rs.getString("ctrt_vrsn_id"),
                status = status,
                completionReady = status == EmergencyExternalControlStatus.CONFIRMED,
                snapshotHash = rs.getString("snps_hash"),
                tapSourceId = rs.getString("tap_src_id"),
                tapBlocked = rs.getString("tap_blck_yn")?.let { it == "Y" },
                pinnedBlockNumber = rs.getBigDecimal("pin_blck_no").toBigIntegerExact(),
                expectedOperatorSetHash = rs.getString("exp_oprtr_hash"),
                firstEndpointId = rs.getString("rpc1_id"),
                firstPaused = rs.getString("rpc1_pause_yn")?.let { it == "Y" },
                firstOperatorSetHash = rs.getString("rpc1_oprtr_hash"),
                secondEndpointId = rs.getString("rpc2_id"),
                secondPaused = rs.getString("rpc2_pause_yn")?.let { it == "Y" },
                secondOperatorSetHash = rs.getString("rpc2_oprtr_hash"),
                observedAt = rs.getString("obs_dttm").instant(),
                validUntil = rs.getString("vld_until_dttm").instant(),
                reason = rs.getString("req_rsn"),
                workTicket = rs.getString("work_tckt"),
                actorEmployeeNo = rs.getString("frst_reg_empno"),
                issues = rs.getArray("issue_codes").strings(),
            )
        }

    override fun findAllowanceRevocations(limit: Int): List<AdminAllowanceRevocationSummary> =
        jdbc
            .query(
                """
                SELECT execution.rvok_exec_id, request.req_id, execution.ntwk_cd,
                       execution.ctrt_vrsn_id, execution.ctrt_bind_rvsn, execution.swp_ctrt_addr,
                       execution.tgt_snps_hash, execution.item_cnt, execution.reg_dttm
                  FROM bcm_alwnc_rvok_exec_l execution
                  JOIN bcm_chng_req_l request ON request.aft_alwnc_rvok_id = execution.rvok_exec_id
                 ORDER BY execution.reg_dttm DESC, execution.rvok_exec_id DESC
                 LIMIT :limit
                """.trimIndent(),
                mapOf("limit" to limit),
            ) { rs, _ -> rs.toAllowanceRevocationRow() }
            .map { row -> row.toSummary(findAllowanceRevocationItems(row.executionId)) }

    override fun findWebhookRecoveries(limit: Int): List<WebhookRecoveryView> = webhookRecoveries.findRecent(limit)

    override fun findExecutionGateResumes(
        now: Instant,
        limit: Int,
    ): List<AdminExecutionGateResumeSummary> =
        jdbc.query(
            """
            SELECT resume.rsm_id, request.req_id, resume.ntwk_cd, resume.gate_dvcd,
                   resume.stop_gate_evt_id, resume.ctrt_vrsn_id, resume.ctrt_evdc_id,
                   resume.rvok_exec_id, resume.cause_evdc_uri, resume.cause_evdc_hash,
                   request.req_dttm, request.expr_dttm, request.frst_reg_empno,
                   decisions.approval_count, decisions.security_count, decisions.reject_count,
                   latest_check.chk_stcd, latest_check.obs_dttm AS check_obs_dttm,
                   latest_check.vld_until_dttm AS check_vld_until_dttm,
                   latest_check.issue_codes,
                   EXISTS (SELECT 1 FROM bcm_exec_gate_evt_l event
                            WHERE event.rsm_req_id = request.req_id AND event.gate_stcd = 'RESUMED') AS resumed,
                   EXISTS (SELECT 1 FROM bcm_exec_gate_evt_l event
                            WHERE event.gate_evt_id = resume.stop_gate_evt_id AND event.gate_stcd = 'STOPPED' AND
                              NOT EXISTS (SELECT 1 FROM bcm_exec_gate_evt_l newer
                                           WHERE newer.ntwk_cd = event.ntwk_cd AND newer.gate_dvcd = event.gate_dvcd AND
                                             newer.evt_seq > event.evt_seq)) AS stopped_current,
                   EXISTS (SELECT 1 FROM bcm_ctrt_bind_m binding
                            WHERE binding.ctrt_scope_id = resume.ntwk_cd || ':SWEEP' AND
                              binding.actv_ctrt_vrsn_id = resume.ctrt_vrsn_id AND
                              binding.bind_rvsn = resume.ctrt_bind_rvsn) AS contract_current,
                   EXISTS (SELECT 1 FROM bcm_ctrt_evdc_l evidence
                            WHERE evidence.evdc_id = resume.ctrt_evdc_id AND
                              evidence.ctrt_vrsn_id = resume.ctrt_vrsn_id AND evidence.evdc_stcd = 'VALID' AND
                              evidence.vld_until_dttm > :now AND
                              NOT EXISTS (SELECT 1 FROM bcm_ctrt_evdc_l newer
                                           WHERE newer.ctrt_vrsn_id = evidence.ctrt_vrsn_id AND
                                             (newer.obs_dttm, newer.evdc_id) >
                                               (evidence.obs_dttm, evidence.evdc_id))) AS evidence_current,
                   bcm_allowance_revocation_completed(resume.rvok_exec_id) AS revocation_completed
              FROM bcm_exec_gate_rsm_l resume
              JOIN bcm_chng_req_l request ON request.aft_gate_rsm_id = resume.rsm_id
              LEFT JOIN LATERAL (
                SELECT count(*) FILTER (WHERE decision.dcsn_dvcd = 'APPROVE') AS approval_count,
                       count(*) FILTER (WHERE decision.dcsn_dvcd = 'APPROVE' AND
                         decision.aprv_role_dvcd = 'BCM_SECURITY_APPROVER') AS security_count,
                       count(*) FILTER (WHERE decision.dcsn_dvcd = 'REJECT') AS reject_count
                  FROM bcm_chng_dcsn_l decision WHERE decision.req_id = request.req_id
              ) decisions ON true
              LEFT JOIN LATERAL (
                SELECT check_row.chk_stcd, check_row.obs_dttm, check_row.vld_until_dttm,
                       ARRAY(SELECT jsonb_array_elements_text(check_row.issue_payload)) AS issue_codes
                  FROM bcm_exec_gate_rsm_chk_l check_row
                 WHERE check_row.rsm_id = resume.rsm_id
                 ORDER BY check_row.chk_seq DESC LIMIT 1
              ) latest_check ON true
             ORDER BY request.req_dttm DESC, request.req_id DESC
             LIMIT :limit
            """.trimIndent(),
            mapOf("now" to now.coreDateTime(), "limit" to limit),
        ) { rs, _ -> rs.toExecutionGateResumeSummary(now) }

    override fun findWebhookRuntimeObservation(): AdminWebhookRuntimeObservation =
        checkNotNull(
            jdbc.queryForObject(
                """
                SELECT GREATEST(
                         (SELECT MAX(rcv_dttm) FROM bcm_whk_l WHERE prcs_stcd = 'P'),
                         (SELECT MAX(rcv_dttm) FROM bcm_whk_l WHERE prcs_stcd = 'S'),
                         (SELECT MAX(rcv_dttm) FROM bcm_whk_l WHERE prcs_stcd = 'F')
                       ) AS last_received_at,
                       (SELECT count(*) FROM bcm_whk_l WHERE prcs_stcd = 'P') AS pending_inbox_count,
                       (SELECT count(*) FROM bcm_whk_l WHERE prcs_stcd = 'F') AS poisoned_inbox_count,
                       (SELECT count(*) FROM bcm_outbox_l WHERE evnt_stcd = 'P') AS pending_outbox_count,
                       (SELECT count(*) FROM bcm_outbox_l WHERE evnt_stcd = 'F') AS poisoned_outbox_count
                """.trimIndent(),
                emptyMap<String, Any>(),
            ) { rs, _ ->
                AdminWebhookRuntimeObservation(
                    lastReceivedAt = rs.getString("last_received_at")?.instant(),
                    pendingInboxCount = rs.getLong("pending_inbox_count"),
                    poisonedInboxCount = rs.getLong("poisoned_inbox_count"),
                    pendingOutboxCount = rs.getLong("pending_outbox_count"),
                    poisonedOutboxCount = rs.getLong("poisoned_outbox_count"),
                )
            },
        )

    private fun actionSucceeded(
        requestId: String,
        action: String,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1 FROM bcm_adm_actn_l
               WHERE req_id = :requestId AND actn_dvcd = :action AND actn_stcd = 'SUCCEEDED'
            )
            """.trimIndent(),
            mapOf("requestId" to requestId, "action" to action),
            Boolean::class.java,
        ) == true

    private fun bandExecutionExists(requestId: String): Boolean =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM bcm_bnds_exec_l WHERE req_id = :requestId)",
            mapOf("requestId" to requestId),
            Boolean::class.java,
        ) == true

    private fun allowanceRevocationCompleted(executionId: String): Boolean =
        jdbc.queryForObject(
            """
            SELECT execution.item_cnt > 0 AND execution.item_cnt = count(*) FILTER (
                     WHERE latest.rvok_stcd = 'ZERO_CONFIRMED'
                   )
              FROM bcm_alwnc_rvok_exec_l execution
              LEFT JOIN bcm_alwnc_rvok_item_l item ON item.rvok_exec_id = execution.rvok_exec_id
              LEFT JOIN LATERAL (
                SELECT event.rvok_stcd
                  FROM bcm_alwnc_rvok_evt_l event
                 WHERE event.rvok_exec_id = item.rvok_exec_id AND event.item_seq = item.item_seq
                 ORDER BY event.evt_seq DESC LIMIT 1
              ) latest ON true
             WHERE execution.rvok_exec_id = :executionId
             GROUP BY execution.item_cnt
            """.trimIndent(),
            mapOf("executionId" to executionId),
            Boolean::class.java,
        ) == true

    private fun targetReady(
        requestId: String,
        now: Instant,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT CASE request.tgt_dvcd
                     WHEN 'POLICY' THEN policy.ceiling_pass_yn = 'Y' AND (
                       policy.ctrt_vrsn_id IS NULL OR EXISTS (
                         SELECT 1 FROM bcm_ctrt_vrsn_l version
                         JOIN bcm_ctrt_bind_m binding
                           ON binding.ctrt_scope_id = version.ctrt_scope_id
                          AND binding.actv_ctrt_vrsn_id = version.ctrt_vrsn_id
                          WHERE version.ctrt_vrsn_id = policy.ctrt_vrsn_id
                       )
                     )
                     WHEN 'CONTRACT' THEN evidence.evdc_stcd = 'VALID'
                       AND evidence.vld_until_dttm > :now
                       AND evidence.ctrt_vrsn_id = request.aft_ctrt_vrsn_id
                     WHEN 'BAND_S' THEN band_snapshot.input_cmplt_yn = 'Y'
                       AND band_snapshot.expr_dttm > :now
                       AND band_proposal.exec_able_yn = 'Y'
                       AND band_proposal.input_hash = band_snapshot.input_hash
                       AND NOT EXISTS (
                         SELECT 1 FROM jsonb_array_elements_text(band_snapshot.issue_payload)
                       )
                       AND NOT EXISTS (
                         SELECT 1 FROM jsonb_array_elements_text(band_proposal.block_payload)
                       )
                       AND NOT EXISTS (
                         SELECT 1 FROM bcm_bnds_prop_item_l item
                          WHERE item.prop_id = band_proposal.prop_id
                            AND (item.exec_able_yn <> 'Y' OR item.block_rsn_cd IS NOT NULL)
                       )
                       AND EXISTS (
                         SELECT 1 FROM bcm_plcy_bind_m binding
                          WHERE binding.actv_plcy_vrsn_id = band_proposal.plcy_vrsn_id
                       )
                     WHEN 'ALLOWANCE_REVOKE' THEN allowance_execution.tgt_snps_hash = request.tgt_snps_hash
                       AND allowance_execution.ctrt_bind_rvsn = request.base_bind_rvsn
                       AND allowance_execution.item_cnt > 0
                       AND allowance_execution.item_cnt = (
                         SELECT count(*) FROM bcm_alwnc_rvok_item_l item
                          WHERE item.rvok_exec_id = allowance_execution.rvok_exec_id
                       )
                       AND EXISTS (
                         SELECT 1 FROM bcm_ctrt_bind_m binding
                          WHERE binding.ctrt_scope_id = request.scope_id
                            AND binding.actv_ctrt_vrsn_id = allowance_execution.ctrt_vrsn_id
                            AND binding.bind_rvsn = allowance_execution.ctrt_bind_rvsn
                       )
                     WHEN 'EXECUTION_GATE' THEN EXISTS (
                       SELECT 1 FROM bcm_exec_gate_rsm_l resume
                        WHERE resume.rsm_id = request.aft_gate_rsm_id
                          AND resume.tgt_snps_hash = request.tgt_snps_hash
                          AND bcm_allowance_revocation_completed(resume.rvok_exec_id)
                          AND EXISTS (
                            SELECT 1 FROM bcm_exec_gate_evt_l event
                             WHERE event.gate_evt_id = resume.stop_gate_evt_id AND event.gate_stcd = 'STOPPED'
                               AND NOT EXISTS (
                                 SELECT 1 FROM bcm_exec_gate_evt_l newer
                                  WHERE newer.ntwk_cd = event.ntwk_cd AND newer.gate_dvcd = event.gate_dvcd
                                    AND newer.evt_seq > event.evt_seq
                               )
                          )
                          AND EXISTS (
                            SELECT 1 FROM bcm_ctrt_bind_m binding
                             WHERE binding.ctrt_scope_id = resume.ntwk_cd || ':SWEEP'
                               AND binding.actv_ctrt_vrsn_id = resume.ctrt_vrsn_id
                               AND binding.bind_rvsn = resume.ctrt_bind_rvsn
                          )
                          AND EXISTS (
                            SELECT 1 FROM bcm_ctrt_evdc_l contract_evidence
                             WHERE contract_evidence.evdc_id = resume.ctrt_evdc_id
                               AND contract_evidence.evdc_stcd = 'VALID'
                               AND contract_evidence.vld_until_dttm > :now
                               AND NOT EXISTS (
                                 SELECT 1 FROM bcm_ctrt_evdc_l newer_evidence
                                  WHERE newer_evidence.ctrt_vrsn_id = contract_evidence.ctrt_vrsn_id
                                    AND (newer_evidence.obs_dttm, newer_evidence.evdc_id) >
                                      (contract_evidence.obs_dttm, contract_evidence.evdc_id)
                               )
                          )
                          AND EXISTS (
                            SELECT 1 FROM bcm_exec_gate_rsm_chk_l resume_check
                             WHERE resume_check.rsm_id = resume.rsm_id AND resume_check.chk_stcd = 'READY'
                               AND resume_check.vld_until_dttm > :now
                               AND NOT EXISTS (
                                 SELECT 1 FROM bcm_exec_gate_rsm_chk_l newer_check
                                  WHERE newer_check.rsm_id = resume_check.rsm_id
                                    AND newer_check.chk_seq > resume_check.chk_seq
                               )
                          )
                     )
                     ELSE false
                   END
              FROM bcm_chng_req_l request
              LEFT JOIN bcm_plcy_vrsn_l policy ON policy.plcy_vrsn_id = request.aft_plcy_vrsn_id
              LEFT JOIN bcm_ctrt_evdc_l evidence ON evidence.evdc_id = request.evdc_id
              LEFT JOIN bcm_bnds_prop_l band_proposal ON band_proposal.prop_id = request.aft_bnds_prop_id
              LEFT JOIN bcm_bnds_snps_l band_snapshot ON band_snapshot.snps_id = band_proposal.snps_id
              LEFT JOIN bcm_alwnc_rvok_exec_l allowance_execution
                ON allowance_execution.rvok_exec_id = request.aft_alwnc_rvok_id
             WHERE request.req_id = :requestId
            """.trimIndent(),
            mapOf("requestId" to requestId, "now" to now.coreDateTime()),
            Boolean::class.java,
        ) == true

    private fun Instant.coreDateTime(): String = CoreDateTimes.format(LocalDateTime.ofInstant(this, ZoneOffset.UTC))

    private fun ResultSet.toExecutionGateResumeSummary(now: Instant): AdminExecutionGateResumeSummary {
        val approvals = getInt("approval_count")
        val securityApprovals = getInt("security_count")
        val rejected = getInt("reject_count") > 0
        val expired = !now.isBefore(getString("expr_dttm").instant())
        val checkStatus = getString("chk_stcd")?.let(ExecutionGateResumeCheckStatus::valueOf)
        val checkValidUntil = getString("check_vld_until_dttm")?.instant()
        val stoppedCurrent = getBoolean("stopped_current")
        val contractCurrent = getBoolean("contract_current")
        val evidenceCurrent = getBoolean("evidence_current")
        val revocationCompleted = getBoolean("revocation_completed")
        val quorum = approvals >= 2 && securityApprovals >= 1 && !rejected && !expired
        val checkReady = checkStatus == ExecutionGateResumeCheckStatus.READY && checkValidUntil?.let(now::isBefore) == true
        val prerequisitesReady = stoppedCurrent && contractCurrent && evidenceCurrent && revocationCompleted
        val resumed = getBoolean("resumed")
        val disabled =
            buildList {
                if (rejected) add("REQUEST_REJECTED")
                if (expired) add("REQUEST_EXPIRED")
                if (approvals < 2) add("APPROVAL_QUORUM_NOT_SATISFIED")
                if (securityApprovals < 1) add("SECURITY_APPROVER_REQUIRED")
                if (!stoppedCurrent && !resumed) add("STOPPED_SNAPSHOT_CHANGED")
                if (!contractCurrent) add("CONTRACT_BINDING_CHANGED")
                if (!evidenceCurrent) add("CONTRACT_EVIDENCE_STALE")
                if (!revocationCompleted) add("ALLOWANCE_REVOCATION_INCOMPLETE")
                if (checkStatus == null) add("RESUME_CHECK_REQUIRED")
                if (checkStatus != null && checkStatus != ExecutionGateResumeCheckStatus.READY) add("RESUME_CHECK_${checkStatus.name}")
                if (checkStatus == ExecutionGateResumeCheckStatus.READY && !checkReady) add("RESUME_CHECK_STALE")
            }.distinct()
        val state =
            when {
                resumed -> "RESUMED"
                rejected || expired || !prerequisitesReady -> "BLOCKED"
                !quorum -> "PENDING"
                checkStatus == null -> "APPROVED"
                !checkReady -> "BLOCKED"
                else -> "READY"
            }
        val retryable = !resumed && !rejected && !expired
        val retryCondition =
            when {
                resumed -> "COMPLETED"
                rejected || expired -> "NEW_REQUEST_REQUIRED"
                disabled.isNotEmpty() -> disabled.first()
                else -> "READY"
            }
        return AdminExecutionGateResumeSummary(
            resumeId = getString("rsm_id"),
            requestId = getString("req_id"),
            network = getString("ntwk_cd"),
            type = ExecutionGateType.valueOf(getString("gate_dvcd")),
            state = state,
            stoppedEventId = getString("stop_gate_evt_id"),
            contractVersionId = getString("ctrt_vrsn_id"),
            contractEvidenceId = getString("ctrt_evdc_id"),
            revocationExecutionId = getString("rvok_exec_id"),
            causeEvidenceUri = getString("cause_evdc_uri"),
            causeEvidenceHash = getString("cause_evdc_hash"),
            requestedAt = getString("req_dttm").instant(),
            expiresAt = getString("expr_dttm").instant(),
            requestedByEmployeeNo = getString("frst_reg_empno"),
            approvalCount = approvals,
            requiredApprovals = 2,
            securityApprovalCount = securityApprovals,
            latestCheckStatus = checkStatus,
            latestCheckObservedAt = getString("check_obs_dttm")?.instant(),
            latestCheckValidUntil = checkValidUntil,
            issues = getArray("issue_codes")?.strings().orEmpty(),
            resumeReady = resumed || (quorum && prerequisitesReady && checkReady),
            disabledReasons = if (resumed) emptyList() else disabled,
            retryable = retryable,
            retryCondition = retryCondition,
        )
    }

    private fun String.instant(): Instant = CoreDateTimes.parse(this).toInstant(ZoneOffset.UTC)

    private fun findBandSItems(
        proposalId: String,
        executionId: String?,
    ): List<AdminBandSItemSummary> =
        jdbc.query(
            """
            SELECT item.item_seq, item.dep_item_seq, item.leg_dvcd, item.ntwk_cd, item.tkn_smbl,
                   item.src_vlt_id, item.dst_vlt_id, item.dst_addr, item.amt, item.krw_amt,
                   item.exp_fee_amt, item.item_hash, item.exec_able_yn, item.block_rsn_cd,
                   latest.exec_stcd
              FROM bcm_bnds_prop_item_l item
              LEFT JOIN LATERAL (
                SELECT event.exec_stcd
                  FROM bcm_bnds_exec_evt_l event
                 WHERE event.exec_id = :executionId AND event.item_seq = item.item_seq
                 ORDER BY event.evt_seq DESC
                 LIMIT 1
              ) latest ON true
             WHERE item.prop_id = :proposalId
             ORDER BY item.item_seq
            """.trimIndent(),
            mapOf("proposalId" to proposalId, "executionId" to executionId),
        ) { rs, _ ->
            AdminBandSItemSummary(
                sequence = rs.getInt("item_seq"),
                dependsOnSequence = rs.getInt("dep_item_seq").takeUnless { rs.wasNull() },
                legType = BandSLegType.valueOf(rs.getString("leg_dvcd")),
                network = rs.getString("ntwk_cd"),
                tokenSymbol = rs.getString("tkn_smbl"),
                sourceVaultId = rs.getString("src_vlt_id"),
                destinationVaultId = rs.getString("dst_vlt_id"),
                destinationAddress = rs.getString("dst_addr"),
                amount = rs.getBigDecimal("amt"),
                krwAmount = rs.getBigDecimal("krw_amt"),
                expectedFeeAmount = rs.getBigDecimal("exp_fee_amt"),
                itemHash = rs.getString("item_hash"),
                executable = rs.getString("exec_able_yn") == "Y",
                blockReason = rs.getString("block_rsn_cd"),
                executionStatus = rs.getString("exec_stcd")?.let(BandSExecutionEventStatus::valueOf),
            )
        }

    private fun findAllowanceRevocationItems(executionId: String): List<AdminAllowanceRevocationItemSummary> =
        jdbc.query(
            """
            SELECT item.item_seq, item.acnt_id, item.ntwk_cd, item.tkn_smbl,
                   item.src_vlt_id, item.ownr_addr, item.tkn_ctrt_addr,
                   item.bfr_obs_alwnc, item.ext_tx_id,
                   latest.rvok_stcd, latest.vndr_tx_id, auth.obs_alwnc,
                   auth.last_chck_dttm, latest.err_cd, latest.occr_dttm
              FROM bcm_alwnc_rvok_item_l item
              JOIN bcm_swp_auth_m auth
                ON auth.acnt_id = item.acnt_id
               AND auth.ntwk_cd = item.ntwk_cd
               AND auth.tkn_smbl = item.tkn_smbl
               AND auth.swp_ctrt_addr = item.swp_ctrt_addr
              LEFT JOIN LATERAL (
                SELECT event.rvok_stcd, event.vndr_tx_id, event.obs_alwnc,
                       event.err_cd, event.occr_dttm
                  FROM bcm_alwnc_rvok_evt_l event
                 WHERE event.rvok_exec_id = item.rvok_exec_id AND event.item_seq = item.item_seq
                 ORDER BY event.evt_seq DESC
                 LIMIT 1
              ) latest ON true
             WHERE item.rvok_exec_id = :executionId
             ORDER BY item.item_seq
            """.trimIndent(),
            mapOf("executionId" to executionId),
        ) { rs, _ ->
            AdminAllowanceRevocationItemSummary(
                sequence = rs.getInt("item_seq"),
                accountId = rs.getString("acnt_id"),
                network = rs.getString("ntwk_cd"),
                symbol = rs.getString("tkn_smbl"),
                sourceVaultId = rs.getString("src_vlt_id"),
                ownerAddress = rs.getString("ownr_addr"),
                tokenContractAddress = rs.getString("tkn_ctrt_addr"),
                beforeObservedAllowance = rs.getBigDecimal("bfr_obs_alwnc"),
                externalTransactionId = rs.getString("ext_tx_id"),
                latestStatus = rs.getString("rvok_stcd")?.let(AllowanceRevocationEventStatus::valueOf),
                vendorTransactionId = rs.getString("vndr_tx_id"),
                observedAllowance = rs.getBigDecimal("obs_alwnc"),
                observedAt = rs.getString("last_chck_dttm").instant(),
                errorCode = rs.getString("err_cd"),
                occurredAt = rs.getString("occr_dttm")?.instant(),
            )
        }

    private data class AllowanceRevocationRow(
        val executionId: String,
        val requestId: String,
        val network: String,
        val contractVersionId: String,
        val contractBindingRevision: Long,
        val sweepContractAddress: String,
        val targetSnapshotHash: String,
        val itemCount: Int,
        val registeredAt: Instant,
    ) {
        fun toSummary(items: List<AdminAllowanceRevocationItemSummary>): AdminAllowanceRevocationSummary {
            val statuses = items.map { it.latestStatus }
            val status = AllowanceRevocationLifecycle.executionStatusFromLatest(itemCount, statuses)
            val failedCount = statuses.count { it == AllowanceRevocationEventStatus.FAILED }
            return AdminAllowanceRevocationSummary(
                executionId = executionId,
                requestId = requestId,
                network = network,
                contractVersionId = contractVersionId,
                contractBindingRevision = contractBindingRevision,
                sweepContractAddress = sweepContractAddress,
                targetSnapshotHash = targetSnapshotHash,
                status = status,
                totalCount = itemCount,
                zeroConfirmedCount = statuses.count { it == AllowanceRevocationEventStatus.ZERO_CONFIRMED },
                submittingCount =
                    statuses.count {
                        it == AllowanceRevocationEventStatus.SUBMIT_INTENT || it == AllowanceRevocationEventStatus.SUBMITTED
                    },
                failedCount = failedCount,
                registeredAt = registeredAt,
                items = items,
                retryable = status == AllowanceRevocationExecutionStatus.READY || failedCount > 0,
                retryCondition =
                    when {
                        status == AllowanceRevocationExecutionStatus.COMPLETED -> "COMPLETED"
                        failedCount > 0 -> "FAILED_ITEMS_CAN_RETRY"
                        status == AllowanceRevocationExecutionStatus.READY -> "EXECUTION_READY"
                        statuses.any {
                            it == AllowanceRevocationEventStatus.SUBMIT_INTENT ||
                                it == AllowanceRevocationEventStatus.SUBMITTED
                        } -> "WAIT_FOR_SUBMISSION_RESULT"
                        else -> "WAIT_FOR_ONCHAIN_ZERO_CONFIRMATION"
                    },
            )
        }
    }

    private fun ResultSet.toAllowanceRevocationRow() =
        AllowanceRevocationRow(
            executionId = getString("rvok_exec_id"),
            requestId = getString("req_id"),
            network = getString("ntwk_cd"),
            contractVersionId = getString("ctrt_vrsn_id"),
            contractBindingRevision = getLong("ctrt_bind_rvsn"),
            sweepContractAddress = getString("swp_ctrt_addr"),
            targetSnapshotHash = getString("tgt_snps_hash"),
            itemCount = getInt("item_cnt"),
            registeredAt = getString("reg_dttm").instant(),
        )

    private data class BandSRow(
        val proposalId: String,
        val sourceProposalId: String,
        val snapshotId: String,
        val sourceRequestId: String,
        val policyVersionId: String,
        val snapshotHash: String,
        val snapshotInputHash: String,
        val proposalInputHash: String,
        val observedAt: Instant,
        val expiresAt: Instant,
        val inputComplete: Boolean,
        val issueCodes: List<String>,
        val totalAssetKrwAmount: BigDecimal,
        val observedHotKrwAmount: BigDecimal,
        val observedColdKrwAmount: BigDecimal,
        val effectiveHotKrwAmount: BigDecimal,
        val hotRatio: BigDecimal,
        val lowerRatio: BigDecimal,
        val targetRatio: BigDecimal,
        val upperRatio: BigDecimal,
        val direction: BandSDirection,
        val proposalHash: String,
        val totalKrwAmount: BigDecimal,
        val afterHotRatio: BigDecimal,
        val proposalExecutable: Boolean,
        val blockReasons: List<String>,
        val policyActive: Boolean,
        val requestId: String?,
        val requestExpiresAt: Instant?,
        val risk: String?,
        val approvals: Int,
        val rejections: Int,
        val executionId: String?,
        val reservedAt: Instant?,
    ) {
        fun toSummary(
            items: List<AdminBandSItemSummary>,
            now: Instant,
        ): AdminBandSSummary {
            val requiredApprovals =
                if (risk in setOf("SECURITY", "RESUME")) {
                    2
                } else if (requestId == null) {
                    0
                } else {
                    1
                }
            val requestExpired = requestExpiresAt?.let { !now.isBefore(it) } == true
            val requestState =
                when {
                    requestId == null -> null
                    executionId != null -> "EXECUTED"
                    rejections > 0 -> "REJECTED"
                    requestExpired -> "EXPIRED"
                    approvals >= requiredApprovals -> "APPROVED"
                    else -> "PENDING"
                }
            val itemStates =
                items.mapNotNull { item ->
                    item.executionStatus?.let { BandSItemState(item.sequence, it) }
                }
            val executionStatus =
                executionId?.let { BandSExecutionStatus.derive(itemStates, items.size) }
            val blockingReasons =
                buildList {
                    if (!inputComplete || issueCodes.isNotEmpty()) add("INPUT_INCOMPLETE")
                    if (!now.isBefore(expiresAt)) add("SNAPSHOT_EXPIRED")
                    if (!policyActive) add("POLICY_NOT_ACTIVE")
                    if (snapshotInputHash != proposalInputHash) add("INPUT_HASH_MISMATCH")
                    if (!proposalExecutable || blockReasons.isNotEmpty() || items.any { !it.executable || it.blockReason != null }) {
                        add("PROPOSAL_BLOCKED")
                    }
                    if (requestId == null) add("REQUEST_NOT_CREATED")
                    if (rejections > 0) add("REQUEST_REJECTED")
                    if (requestExpired) add("REQUEST_EXPIRED")
                    if (requestId != null && approvals < requiredApprovals) add("APPROVAL_QUORUM_NOT_SATISFIED")
                    if (executionId != null) add("ALREADY_RESERVED")
                }.distinct()
            val state =
                executionStatus?.name
                    ?: when {
                        "INPUT_INCOMPLETE" in blockingReasons || "PROPOSAL_BLOCKED" in blockingReasons -> "BLOCKED"
                        blockingReasons.any { it in setOf("SNAPSHOT_EXPIRED", "POLICY_NOT_ACTIVE", "INPUT_HASH_MISMATCH") } -> "STALE"
                        requestState != null -> requestState
                        else -> "PROPOSED"
                    }
            return AdminBandSSummary(
                proposalId,
                sourceProposalId,
                snapshotId,
                sourceRequestId,
                policyVersionId,
                snapshotHash,
                snapshotInputHash,
                observedAt,
                expiresAt,
                inputComplete,
                issueCodes,
                totalAssetKrwAmount,
                observedHotKrwAmount,
                observedColdKrwAmount,
                effectiveHotKrwAmount,
                hotRatio,
                lowerRatio,
                targetRatio,
                upperRatio,
                direction,
                proposalHash,
                totalKrwAmount,
                afterHotRatio,
                state,
                requestId,
                requestState,
                approvals,
                requiredApprovals,
                executionId,
                executionStatus,
                reservedAt,
                blockingReasons.isEmpty(),
                blockingReasons + "READ_ONLY_AUTH_BOUNDARY",
                items,
            )
        }
    }

    private fun ResultSet.toBandSRow() =
        BandSRow(
            proposalId = getString("prop_id"),
            sourceProposalId = getString("src_prop_id"),
            snapshotId = getString("snps_id"),
            sourceRequestId = getString("src_req_id"),
            policyVersionId = getString("plcy_vrsn_id"),
            snapshotHash = getString("snps_hash"),
            snapshotInputHash = getString("snapshot_input_hash"),
            proposalInputHash = getString("proposal_input_hash"),
            observedAt = getString("base_dttm").instant(),
            expiresAt = getString("expr_dttm").instant(),
            inputComplete = getString("input_cmplt_yn") == "Y",
            issueCodes = getArray("issue_codes").strings(),
            totalAssetKrwAmount = getBigDecimal("total_ast_krw_amt"),
            observedHotKrwAmount = getBigDecimal("obs_hot_krw_amt"),
            observedColdKrwAmount = getBigDecimal("obs_cold_krw_amt"),
            effectiveHotKrwAmount = getBigDecimal("efct_hot_krw_amt"),
            hotRatio = getBigDecimal("hot_ratio"),
            lowerRatio = getBigDecimal("low_ratio"),
            targetRatio = getBigDecimal("trgt_ratio"),
            upperRatio = getBigDecimal("up_ratio"),
            direction = BandSDirection.valueOf(getString("drct_dvcd")),
            proposalHash = getString("prop_hash"),
            totalKrwAmount = getBigDecimal("total_krw_amt"),
            afterHotRatio = getBigDecimal("aft_hot_ratio"),
            proposalExecutable = getString("proposal_executable") == "Y",
            blockReasons = getArray("block_reasons").strings(),
            policyActive = getBoolean("policy_active"),
            requestId = getString("req_id"),
            requestExpiresAt = getString("request_expires_at")?.instant(),
            risk = getString("risk_dvcd"),
            approvals = getInt("approvals"),
            rejections = getInt("rejections"),
            executionId = getString("exec_id"),
            reservedAt = getString("rsv_dttm")?.instant(),
        )

    private fun java.sql.Array.strings(): List<String> =
        (array as Array<*>)
            .map(Any?::toString)
}
