package com.whatto.bcm.infra.persistence.sweep

import com.whatto.bcm.domain.admin.AdminGovernanceQueryRepository
import com.whatto.bcm.domain.admin.SweepPolicyHardCeiling
import com.whatto.bcm.domain.sweep.SweepExecutionGatePort
import com.whatto.bcm.domain.sweep.SweepRuntimeAttestationRepository
import com.whatto.bcm.domain.sweep.SweepRuntimePolicyRepository
import com.whatto.bcm.domain.sweep.attestationEntry
import com.whatto.bcm.infra.persistence.admin.AdminGovernanceQueryJdbcAdapter
import com.whatto.bcm.infra.persistence.admin.AdminPolicyJdbcAdapter
import com.whatto.bcm.infra.persistence.admin.WebhookRecoveryJdbcAdapter
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.time.Instant

@DataJdbcTest
@Import(
    SweepRuntimePolicyJdbcAdapter::class,
    SweepExecutionGateJdbcAdapter::class,
    AdminGovernanceQueryJdbcAdapter::class,
    AdminPolicyJdbcAdapter::class,
    WebhookRecoveryJdbcAdapter::class,
)
class SweepRuntimePolicyPersistenceTest : PersistenceTestSupport() {
    @Autowired lateinit var policies: SweepRuntimePolicyRepository

    @Autowired lateinit var attestations: SweepRuntimeAttestationRepository

    @Autowired lateinit var executionGates: SweepExecutionGatePort

    @Autowired lateinit var governance: AdminGovernanceQueryRepository

    @Autowired lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun setUp() {
        jdbc.update(
            """
            INSERT INTO bcm_blkc_m
              (vndr_blkc_id, ntwk_cd, chain_id, dspl_nm, test_yn, deprc_yn, sync_dttm, chain_mdl_dvcd,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('sweep-runtime-base', 'BASE', 8453, 'Base', 'N', 'N', '20260819000000', 'EVM',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (vndr_blkc_id) DO NOTHING
            """.trimIndent(),
        )
        insertContract()
        insertEvidence("sweep-runtime-evidence-valid", "VALID", "Y", "20991231235959", "20260819010000")
        insertPolicy()
    }

    @Test
    fun `활성 정책과 컨트랙트의 최신 VALID 증적을 실행 문맥으로 조립한다`() {
        val found = policies.findActive(NETWORK, SYMBOL, OBSERVED_AT)

        assertThat(found?.policyVersionId).isEqualTo("sweep-runtime-policy-v1")
        assertThat(found?.policySnapshotHash).isEqualTo(hash("policy-binding"))
        assertThat(found?.policy?.minimumAmount).isEqualByComparingTo("10")
        assertThat(found?.policy?.batchSize).isEqualTo(25)
        assertThat(found?.policy?.allowanceCap).isEqualByComparingTo("100")
        assertThat(found?.contractVersionId).isEqualTo("sweep-runtime-contract-v1")
        assertThat(found?.contractEvidenceId).isEqualTo("sweep-runtime-evidence-valid")
        assertThat(found?.contractAddress).isEqualTo(SWEEP_CONTRACT)
        assertThat(attestations.findAllActive(OBSERVED_AT))
            .filteredOn { it.network == NETWORK && it.symbol == SYMBOL }
            .containsExactly(found)
        assertThat(governance.findSweepRuntimeAttestationEntries(OBSERVED_AT))
            .filteredOn { it.scopeKey == "$NETWORK|$SYMBOL" }
            .containsExactly(requireNotNull(found).attestationEntry())
        assertThat(governance.findSweepRuntimeAttestationEntries(OBSERVED_AT))
            .filteredOn { it.scopeKey == "gate:SWEEP|$NETWORK" }
            .containsExactly(executionGates.findCurrent(NETWORK).attestationEntry())
    }

    @Test
    fun `최신 증적이 무효이면 과거 VALID 증적으로 실행을 열지 않는다`() {
        insertEvidence("sweep-runtime-evidence-invalid", "INVALID", "N", "20991231235959", "20260819020000")

        assertThat(policies.findActive(NETWORK, SYMBOL, OBSERVED_AT)).isNull()
    }

    @Test
    fun `Admin 실행 게이트 조회도 같은 활성 release 문맥을 준비 완료로 표시한다`() {
        val scopes = governance.findExecutionGateScopes(OBSERVED_AT, HARD_CEILING, 10).filter { it.network == NETWORK }

        assertThat(scopes).hasSize(3).allSatisfy { assertThat(it.releaseContextReady).isTrue() }
    }

