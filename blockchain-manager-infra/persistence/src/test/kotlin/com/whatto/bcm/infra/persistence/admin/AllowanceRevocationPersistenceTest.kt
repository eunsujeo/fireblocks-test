package com.whatto.bcm.infra.persistence.admin

import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminGovernanceQueryRepository
import com.whatto.bcm.domain.admin.AdminRole
import com.whatto.bcm.domain.admin.AllowanceRevocationEvent
import com.whatto.bcm.domain.admin.AllowanceRevocationEventStatus
import com.whatto.bcm.domain.admin.AllowanceRevocationExecution
import com.whatto.bcm.domain.admin.AllowanceRevocationExecutionStatus
import com.whatto.bcm.domain.admin.AllowanceRevocationRepository
import com.whatto.bcm.domain.admin.AllowanceRevocationTarget
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.time.Instant

@DataJdbcTest
@Import(
    AllowanceRevocationJdbcAdapter::class,
    AdminPolicyJdbcAdapter::class,
    AdminGovernanceQueryJdbcAdapter::class,
    WebhookRecoveryJdbcAdapter::class,
)
class AllowanceRevocationPersistenceTest : PersistenceTestSupport() {
    @Autowired lateinit var revocations: AllowanceRevocationRepository

    @Autowired lateinit var governance: AdminGovernanceQueryRepository

    @Autowired lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun setUp() {
        insertBaseFixture()
    }

