package com.whatto.bcm.infra.persistence.admin

import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminContractBinding
import com.whatto.bcm.domain.admin.AdminContractEvidence
import com.whatto.bcm.domain.admin.AdminContractRepository
import com.whatto.bcm.domain.admin.AdminContractVersion
import com.whatto.bcm.domain.admin.ContractEvidenceCandidate
import com.whatto.bcm.domain.admin.ContractEvidenceEvaluation
import com.whatto.bcm.domain.admin.ContractEvidenceStatus
import com.whatto.bcm.domain.admin.ContractExpectedState
import com.whatto.bcm.domain.admin.ExternalControlEvidence
import com.whatto.bcm.domain.admin.RpcObservation
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Repository
class AdminContractJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : AdminContractRepository {
    override fun insertVersion(version: AdminContractVersion): AdminContractVersion {
        jdbc.update(
            """
            INSERT INTO bcm_ctrt_vrsn_l
              (ctrt_vrsn_id, ctrt_scope_id, ntwk_cd, use_dvcd, vrsn, ctrt_addr,
               release_cmit, artifact_hash, abi_hash, runtime_code_hash, deploy_tx_hash,
               deploy_blck_no, immut_payload, immut_hash, ceiling_payload, ceiling_hash,
               release_uri, reg_dttm, frst_reg_empno, frst_reg_brcd,
               last_chng_empno, last_chng_brcd)
            VALUES
              (:versionId, :scopeId, :network, :use, :version, :address,
               :releaseCommit, :artifactHash, :abiHash, :runtimeCodeHash, :deploymentTransactionHash,
               :deploymentBlockNumber, CAST(:immutableValues AS jsonb), :immutableHash,
               CAST(:ceilingSnapshot AS jsonb), :ceilingHash, :releaseUri, :registeredAt,
               :employeeNo, :branchCode, :employeeNo, :branchCode)
            """.trimIndent(),
            mapOf(
                "versionId" to version.versionId,
                "scopeId" to version.scopeId,
                "network" to version.network,
                "use" to version.use,
                "version" to version.version,
                "address" to version.address,
                "releaseCommit" to version.releaseCommit,
                "artifactHash" to version.artifactHash,
                "abiHash" to version.abiHash,
                "runtimeCodeHash" to version.runtimeCodeHash,
                "deploymentTransactionHash" to version.deploymentTransactionHash,
                "deploymentBlockNumber" to version.deploymentBlockNumber,
                "immutableValues" to version.immutableValues,
                "immutableHash" to version.immutableHash,
                "ceilingSnapshot" to version.ceilingSnapshot,
                "ceilingHash" to version.ceilingHash,
                "releaseUri" to version.releaseUri,
                "registeredAt" to version.registeredAt.coreDateTime(),
                "employeeNo" to version.registeredBy.employeeNo,
                "branchCode" to version.registeredBy.branchCode,
            ),
        )
        return version
    }

    override fun findVersion(versionId: String): AdminContractVersion? =
        jdbc
            .query(
                "$VERSION_SELECT WHERE ctrt_vrsn_id = :versionId",
                mapOf("versionId" to versionId),
            ) { rs, _ -> rs.toVersion() }
            .firstOrNull()

    override fun initializeBinding(
        version: AdminContractVersion,
        actor: AdminActor,
        now: Instant,
    ): AdminContractBinding {
        jdbc.update(
            """
            INSERT INTO bcm_ctrt_bind_m
              (ctrt_scope_id, ntwk_cd, use_dvcd, bind_rvsn, bind_snps_hash,
               reg_dttm, last_chng_dttm, frst_reg_empno, frst_reg_brcd,
               last_chng_empno, last_chng_brcd)
            VALUES
              (:scopeId, :network, :use, 0, :snapshotHash,
               :now, :now, :employeeNo, :branchCode, :employeeNo, :branchCode)
            ON CONFLICT (ctrt_scope_id) DO NOTHING
            """.trimIndent(),
            mapOf(
                "scopeId" to version.scopeId,
                "network" to version.network,
                "use" to version.use,
                "snapshotHash" to EMPTY_SNAPSHOT_HASH,
                "now" to now.coreDateTime(),
                "employeeNo" to actor.employeeNo,
                "branchCode" to actor.branchCode,
            ),
        )
        return requireNotNull(findBinding(version.scopeId))
    }