    private fun insertContract() {
        jdbc.update(
            """
            INSERT INTO bcm_ctrt_vrsn_l
              (ctrt_vrsn_id, ctrt_scope_id, ntwk_cd, use_dvcd, vrsn, ctrt_addr, release_cmit,
               artifact_hash, abi_hash, runtime_code_hash, deploy_tx_hash, deploy_blck_no,
               immut_payload, immut_hash, ceiling_payload, ceiling_hash, release_uri, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('sweep-runtime-contract-v1', 'BASE:SWEEP', 'BASE', 'SWEEP', '1.0.0', ?, 'commit',
                    ?, ?, ?, '0xdeploy', 1, '{}'::jsonb, ?, '{}'::jsonb, ?, 'doc://release', '20260819000000',
                    '830001', '0001', '830001', '0001')
            """.trimIndent(),
            SWEEP_CONTRACT,
            hash("artifact"),
            hash("abi"),
            hash("runtime"),
            hash("immutable"),
            hash("contract-ceiling"),
        )
        jdbc.update(
            """
            INSERT INTO bcm_ctrt_bind_m
              (ctrt_scope_id, ntwk_cd, use_dvcd, actv_ctrt_vrsn_id, bind_rvsn,
               bind_snps_hash, reg_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('BASE:SWEEP', 'BASE', 'SWEEP', 'sweep-runtime-contract-v1', 1, ?, '20260819000000', '20260819000000',
                    '830001', '0001', '830001', '0001')
            """.trimIndent(),
            hash("contract-binding"),
        )
    }

    private fun insertEvidence(
        id: String,
        status: String,
        launchGate: String,
        validUntil: String,
        observedAt: String,
    ) {
        jdbc.update(
            """
            INSERT INTO bcm_ctrt_evdc_l
              (evdc_id, ctrt_vrsn_id, snps_hash, exp_chain_id, exp_code_hash, exp_immut_hash, pin_blck_no,
               rpc1_id, rpc1_chain_id, rpc1_code_hash, rpc1_immut_hash, rpc1_obs_dttm,
               rpc2_id, rpc2_chain_id, rpc2_code_hash, rpc2_immut_hash, rpc2_obs_dttm,
               tap_mtch_yn, clbk_mtch_yn, gasless_pass_yn, audit_pass_yn, revoke_drill_yn, launch_gate_yn,
               evdc_stcd, obs_dttm, vld_until_dttm, doc_evdc, doc_evdc_hash,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, 'sweep-runtime-contract-v1', ?, 8453, ?, ?, 1,
                    'RPC_A', 8453, ?, ?, ?, 'RPC_B', 8453, ?, ?, ?,
                    'Y', 'Y', 'Y', 'Y', 'Y', ?, ?, ?, ?, '{}'::jsonb, ?,
                    '830001', '0001', '830001', '0001')
            """.trimIndent(),
            id,
            hash("snapshot-$id"),
            hash("runtime"),
            hash("immutable"),
            hash("runtime"),
            hash("immutable"),
            observedAt,
            hash("runtime"),
            hash("immutable"),
            observedAt,
            launchGate,
            status,
            observedAt,
            validUntil,
            hash("document-$id"),
        )
    }

    private fun insertPolicy() {
        jdbc.update(
            """
            INSERT INTO bcm_plcy_vrsn_l
              (plcy_vrsn_id, plcy_scope_id, vrsn_no, plcy_schm_vrsn, ctrt_vrsn_id,
               plcy_payload, plcy_hash, ceiling_snps, ceiling_hash, ceiling_pass_yn, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('sweep-runtime-policy-v1', 'POLICY:BASE:USDC', 1, 'v1', 'sweep-runtime-contract-v1',
                    CAST(? AS jsonb), ?, '{}'::jsonb, ?, 'Y', '20260819000000',
                    '830001', '0001', '830001', '0001')
            """.trimIndent(),
            """{"enabled":true,"minimumAmount":10,"batchSize":25,"allowanceCap":100,"itemAmountCap":1000,"batchAmountCap":10000,"boostAttempts":1}""",
            hash("policy"),
            hash("policy-ceiling"),
        )
        jdbc.update(
            """
            INSERT INTO bcm_plcy_bind_m
              (plcy_scope_id, actv_plcy_vrsn_id, bind_rvsn, bind_snps_hash, reg_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('POLICY:BASE:USDC', 'sweep-runtime-policy-v1', 1, ?, '20260819000000', '20260819000000',
                    '830001', '0001', '830001', '0001')
            """.trimIndent(),
            hash("policy-binding"),
        )
    }

    private fun hash(seed: String): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(seed.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val NETWORK = "BASE"
        const val SYMBOL = "USDC"
        const val SWEEP_CONTRACT = "0x4444444444444444444444444444444444444444"
        val OBSERVED_AT: Instant = Instant.parse("2026-08-19T01:30:00Z")
        val HARD_CEILING =
            SweepPolicyHardCeiling(
                executionEnabled = true,
                maximumBatchSize = 100,
                maximumAllowance = java.math.BigDecimal("1000"),
                maximumItemAmount = java.math.BigDecimal("10000"),
                maximumBatchAmount = java.math.BigDecimal("100000"),
                maximumBoostAttempts = 3,
            )
    }
}
