package com.whatto.bcm.app.application.admin

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi
import com.whatto.bcm.app.api.BcmApiApplication
import com.whatto.bcm.app.api.support.IntegrationTestSupport
import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminRole
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.io.File

@SpringBootTest(classes = [BcmApiApplication::class])
@AutoConfigureMockMvc
@Transactional
class ExecutionGateQueryIntegrationTest : IntegrationTestSupport() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var externalControls: EmergencyExternalControlObservationService

    @BeforeEach
    fun setUp() {
        insertNetwork("gate-view-a", "GATEVIEWA", 987654310)
        insertNetwork("gate-view-b", "GATEVIEWB", 987654311)
        jdbc.update(
            """
            INSERT INTO bcm_exec_gate_evt_l
              (gate_evt_id, ntwk_cd, gate_dvcd, evt_seq, gate_stcd, req_rsn, work_tckt,
               idmp_key, occr_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              ('gate-view-event', 'GATEVIEWA', 'WITHDRAWAL', 1, 'STOPPED', '출금 이상 징후', 'SEC-1061',
               'gate-view-stop', '20260817130000', '810001', '0001', '810001', '0001')
            """.trimIndent(),
        )
    }

    @Test
    fun `채택 네트워크별 세 게이트와 서버 계산 실행 가능 상태를 조회한다`() {
        mockMvc
            .perform(get("/admin/execution-gates"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.meta.requestId").isString)
            .andExpect(jsonPath("$.data.observedAt").isString)
            .andExpect(jsonPath("$.data.truncated").value(false))
            .andExpect(jsonPath("$.data.gates[?(@.network == 'GATEVIEWA')]").value(hasSize<Any>(3)))
            .andExpect(jsonPath("$.data.gates[0].network").value("GATEVIEWA"))
            .andExpect(jsonPath("$.data.gates[0].type").value("WITHDRAWAL"))
            .andExpect(
                jsonPath("$.data.gates[0].state")
                    .value("STOPPED"),
            ).andExpect(
                jsonPath("$.data.gates[0].newExecutionAllowed")
                    .value(false),
            ).andExpect(
                jsonPath("$.data.gates[0].existingExecutionRecoveryAllowed")
                    .value(true),
            ).andExpect(
                jsonPath("$.data.gates[0].disabledReasons[0]")
                    .value("EXECUTION_GATE_STOPPED"),
            ).andExpect(
                jsonPath("$.data.gates[4].state")
                    .value("OPEN"),
            ).andExpect(
                jsonPath("$.data.gates[4].newExecutionAllowed")
                    .value(false),
            ).andExpect(
                jsonPath("$.data.gates[4].disabledReasons[0]")
                    .value("RELEASE_GATE_NOT_READY"),
            ).andExpect(
                jsonPath("$.data.gates[2].emergencyRevocationAllowed")
                    .value(false),
            ).andExpect(jsonPath("$.data.externalControls").isEmpty)
    }

    @Test
    fun `외부 source가 없는 환경도 ERROR 증적을 남기고 조회 API에서 완료로 표시하지 않는다`() {
        insertContractVersion()
        val actor = AdminActor("810001", "0001", setOf(AdminRole.BCM_OPERATOR))

        val evidence =
            externalControls.observe(
                ObserveEmergencyExternalControlsCommand(
                    versionId = "gate-view-contract",
                    reason = "비상 외부 통제 확인",
                    workTicket = "SEC-1062",
                    idempotencyKey = "gate-view-observe",
                    actor = actor,
                ),
            )

        mockMvc
            .perform(get("/admin/execution-gates"))
            .andExpect(status().isOk)
            .andExpect(openApi().isValid(SPEC))
            .andExpect(jsonPath("$.data.externalControls[0].evidenceId").value(evidence.evidenceId))
            .andExpect(jsonPath("$.data.externalControls[0].network").value("GATEVIEWA"))
            .andExpect(jsonPath("$.data.externalControls[0].status").value("ERROR"))
            .andExpect(jsonPath("$.data.externalControls[0].completionReady").value(false))
            .andExpect(jsonPath("$.data.externalControls[0].tapBlocked").value(null))
            .andExpect(
                jsonPath("$.data.externalControls[0].issues[0]")
                    .value("EXTERNAL_CONTROL_SOURCE_UNCONFIGURED"),
            )
    }

    @Test
    fun `allowance 회수 snapshot과 항목별 최신 상태를 비상 조회에 포함한다`() {
        insertAllowanceRevocation()

        mockMvc
            .perform(get("/admin/execution-gates"))
            .andExpect(status().isOk)
            .andExpect(openApi().isValid(SPEC))
            .andExpect(jsonPath("$.data.allowanceRevocations[0].executionId").value("gate-view-revocation"))
            .andExpect(jsonPath("$.data.allowanceRevocations[0].status").value("READY"))
            .andExpect(jsonPath("$.data.allowanceRevocations[0].totalCount").value(1))
            .andExpect(jsonPath("$.data.allowanceRevocations[0].zeroConfirmedCount").value(0))
            .andExpect(jsonPath("$.data.allowanceRevocations[0].submittingCount").value(0))
            .andExpect(jsonPath("$.data.allowanceRevocations[0].failedCount").value(0))
            .andExpect(jsonPath("$.data.allowanceRevocations[0].items[0].sourceVaultId").value("vault-view-1"))
            .andExpect(jsonPath("$.data.allowanceRevocations[0].items[0].latestStatus").value(null))
    }

    private fun insertAllowanceRevocation() {
        insertContractVersion()
        jdbc.update(
            """
            INSERT INTO bcm_ctrt_bind_m
              (ctrt_scope_id, ntwk_cd, use_dvcd, actv_ctrt_vrsn_id, bind_rvsn,
               bind_snps_hash, reg_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('GATEVIEWA:SWEEP', 'GATEVIEWA', 'SWEEP', 'gate-view-contract', 2,
                    repeat('f', 64), '20260818010000', '20260818010000',
                    '810001', '0001', '810001', '0001')
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO bcm_acnt_m
              (acnt_id, acnt_typ_dvcd, ref, vndr_vlt_id, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('gate-view-account', 'CU', 'gate-view-customer', 'vault-view-1', '20260818010000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO bcm_swp_auth_m
              (acnt_id, ntwk_cd, tkn_smbl, swp_ctrt_addr, alwnc_cap, obs_alwnc, auth_stcd,
               last_chck_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('gate-view-account', 'GATEVIEWA', 'USDC', '0xabc', 100, 25, 'ACTIVE',
                    '20260818010000', 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO bcm_addr_m
              (acnt_id, ntwk_cd, tkn_smbl, dpst_addr, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('gate-view-account', 'GATEVIEWA', 'USDC', '0xowner', '20260818010000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO bcm_vndr_ast_m
              (ntwk_cd, tkn_smbl, vndr_ast_id, cntr_addr, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('GATEVIEWA', 'USDC', 'GATEVIEWA_USDC', '0xtoken', '20260818010000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO bcm_alwnc_rvok_exec_l
              (rvok_exec_id, ctrt_vrsn_id, ctrt_bind_rvsn, ntwk_cd, swp_ctrt_addr,
               tgt_snps_hash, item_cnt, idmp_key, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('gate-view-revocation', 'gate-view-contract', 2, 'GATEVIEWA', '0xabc',
                    repeat('1', 64), 1, 'gate-view-revoke', '20260818011000',
                    '810001', '0001', '810001', '0001')
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO bcm_alwnc_rvok_item_l
              (rvok_exec_id, item_seq, acnt_id, ntwk_cd, tkn_smbl, swp_ctrt_addr,
               src_vlt_id, ownr_addr, tkn_ctrt_addr, bfr_obs_alwnc, ext_tx_id, req_hash,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('gate-view-revocation', 1, 'gate-view-account', 'GATEVIEWA', 'USDC', '0xabc',
                    'vault-view-1', '0xowner', '0xtoken', 25, 'arv-gate-view', repeat('2', 64),
                    '810001', '0001', '810001', '0001')
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO bcm_chng_req_l
              (req_id, tgt_dvcd, scope_id, aft_alwnc_rvok_id, risk_dvcd, base_bind_rvsn,
               tgt_snps_hash, diff_payload, diff_hash, impact_payload, impact_hash,
               req_rsn, work_tckt, idmp_key, req_role_dvcd, req_dttm, expr_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('gate-view-revocation-request', 'ALLOWANCE_REVOKE', 'GATEVIEWA:SWEEP',
                    'gate-view-revocation', 'FUND', 2, repeat('1', 64), '{}'::jsonb,
                    repeat('3', 64), '{}'::jsonb, repeat('4', 64), '비상 회수', 'SEC-1063',
                    'gate-view-revoke-request', 'BCM_OPERATOR', '20260818011000', '20991231235959',
                    '810001', '0001', '810001', '0001')
            """.trimIndent(),
        )
    }

    private fun insertContractVersion() {
        jdbc.update(
            """
            INSERT INTO bcm_ctrt_vrsn_l
              (ctrt_vrsn_id, ctrt_scope_id, ntwk_cd, use_dvcd, vrsn, ctrt_addr,
               release_cmit, artifact_hash, abi_hash, runtime_code_hash, deploy_tx_hash,
               deploy_blck_no, immut_payload, immut_hash, ceiling_payload, ceiling_hash,
               release_uri, reg_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              ('gate-view-contract', 'GATEVIEWA:SWEEP', 'GATEVIEWA', 'SWEEP', 'v1', '0xabc',
               'commit', repeat('a', 64), repeat('b', 64), repeat('c', 64), '0xdeploy',
               100, '{}'::jsonb, repeat('d', 64), '{}'::jsonb, repeat('e', 64),
               'doc://release', '20260818010000', '810001', '0001', '810001', '0001')
            """.trimIndent(),
        )
    }

    private fun insertNetwork(
        candidateId: String,
        network: String,
        chainId: Long,
    ) {
        jdbc.update(
            """
            INSERT INTO bcm_blkc_m
              (vndr_blkc_id, ntwk_cd, chain_id, dspl_nm, test_yn, deprc_yn, sync_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, ?, ?, 'N', 'N', '20260817120000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            candidateId,
            network,
            chainId,
            network,
        )
    }

    private companion object {
        val SPEC: String = File("../../docs/api/openapi.yaml").absolutePath
    }
}
