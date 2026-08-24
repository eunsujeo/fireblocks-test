package com.whatto.bcm.infra.persistence.admin

import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.EmergencyContractObservation
import com.whatto.bcm.domain.admin.EmergencyExternalControlCandidate
import com.whatto.bcm.domain.admin.EmergencyExternalControlEvaluation
import com.whatto.bcm.domain.admin.EmergencyExternalControlEvidence
import com.whatto.bcm.domain.admin.EmergencyExternalControlRepository
import com.whatto.bcm.domain.admin.EmergencyExternalControlStatus
import com.whatto.bcm.domain.admin.TapBatchObservation
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Repository
class EmergencyExternalControlJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : EmergencyExternalControlRepository {
    override fun findByIdempotency(
        employeeNo: String,
        idempotencyKey: String,
    ): EmergencyExternalControlEvidence? =
        jdbc
            .query(
                "$SELECT WHERE frst_reg_empno = :employeeNo AND idmp_key = :idempotencyKey",
                mapOf("employeeNo" to employeeNo, "idempotencyKey" to idempotencyKey),
                ROW_MAPPER,
            ).firstOrNull()

    override fun findLatestByNetwork(network: String): EmergencyExternalControlEvidence? =
        jdbc
            .query(
                "$SELECT WHERE ntwk_cd = :network ORDER BY obs_dttm DESC, ext_ctrl_evdc_id DESC LIMIT 1",
                mapOf("network" to network),
                ROW_MAPPER,
            ).firstOrNull()

    override fun insert(evidence: EmergencyExternalControlEvidence): EmergencyExternalControlEvidence {
        val issues = evidence.evaluation.issues.toIssueJson()
        val inserted =
            jdbc.update(
                """
                INSERT INTO bcm_ext_ctrl_evdc_l
                  (ext_ctrl_evdc_id, ntwk_cd, ctrt_vrsn_id, snps_hash,
                   tap_src_id, tap_blck_yn, tap_obs_dttm, pin_blck_no, exp_oprtr_hash,
                   rpc1_id, rpc1_blck_no, rpc1_pause_yn, rpc1_oprtr_hash, rpc1_obs_dttm,
                   rpc2_id, rpc2_blck_no, rpc2_pause_yn, rpc2_oprtr_hash, rpc2_obs_dttm,
                   evdc_stcd, issue_payload, issue_hash, obs_dttm, vld_until_dttm,
                   req_rsn, work_tckt, idmp_key,
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES
                  (:evidenceId, :network, :contractVersionId, :snapshotHash,
                   :tapSourceId, :tapBlocked, :tapObservedAt, :pinnedBlockNumber, :expectedOperatorSetHash,
                   :firstEndpointId, :firstBlockNumber, :firstPaused, :firstOperatorSetHash, :firstObservedAt,
                   :secondEndpointId, :secondBlockNumber, :secondPaused, :secondOperatorSetHash, :secondObservedAt,
                   :status, CAST(:issuePayload AS jsonb), :issueHash, :observedAt, :validUntil,
                   :reason, :workTicket, :idempotencyKey,
                   :employeeNo, :branchCode, :employeeNo, :branchCode)
                ON CONFLICT (frst_reg_empno, idmp_key) DO NOTHING
                """.trimIndent(),
                evidence.parameters(issues),
            )
        if (inserted != 1) {
            throw ConflictException("emergencyExternalControl", "${evidence.registeredBy.employeeNo}:${evidence.idempotencyKey}")
        }
        return evidence
    }

    private fun EmergencyExternalControlEvidence.parameters(issuePayload: String): Map<String, Any?> =
        mapOf(
            "evidenceId" to evidenceId,
            "network" to network,
            "contractVersionId" to contractVersionId,
            "snapshotHash" to snapshotHash,
            "tapSourceId" to candidate.tapSourceId,
            "tapBlocked" to candidate.tap?.blocked.toYn(),
            "tapObservedAt" to candidate.tap?.observedAt.coreDateTime(),
            "pinnedBlockNumber" to candidate.pinnedBlockNumber,
            "expectedOperatorSetHash" to candidate.expectedOperatorSetHash,
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
            "reason" to reason,
            "workTicket" to workTicket,
            "idempotencyKey" to idempotencyKey,
            "employeeNo" to registeredBy.employeeNo,
            "branchCode" to registeredBy.branchCode,
        )