    override fun insertEvidence(evidence: AdminContractEvidence): AdminContractEvidence {
        val candidate = evidence.candidate
        val first = candidate.first
        val second = candidate.second
        jdbc.update(
            """
            INSERT INTO bcm_ctrt_evdc_l
              (evdc_id, ctrt_vrsn_id, snps_hash, exp_chain_id, exp_code_hash,
               exp_immut_hash, pin_blck_no, rpc1_id, rpc1_chain_id, rpc1_code_hash,
               rpc1_immut_hash, rpc1_obs_dttm, rpc2_id, rpc2_chain_id, rpc2_code_hash,
               rpc2_immut_hash, rpc2_obs_dttm, tap_mtch_yn, clbk_mtch_yn,
               gasless_pass_yn, audit_pass_yn, revoke_drill_yn, launch_gate_yn,
               evdc_stcd, obs_dttm, vld_until_dttm, doc_evdc, doc_evdc_hash,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (:evidenceId, :contractVersionId, :snapshotHash, :expectedChainId, :expectedCodeHash,
               :expectedImmutableHash, :pinnedBlockNumber, :firstEndpointId, :firstChainId, :firstCodeHash,
               :firstImmutableHash, :firstObservedAt, :secondEndpointId, :secondChainId, :secondCodeHash,
               :secondImmutableHash, :secondObservedAt, :tapMatches, :callbackMatches,
               :gaslessPassed, :auditPassed, :revokeDrillPassed, :launchGatePassed,
               :status, :observedAt, :validUntil, CAST(:documentEvidence AS jsonb), :documentEvidenceHash,
               :employeeNo, :branchCode, :employeeNo, :branchCode)
            """.trimIndent(),
            mapOf(
                "evidenceId" to evidence.evidenceId,
                "contractVersionId" to evidence.contractVersionId,
                "snapshotHash" to evidence.snapshotHash,
                "expectedChainId" to candidate.expected.chainId,
                "expectedCodeHash" to candidate.expected.codeHash,
                "expectedImmutableHash" to candidate.expected.immutableHash,
                "pinnedBlockNumber" to candidate.expected.pinnedBlockNumber,
                "firstEndpointId" to evidence.firstEndpointId,
                "firstChainId" to first?.chainId,
                "firstCodeHash" to first?.codeHash,
                "firstImmutableHash" to first?.immutableHash,
                "firstObservedAt" to first?.observedAt?.coreDateTime(),
                "secondEndpointId" to evidence.secondEndpointId,
                "secondChainId" to second?.chainId,
                "secondCodeHash" to second?.codeHash,
                "secondImmutableHash" to second?.immutableHash,
                "secondObservedAt" to second?.observedAt?.coreDateTime(),
                "tapMatches" to candidate.controls.tapMatches.toYn(),
                "callbackMatches" to candidate.controls.callbackMatches.toYn(),
                "gaslessPassed" to candidate.controls.gaslessPassed.toYn(),
                "auditPassed" to candidate.controls.auditPassed.toYn(),
                "revokeDrillPassed" to candidate.controls.revokeDrillPassed.toYn(),
                "launchGatePassed" to candidate.controls.launchGatePassed.toYn(),
                "status" to evidence.evaluation.status.name,
                "observedAt" to candidate.observedAt.coreDateTime(),
                "validUntil" to candidate.validUntil.coreDateTime(),
                "documentEvidence" to evidence.documentEvidence,
                "documentEvidenceHash" to evidence.documentEvidenceHash,
                "employeeNo" to evidence.registeredBy.employeeNo,
                "branchCode" to evidence.registeredBy.branchCode,
            ),
        )
        return evidence
    }

    override fun findEvidence(evidenceId: String): AdminContractEvidence? =
        jdbc
            .query(
                "$EVIDENCE_SELECT WHERE evdc_id = :evidenceId",
                mapOf("evidenceId" to evidenceId),
            ) { rs, _ -> rs.toEvidence() }
            .firstOrNull()