    @Test
    fun `불변 실행과 target을 왕복하고 update를 거절한다`() {
        insertExecution()

        val found = requireNotNull(revocations.findExecution(EXECUTION_ID))
        assertThat(found.execution.contractBindingRevision).isEqualTo(3)
        assertThat(found.targets.single().externalTransactionId).isEqualTo(EXTERNAL_ID)
        assertThat(found.status).isEqualTo(AllowanceRevocationExecutionStatus.READY)

        assertThatThrownBy {
            jdbc.update("UPDATE bcm_alwnc_rvok_exec_l SET item_cnt = 2 WHERE rvok_exec_id = ?", EXECUTION_ID)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `승인과 실행 intent가 있어야 예약되고 active batch가 생기면 제출 intent를 막는다`() {
        insertExecution()
        insertApprovedRequest()
        revocations.appendEvent(event(1, AllowanceRevocationEventStatus.RESERVED))
        insertActiveBatch()

        assertThatThrownBy {
            revocations.appendEvent(
                event(
                    2,
                    AllowanceRevocationEventStatus.SUBMIT_INTENT,
                    externalTransactionId = EXTERNAL_ID,
                    observedAllowance = BigDecimal.TEN,
                ),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `snapshot hash가 다른 회수 요청은 DB가 거절한다`() {
        insertExecution()

        assertThatThrownBy {
            insertRequest("f".repeat(64))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `승인 snapshot에서 양수 allowance 대상이 빠지면 요청을 거절한다`() {
        insertExecution()
        insertAdditionalPositiveAuthorization()

        assertThatThrownBy { insertRequest(SNAPSHOT_HASH) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `승인 뒤 source vault가 바뀌면 예약을 거절한다`() {
        insertExecution()
        insertApprovedRequest()
        jdbc.update("UPDATE bcm_acnt_m SET vndr_vlt_id = 'vault-drifted' WHERE acnt_id = 'account-1'")

        assertThatThrownBy {
            revocations.appendEvent(event(1, AllowanceRevocationEventStatus.RESERVED))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `Admin 조회는 항목 최신 event로 진행 건수를 계산한다`() {
        insertExecution()
        insertApprovedRequest()
        revocations.appendEvent(event(1, AllowanceRevocationEventStatus.RESERVED))
        revocations.appendEvent(event(2, AllowanceRevocationEventStatus.ZERO_CONFIRMED, observedAllowance = BigDecimal.ZERO))

        val summary = governance.findAllowanceRevocations(10).single()

        assertThat(summary.status).isEqualTo(AllowanceRevocationExecutionStatus.COMPLETED)
        assertThat(summary.zeroConfirmedCount).isEqualTo(1)
        assertThat(summary.submittingCount).isZero()
        assertThat(summary.failedCount).isZero()
        assertThat(summary.retryable).isFalse()
        assertThat(summary.retryCondition).isEqualTo("COMPLETED")
        assertThat(summary.statusPath).isEqualTo("/admin/execution-gates")
        assertThat(summary.items.single().latestStatus).isEqualTo(AllowanceRevocationEventStatus.ZERO_CONFIRMED)
    }

    private fun insertExecution() {
        revocations.insertExecution(execution(), listOf(target()))
    }

    private fun insertBaseFixture() {
        jdbc.update(
            """
            INSERT INTO bcm_blkc_m
              (vndr_blkc_id, ntwk_cd, chain_id, dspl_nm, test_yn, deprc_yn, sync_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('base-chain', 'BASE', 8453, 'Base', 'N', 'N', '20260818010000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO bcm_acnt_m
              (acnt_id, acnt_typ_dvcd, ref, vndr_vlt_id, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('account-1', 'CU', 'customer-1', 'vault-1', '20260818010000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO bcm_swp_auth_m
              (acnt_id, ntwk_cd, tkn_smbl, swp_ctrt_addr, alwnc_cap, obs_alwnc, auth_stcd,
               last_chck_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('account-1', 'BASE', 'USDC', ?, 100, 25, 'ACTIVE', '20260818010000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            SWEEP_CONTRACT,
        )
        jdbc.update(
            """
            INSERT INTO bcm_addr_m
              (acnt_id, ntwk_cd, tkn_smbl, dpst_addr, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('account-1', 'BASE', 'USDC', ?, '20260818010000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            OWNER,
        )
        jdbc.update(
            """
            INSERT INTO bcm_vndr_ast_m
              (ntwk_cd, tkn_smbl, vndr_ast_id, cntr_addr, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('BASE', 'USDC', 'BASE_USDC', ?, '20260818010000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            TOKEN,
        )
        jdbc.update(
            """
            INSERT INTO bcm_ctrt_vrsn_l
              (ctrt_vrsn_id, ctrt_scope_id, ntwk_cd, use_dvcd, vrsn, ctrt_addr, release_cmit,
               artifact_hash, abi_hash, runtime_code_hash, deploy_tx_hash, deploy_blck_no,
               immut_payload, immut_hash, ceiling_payload, ceiling_hash, release_uri, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('contract-v1', 'BASE:SWEEP', 'BASE', 'SWEEP', '1.0.0', ?, 'commit',
                    ?, ?, ?, '0xdeploy', 1, '{}'::jsonb, ?, '{}'::jsonb, ?, 'doc://release', '20260818010000',
                    '830001', '0001', '830001', '0001')
            """.trimIndent(),
            SWEEP_CONTRACT,
            hash("artifact"),
            hash("abi"),
            hash("runtime"),
            hash("immutable"),
            hash("ceiling"),
        )
        jdbc.update(
            """
            INSERT INTO bcm_ctrt_bind_m
              (ctrt_scope_id, ntwk_cd, use_dvcd, actv_ctrt_vrsn_id, bind_rvsn,
               bind_snps_hash, reg_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('BASE:SWEEP', 'BASE', 'SWEEP', 'contract-v1', 3, ?, '20260818010000', '20260818010000',
                    '830001', '0001', '830001', '0001')
            """.trimIndent(),
            hash("binding"),
        )
    }

    private fun insertApprovedRequest() {
        insertRequest(SNAPSHOT_HASH)
        jdbc.update(
            """
            INSERT INTO bcm_chng_dcsn_l
              (req_id, aprv_empno, aprv_brcd, aprv_role_dvcd, dcsn_dvcd, dcsn_snps_hash,
               dcsn_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('request-1', '830002', '0002', 'BCM_APPROVER', 'APPROVE', ?, '20260818011000',
                    '830002', '0002', '830002', '0002')
            """.trimIndent(),
            SNAPSHOT_HASH,
        )
        jdbc.update(
            """
            INSERT INTO bcm_adm_actn_l
              (actn_id, corr_id, req_id, actn_dvcd, actn_stcd, try_seq, idmp_key,
               req_hash, exp_state, exp_state_hash, occr_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('action-1', 'correlation-1', 'request-1', 'EXECUTE', 'INTENT', 1, 'execute-1',
                    ?, '{}'::jsonb, ?, '20260818012000', '830001', '0001', '830001', '0001')
            """.trimIndent(),
            hash("request"),
            hash("expected"),
        )
    }

    private fun insertRequest(snapshotHash: String) {
        jdbc.update(
            """
            INSERT INTO bcm_chng_req_l
              (req_id, tgt_dvcd, scope_id, aft_alwnc_rvok_id, risk_dvcd, base_bind_rvsn,
               tgt_snps_hash, diff_payload, diff_hash, impact_payload, impact_hash,
               req_rsn, work_tckt, idmp_key, req_role_dvcd, req_dttm, expr_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('request-1', 'ALLOWANCE_REVOKE', 'BASE:SWEEP', ?, 'FUND', 3,
                    ?, '{}'::jsonb, ?, '{}'::jsonb, ?, '사고 대응', 'INC-1063', 'revoke-1',
                    'BCM_OPERATOR', '20260818010000', '20991231235959',
                    '830001', '0001', '830001', '0001')
            """.trimIndent(),
            EXECUTION_ID,
            snapshotHash,
            hash("diff"),
            hash("impact"),
        )
    }

    private fun insertActiveBatch() {
        val snapshot = insertActiveSweepAdminSnapshot(jdbc, "BASE", "USDC", SWEEP_CONTRACT)
        jdbc.update(
            """
            INSERT INTO bcm_swp_exec_l
              (swp_exec_id, ext_tx_id, req_hash, ntwk_cd, tkn_smbl, opr_acnt_id, swp_ctrt_addr,
               plcy_vrsn_id, plcy_snps_hash, ctrt_vrsn_id, ctrt_evdc_id,
               swp_exec_stcd, item_cnt, req_tot_amt, gasless_yn, req_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('sweep-active', 'swp-active', ?, 'BASE', 'USDC', 'operator-1', ?, ?, ?, ?, ?,
                    'SUBMITTED', 1, 10, 'Y', '20260818012000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            hash("sweep"),
            SWEEP_CONTRACT,
            snapshot.policyVersionId,
            snapshot.policySnapshotHash,
            snapshot.contractVersionId,
            snapshot.contractEvidenceId,
        )
    }

    private fun insertAdditionalPositiveAuthorization() {
        jdbc.update(
            """
            INSERT INTO bcm_acnt_m
              (acnt_id, acnt_typ_dvcd, ref, vndr_vlt_id, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('account-2', 'CU', 'customer-2', 'vault-2', '20260818010000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO bcm_swp_auth_m
              (acnt_id, ntwk_cd, tkn_smbl, swp_ctrt_addr, alwnc_cap, obs_alwnc, auth_stcd,
               last_chck_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('account-2', 'BASE', 'USDC', ?, 100, 10, 'ACTIVE', '20260818010000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            SWEEP_CONTRACT,
        )
    }

    private fun execution() =
        AllowanceRevocationExecution(
            EXECUTION_ID,
            "contract-v1",
            3,
            "BASE",
            SWEEP_CONTRACT,
            SNAPSHOT_HASH,
            1,
            "revoke-1",
            NOW,
            OPERATOR,
        )

    private fun target() =
        AllowanceRevocationTarget(
            EXECUTION_ID,
            1,
            "account-1",
            "BASE",
            "USDC",
            SWEEP_CONTRACT,
            "vault-1",
            OWNER,
            TOKEN,
            BigDecimal("25"),
            EXTERNAL_ID,
            hash("request-hash"),
        )

    private fun event(
        sequence: Int,
        status: AllowanceRevocationEventStatus,
        externalTransactionId: String? = null,
        observedAllowance: BigDecimal? = null,
    ) = AllowanceRevocationEvent(
        EXECUTION_ID,
        1,
        sequence,
        status,
        externalTransactionId,
        null,
        observedAllowance,
        "{}",
        hash("event-$sequence"),
        null,
        NOW,
        OPERATOR,
    )

    private fun hash(seed: String): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(seed.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-18T01:00:00Z")
        val OPERATOR = AdminActor("830001", "0001", setOf(AdminRole.BCM_OPERATOR))
        const val EXECUTION_ID = "revocation-1"
        const val EXTERNAL_ID = "arv-1"
        const val SNAPSHOT_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val SWEEP_CONTRACT = "0x0000000000000000000000000000000000000001"
        const val OWNER = "0x0000000000000000000000000000000000000002"
        const val TOKEN = "0x0000000000000000000000000000000000000003"
    }
}