    private companion object {
        const val SELECT =
            """
            SELECT ext_ctrl_evdc_id, ntwk_cd, ctrt_vrsn_id, snps_hash,
                   tap_src_id, tap_blck_yn, tap_obs_dttm, pin_blck_no, exp_oprtr_hash,
                   rpc1_id, rpc1_blck_no, rpc1_pause_yn, rpc1_oprtr_hash, rpc1_obs_dttm,
                   rpc2_id, rpc2_blck_no, rpc2_pause_yn, rpc2_oprtr_hash, rpc2_obs_dttm,
                   evdc_stcd, ARRAY(SELECT jsonb_array_elements_text(issue_payload)) AS issue_codes,
                   obs_dttm, vld_until_dttm, req_rsn, work_tckt, idmp_key,
                   frst_reg_empno, frst_reg_brcd
              FROM bcm_ext_ctrl_evdc_l
            """

        val ROW_MAPPER = RowMapper { rs, _ -> rs.toEvidence() }

        fun ResultSet.toEvidence(): EmergencyExternalControlEvidence {
            val status = EmergencyExternalControlStatus.valueOf(getString("evdc_stcd"))
            val issues = (getArray("issue_codes").array as Array<*>).map(Any?::toString)
            val candidate =
                EmergencyExternalControlCandidate(
                    tapSourceId = getString("tap_src_id"),
                    tap =
                        getString("tap_blck_yn")?.let { blocked ->
                            TapBatchObservation(blocked == "Y", getString("tap_obs_dttm").toInstant())
                        },
                    pinnedBlockNumber = getBigDecimal("pin_blck_no").toBigIntegerExact(),
                    expectedOperatorSetHash = getString("exp_oprtr_hash"),
                    firstEndpointId = getString("rpc1_id"),
                    first = rpcObservation("rpc1"),
                    secondEndpointId = getString("rpc2_id"),
                    second = rpcObservation("rpc2"),
                    sourceErrors = if (status == EmergencyExternalControlStatus.ERROR) issues else emptyList(),
                    observedAt = getString("obs_dttm").toInstant(),
                    validUntil = getString("vld_until_dttm").toInstant(),
                )
            return EmergencyExternalControlEvidence(
                evidenceId = getString("ext_ctrl_evdc_id"),
                network = getString("ntwk_cd"),
                contractVersionId = getString("ctrt_vrsn_id"),
                snapshotHash = getString("snps_hash"),
                candidate = candidate,
                evaluation = EmergencyExternalControlEvaluation(status, issues),
                reason = getString("req_rsn"),
                workTicket = getString("work_tckt"),
                idempotencyKey = getString("idmp_key"),
                registeredBy = AdminActor(getString("frst_reg_empno"), getString("frst_reg_brcd"), emptySet()),
            )
        }

        fun ResultSet.rpcObservation(prefix: String): EmergencyContractObservation? {
            val blockNumber = getBigDecimal("${prefix}_blck_no")?.toBigIntegerExact() ?: return null
            val paused = getString("${prefix}_pause_yn") ?: return null
            val operatorSetHash = getString("${prefix}_oprtr_hash") ?: return null
            val observedAt = getString("${prefix}_obs_dttm") ?: return null
            return EmergencyContractObservation(blockNumber, paused == "Y", operatorSetHash, observedAt.toInstant())
        }

        fun List<String>.toIssueJson(): String {
            forEach { require(it.matches(Regex("[A-Z0-9_]+"))) { "invalid external control issue code" } }
            return joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        }

        fun Boolean?.toYn(): String? = this?.let { if (it) "Y" else "N" }

        fun Instant?.coreDateTime(): String? = this?.let { CoreDateTimes.format(LocalDateTime.ofInstant(it, ZoneOffset.UTC)) }

        fun String.toInstant(): Instant = CoreDateTimes.parse(this).toInstant(ZoneOffset.UTC)

        fun sha256(value: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