    override fun findLatestEvidence(versionId: String): AdminContractEvidence? =
        jdbc
            .query(
                "$EVIDENCE_SELECT WHERE ctrt_vrsn_id = :versionId ORDER BY obs_dttm DESC, evdc_id DESC LIMIT 1",
                mapOf("versionId" to versionId),
            ) { rs, _ -> rs.toEvidence() }
            .firstOrNull()

    override fun lockBinding(scopeId: String): AdminContractBinding? = findBinding(scopeId, forUpdate = true)

    override fun activateBinding(
        request: com.whatto.bcm.domain.admin.AdminChangeRequest,
        actor: AdminActor,
        now: Instant,
    ): AdminContractBinding {
        val changed =
            jdbc.update(
                """
                UPDATE bcm_ctrt_bind_m
                   SET actv_ctrt_vrsn_id = :versionId,
                       bind_rvsn = bind_rvsn + 1,
                       last_req_id = :requestId,
                       last_evdc_id = :evidenceId,
                       bind_snps_hash = :snapshotHash,
                       last_chng_dttm = :now,
                       last_chng_empno = :employeeNo,
                       last_chng_brcd = :branchCode
                 WHERE ctrt_scope_id = :scopeId
                   AND bind_rvsn = :baseRevision
                """.trimIndent(),
                mapOf(
                    "versionId" to request.lifecycle.targetVersionId,
                    "requestId" to request.lifecycle.requestId,
                    "evidenceId" to request.evidenceId,
                    "snapshotHash" to request.lifecycle.targetSnapshotHash,
                    "now" to now.coreDateTime(),
                    "employeeNo" to actor.employeeNo,
                    "branchCode" to actor.branchCode,
                    "scopeId" to request.scopeId,
                    "baseRevision" to request.lifecycle.baseBindingRevision,
                ),
            )
        check(changed == 1) { "contract binding changed concurrently for ${request.scopeId}" }
        return requireNotNull(findBinding(request.scopeId, forUpdate = false))
    }

    private fun findBinding(
        scopeId: String,
        forUpdate: Boolean = false,
    ): AdminContractBinding? =
        jdbc
            .query(
                """
                SELECT ctrt_scope_id, ntwk_cd, use_dvcd, actv_ctrt_vrsn_id,
                       bind_rvsn, last_evdc_id, bind_snps_hash
                 FROM bcm_ctrt_bind_m
                 WHERE ctrt_scope_id = :scopeId
                ${if (forUpdate) " FOR UPDATE" else ""}
                """.trimIndent(),
                mapOf("scopeId" to scopeId),
            ) { rs, _ ->
                AdminContractBinding(
                    scopeId = rs.getString("ctrt_scope_id"),
                    network = rs.getString("ntwk_cd"),
                    use = rs.getString("use_dvcd"),
                    activeVersionId = rs.getString("actv_ctrt_vrsn_id"),
                    revision = rs.getLong("bind_rvsn"),
                    evidenceId = rs.getString("last_evdc_id"),
                    snapshotHash = rs.getString("bind_snps_hash"),
                )
            }.firstOrNull()

    private fun ResultSet.toVersion(): AdminContractVersion =
        AdminContractVersion(
            versionId = getString("ctrt_vrsn_id"),
            scopeId = getString("ctrt_scope_id"),
            network = getString("ntwk_cd"),
            use = getString("use_dvcd"),
            version = getString("vrsn"),
            address = getString("ctrt_addr"),
            releaseCommit = getString("release_cmit"),
            artifactHash = getString("artifact_hash"),
            abiHash = getString("abi_hash"),
            runtimeCodeHash = getString("runtime_code_hash"),
            deploymentTransactionHash = getString("deploy_tx_hash"),
            deploymentBlockNumber = getBigDecimal("deploy_blck_no").toBigIntegerExact(),
            immutableValues = getString("immut_payload"),
            immutableHash = getString("immut_hash"),
            ceilingSnapshot = getString("ceiling_payload"),
            ceilingHash = getString("ceiling_hash"),
            releaseUri = getString("release_uri"),
            registeredAt = getString("reg_dttm").instant(),
            registeredBy = AdminActor(getString("frst_reg_empno"), getString("frst_reg_brcd"), emptySet()),
        )

    private fun ResultSet.toEvidence(): AdminContractEvidence {
        val expected =
            ContractExpectedState(
                chainId = getLong("exp_chain_id"),
                codeHash = getString("exp_code_hash"),
                immutableHash = getString("exp_immut_hash"),
                pinnedBlockNumber = getBigDecimal("pin_blck_no").toBigIntegerExact(),
            )
        val firstEndpoint = getString("rpc1_id")
        val secondEndpoint = getString("rpc2_id")
        val candidate =
            ContractEvidenceCandidate(
                expected = expected,
                first = observation("rpc1", firstEndpoint),
                second = observation("rpc2", secondEndpoint),
                controls =
                    ExternalControlEvidence(
                        getString("tap_mtch_yn") == "Y",
                        getString("clbk_mtch_yn") == "Y",
                        getString("gasless_pass_yn") == "Y",
                        getString("audit_pass_yn") == "Y",
                        getString("revoke_drill_yn") == "Y",
                        getString("launch_gate_yn") == "Y",
                    ),
                observedAt = getString("obs_dttm").instant(),
                validUntil = getString("vld_until_dttm").instant(),
            )
        val status = ContractEvidenceStatus.valueOf(getString("evdc_stcd"))
        return AdminContractEvidence(
            evidenceId = getString("evdc_id"),
            contractVersionId = getString("ctrt_vrsn_id"),
            snapshotHash = getString("snps_hash"),
            candidate = candidate,
            evaluation = ContractEvidenceEvaluation(status, emptyList()),
            documentEvidence = getString("doc_evdc"),
            documentEvidenceHash = getString("doc_evdc_hash"),
            firstEndpointId = firstEndpoint,
            secondEndpointId = secondEndpoint,
            registeredBy = AdminActor(getString("frst_reg_empno"), getString("frst_reg_brcd"), emptySet()),
        )
    }

    private fun ResultSet.observation(
        prefix: String,
        endpointId: String,
    ): RpcObservation? {
        val observedAt = getString("${prefix}_obs_dttm") ?: return null
        return RpcObservation(
            endpointId = endpointId,
            chainId = getLong("${prefix}_chain_id"),
            codeHash = getString("${prefix}_code_hash"),
            immutableHash = getString("${prefix}_immut_hash"),
            blockNumber = getBigDecimal("pin_blck_no").toBigIntegerExact(),
            observedAt = observedAt.instant(),
        )
    }

    private fun Instant.coreDateTime(): String = CoreDateTimes.format(LocalDateTime.ofInstant(this, ZoneOffset.UTC))

    private fun String.instant(): Instant = CoreDateTimes.parse(this).toInstant(ZoneOffset.UTC)

    private fun Boolean.toYn(): String = if (this) "Y" else "N"

    companion object {
        private val EMPTY_SNAPSHOT_HASH = "0".repeat(64)
        private val VERSION_SELECT =
            """
            SELECT ctrt_vrsn_id, ctrt_scope_id, ntwk_cd, use_dvcd, vrsn, ctrt_addr,
                   release_cmit, artifact_hash, abi_hash, runtime_code_hash, deploy_tx_hash,
                   deploy_blck_no, immut_payload::text AS immut_payload, immut_hash,
                   ceiling_payload::text AS ceiling_payload, ceiling_hash, release_uri,
                   reg_dttm, frst_reg_empno, frst_reg_brcd
              FROM bcm_ctrt_vrsn_l
            """.trimIndent()
        private val EVIDENCE_SELECT =
            """
            SELECT evdc_id, ctrt_vrsn_id, snps_hash, exp_chain_id, exp_code_hash,
                   exp_immut_hash, pin_blck_no, rpc1_id, rpc1_chain_id, rpc1_code_hash,
                   rpc1_immut_hash, rpc1_obs_dttm, rpc2_id, rpc2_chain_id, rpc2_code_hash,
                   rpc2_immut_hash, rpc2_obs_dttm, tap_mtch_yn, clbk_mtch_yn,
                   gasless_pass_yn, audit_pass_yn, revoke_drill_yn, launch_gate_yn,
                   evdc_stcd, obs_dttm, vld_until_dttm, doc_evdc::text AS doc_evdc,
                   doc_evdc_hash, frst_reg_empno, frst_reg_brcd
              FROM bcm_ctrt_evdc_l
            """.trimIndent()
    }
}
