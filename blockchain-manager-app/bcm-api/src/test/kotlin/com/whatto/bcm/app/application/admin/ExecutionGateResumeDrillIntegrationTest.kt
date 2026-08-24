package com.whatto.bcm.app.application.admin

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi
import com.whatto.bcm.app.api.BcmApiApplication
import com.whatto.bcm.app.api.support.IntegrationTestSupport
import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminRole
import com.whatto.bcm.domain.admin.ChangeDecision
import com.whatto.bcm.domain.admin.EmergencyContractObservation
import com.whatto.bcm.domain.admin.EmergencyExternalControlCandidate
import com.whatto.bcm.domain.admin.EmergencyExternalControlVerificationPort
import com.whatto.bcm.domain.admin.ExecutionGateState
import com.whatto.bcm.domain.admin.ExecutionGateType
import com.whatto.bcm.domain.admin.TapBatchObservation
import com.whatto.bcm.domain.exception.ConflictException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.transaction.TestTransaction
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.transaction.annotation.Transactional
import java.io.File
import java.math.BigInteger
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(classes = [BcmApiApplication::class])
@Import(ExecutionGateResumeDrillIntegrationTest.ResumeVerificationConfig::class)
@AutoConfigureMockMvc
@Transactional
class ExecutionGateResumeDrillIntegrationTest : IntegrationTestSupport() {
    @Autowired
    lateinit var gates: ExecutionGateCommandService

    @Autowired
    lateinit var resumes: ExecutionGateResumeService

    @Autowired
    lateinit var policies: AdminPolicyCommandService

    @Autowired
    lateinit var governance: AdminGovernanceQueryService

    @Autowired
    lateinit var verification: MutableResumeVerificationPort

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setUp() {
        verification.drift = false
        insertPrerequisites()
    }

    @Test
    fun `중지부터 회수와 강화 승인과 외부 정상화 재조회까지 장애 훈련을 관통한다`() {
        val stopped = stop()
        val request = requestResume()
        approve(request.lifecycle.requestId, request.lifecycle.targetSnapshotHash)

        val resumed =
            resumes.resume(
                ResumeExecutionGateCommand(request.lifecycle.requestId, "resume-execute", OPERATOR),
            )

        assertThat(resumed.sequence).isEqualTo(stopped.sequence + 1)
        assertThat(resumed.resumeRequestId).isEqualTo(request.lifecycle.requestId)
        assertThat(resumed.status.name).isEqualTo("RESUMED")
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM bcm_adm_actn_l WHERE req_id = ? AND actn_dvcd = 'RESUME' AND actn_stcd = 'SUCCEEDED'",
                Int::class.java,
                request.lifecycle.requestId,
            ),
        ).isEqualTo(1)
        assertThat(
            com.whatto.bcm.domain.admin.ExecutionGatePolicy
                .availability(ExecutionGateType.SWEEP, resumed)
                .state,
        ).isEqualTo(ExecutionGateState.OPEN)
        val resumeSummary = governance.executionGates().resumes.single()
        assertThat(resumeSummary.state).isEqualTo("RESUMED")
        assertThat(resumeSummary.resumeReady).isTrue()
        assertThat(resumeSummary.retryable).isFalse()
        assertThat(resumeSummary.retryCondition).isEqualTo("COMPLETED")
        assertThat(resumeSummary.statusPath).isEqualTo("/admin/execution-gates")
        val requestDetail = governance.changeRequest(request.lifecycle.requestId)
        assertThat(requestDetail.state).isEqualTo("EXECUTED")
        assertThat(requestDetail.disabledReasons).containsExactly("ALREADY_EXECUTED")
        mockMvc
            .perform(get("/admin/execution-gates"))
            .andExpect(status().isOk)
            .andExpect(openApi().isValid(SPEC))
            .andExpect(jsonPath("$.data.resumes[0].state").value("RESUMED"))
            .andExpect(jsonPath("$.data.resumes[0].resumeReady").value(true))
            .andExpect(jsonPath("$.data.resumes[0].retryable").value(false))
            .andExpect(jsonPath("$.data.resumes[0].retryCondition").value("COMPLETED"))
            .andExpect(jsonPath("$.data.resumes[0].statusPath").value("/admin/execution-gates"))
    }

    @Test
    fun `같은 요청을 동시에 재개해도 한 event만 성공하고 게이트는 열린다`() {
        stop()
        val request = requestResume()
        approve(request.lifecycle.requestId, request.lifecycle.targetSnapshotHash)
        TestTransaction.flagForCommit()
        TestTransaction.end()

        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val results =
                (1..2).map { index ->
                    executor.submit(
                        Callable {
                            ready.countDown()
                            check(start.await(10, TimeUnit.SECONDS))
                            runCatching {
                                resumes.resume(
                                    ResumeExecutionGateCommand(
                                        request.lifecycle.requestId,
                                        "resume-concurrent-$index",
                                        AdminActor("83000${index + 3}", "0001", setOf(AdminRole.BCM_OPERATOR)),
                                    ),
                                )
                            }
                        },
                    )
                }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
            start.countDown()

            val completed = results.map { it.get(15, TimeUnit.SECONDS) }

            assertThat(completed.count { it.isSuccess }).isEqualTo(1)
            assertThat(completed.count { it.isFailure }).isEqualTo(1)
            assertThat(gateEventCount()).isEqualTo(2)
            assertThat(
                jdbc.queryForObject(
                    """
                    SELECT count(*) FROM bcm_exec_gate_evt_l
                     WHERE ntwk_cd = ? AND gate_dvcd = 'SWEEP' AND gate_stcd = 'RESUMED'
                    """.trimIndent(),
                    Int::class.java,
                    NETWORK,
                ),
            ).isEqualTo(1)
        } finally {
            executor.shutdownNow()
            cleanupCommittedFixtures()
        }
    }

    @Test
    fun `같은 멱등 키로 승인 snapshot 입력을 바꾸면 기존 요청을 반환하지 않는다`() {
        stop()
        val original = requestResume()

        assertThat(requestResume().lifecycle.requestId).isEqualTo(original.lifecycle.requestId)

        assertThatThrownBy {
            resumes.request(
                RequestExecutionGateResumeCommand(
                    NETWORK,
                    ExecutionGateType.SWEEP,
                    REVOCATION_ID,
                    "e".repeat(64),
                    "evidence://incidents/INC-8600/changed",
                    "8".repeat(64),
                    "원인 해소와 회수 완료",
                    "INC-8600",
                    "resume-drill-request",
                    Duration.ofHours(1),
                    OPERATOR,
                ),
            )
        }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `완료되지 않은 allowance 회수로는 재개 요청을 만들지 않는다`() {
        stop()
        insertIncompleteRevocation()

        assertThatThrownBy {
            resumes.request(
                RequestExecutionGateResumeCommand(
                    NETWORK,
                    ExecutionGateType.SWEEP,
                    INCOMPLETE_REVOCATION_ID,
                    OPERATOR_HASH,
                    "evidence://incidents/INC-8600/root-cause",
                    "9".repeat(64),
                    "원인 해소와 회수 완료",
                    "INC-8600",
                    "resume-incomplete-revocation",
                    Duration.ofHours(1),
                    OPERATOR,
                ),
            )
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `재개 요청자는 승인자 역할을 함께 가져도 자기 요청을 승인하지 못한다`() {
        stop()
        val request = requestResume()

        assertThatThrownBy {
            policies.decide(
                DecidePolicyChangeCommand(
                    request.lifecycle.requestId,
                    ChangeDecision.APPROVE,
                    request.lifecycle.targetSnapshotHash,
                    "자기 승인 시도",
                    AdminActor("830001", "0001", setOf(AdminRole.BCM_APPROVER)),
                ),
            )
        }.isInstanceOf(com.whatto.bcm.domain.admin.SelfApprovalNotAllowed::class.java)
    }

    @Test
    fun `외부 상태가 drift하면 check를 보존하고 재개 event는 추가하지 않는다`() {
        val stopped = stop()
        val request = requestResume()
        approve(request.lifecycle.requestId, request.lifecycle.targetSnapshotHash)
        verification.drift = true

        assertThatThrownBy {
            resumes.resume(ResumeExecutionGateCommand(request.lifecycle.requestId, "resume-drift", OPERATOR))
        }.isInstanceOf(IllegalStateException::class.java)

        assertThat(gateEventCount()).isEqualTo(1)
        assertThat(
            jdbc.queryForObject(
                "SELECT chk_stcd FROM bcm_exec_gate_rsm_chk_l WHERE rsm_id = ?",
                String::class.java,
                request.lifecycle.targetVersionId,
            ),
        ).isEqualTo("DRIFT")
        assertThat(stopped.status.name).isEqualTo("STOPPED")
    }

    @Test
    fun `승인 뒤 최신 contract evidence가 바뀌면 앱과 DB 우회 재개를 모두 거절한다`() {
        stop()
        val request = requestResume()
        approve(request.lifecycle.requestId, request.lifecycle.targetSnapshotHash)
        insertNewerContractEvidence()

        assertThatThrownBy {
            resumes.resume(ResumeExecutionGateCommand(request.lifecycle.requestId, "resume-stale-evidence", OPERATOR))
        }.isInstanceOf(com.whatto.bcm.domain.admin.StaleChangeSnapshot::class.java)

        jdbc.update(
            """
            INSERT INTO bcm_adm_actn_l
              (actn_id, corr_id, req_id, actn_dvcd, actn_stcd, try_seq, idmp_key, req_hash,
               exp_state, exp_state_hash, occr_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('resume-stale-intent', 'resume-stale-corr', ?, 'RESUME', 'INTENT', 1,
                    'resume-stale-direct', repeat('1', 64), '{}'::jsonb, repeat('2', 64),
                    '20260819010200', '830001', '0001', '830001', '0001')
            """.trimIndent(),
            request.lifecycle.requestId,
        )
        assertThat(gateEventCount()).isEqualTo(1)
        assertThatThrownBy {
            jdbc.update(
                """
                INSERT INTO bcm_exec_gate_evt_l
                  (gate_evt_id, ntwk_cd, gate_dvcd, evt_seq, gate_stcd, req_rsn, work_tckt,
                   idmp_key, rsm_req_id, occr_dttm,
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES ('resume-stale-direct', ?, 'SWEEP', 2, 'RESUMED', '직접 우회', 'INC-8600',
                        'resume-stale-direct', ?, '20260819010201',
                        '830001', '0001', '830001', '0001')
                """.trimIndent(),
                NETWORK,
                request.lifecycle.requestId,
            )
        }.isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
    }

    @Test
    fun `승인자 한 명만 있으면 직전 check가 READY여도 재개하지 않는다`() {
        stop()
        val request = requestResume()
        policies.decide(
            DecidePolicyChangeCommand(
                request.lifecycle.requestId,
                ChangeDecision.APPROVE,
                request.lifecycle.targetSnapshotHash,
                "일반 승인",
                APPROVER,
            ),
        )

        assertThatThrownBy {
            resumes.resume(ResumeExecutionGateCommand(request.lifecycle.requestId, "resume-no-quorum", OPERATOR))
        }.isInstanceOf(com.whatto.bcm.domain.admin.ApprovalQuorumNotSatisfied::class.java)

        assertThat(gateEventCount()).isEqualTo(1)
        assertThat(governance.changeRequest(request.lifecycle.requestId).disabledReasons)
            .doesNotContain("TARGET_NOT_READY")
        assertThatThrownBy {
            jdbc.update(
                """
                INSERT INTO bcm_exec_gate_evt_l
                  (gate_evt_id, ntwk_cd, gate_dvcd, evt_seq, gate_stcd, req_rsn, work_tckt,
                   idmp_key, rsm_req_id, occr_dttm,
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES ('resume-without-quorum', ?, 'SWEEP', 2, 'RESUMED', '직접 우회', 'INC-8600',
                        'resume-without-quorum', ?, '20260819010010',
                        '830001', '0001', '830001', '0001')
                """.trimIndent(),
                NETWORK,
                request.lifecycle.requestId,
            )
        }.isInstanceOf(org.springframework.dao.DataIntegrityViolationException::class.java)
    }

    private fun stop() =
        gates.stop(
            StopExecutionGateCommand(
                NETWORK,
                ExecutionGateType.SWEEP,
                "sweep 사고 격리",
                "INC-8600",
                "resume-drill-stop",
                OPERATOR,
            ),
        )

    private fun requestResume() =
        resumes.request(
            RequestExecutionGateResumeCommand(
                NETWORK,
                ExecutionGateType.SWEEP,
                REVOCATION_ID,
                OPERATOR_HASH,
                "evidence://incidents/INC-8600/root-cause",
                "9".repeat(64),
                "원인 해소와 회수 완료",
                "INC-8600",
                "resume-drill-request",
                Duration.ofHours(1),
                OPERATOR,
            ),
        )

    private fun approve(
        requestId: String,
        snapshotHash: String,
    ) {
        policies.decide(
            DecidePolicyChangeCommand(requestId, ChangeDecision.APPROVE, snapshotHash, "일반 승인", APPROVER),
        )
        policies.decide(
            DecidePolicyChangeCommand(requestId, ChangeDecision.APPROVE, snapshotHash, "보안 승인", SECURITY_APPROVER),
        )
    }

    private fun gateEventCount() =
        jdbc.queryForObject(
            "SELECT count(*) FROM bcm_exec_gate_evt_l WHERE ntwk_cd = ? AND gate_dvcd = 'SWEEP'",
            Int::class.java,
            NETWORK,
        )

    private fun cleanupCommittedFixtures() {
        jdbc.execute("TRUNCATE TABLE bcm_ctrt_vrsn_l CASCADE")
        jdbc.update("DELETE FROM bcm_swp_auth_m WHERE ntwk_cd = ?", NETWORK)
        jdbc.update("DELETE FROM bcm_addr_m WHERE ntwk_cd = ?", NETWORK)
        jdbc.update("DELETE FROM bcm_acnt_m WHERE acnt_id = 'resume-account'")
        jdbc.update("DELETE FROM bcm_vndr_ast_m WHERE ntwk_cd = ?", NETWORK)
        jdbc.update("DELETE FROM bcm_blkc_m WHERE ntwk_cd = ?", NETWORK)
    }

    private fun insertPrerequisites() {
        jdbc.update(
            """
            INSERT INTO bcm_blkc_m
              (vndr_blkc_id, ntwk_cd, chain_id, dspl_nm, test_yn, deprc_yn, sync_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('resume-drill-network', ?, 987654300, 'Resume drill', 'Y', 'N', '20260819010000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            NETWORK,
        )
        jdbc.update(
            """
            INSERT INTO bcm_ctrt_vrsn_l
              (ctrt_vrsn_id, ctrt_scope_id, ntwk_cd, use_dvcd, vrsn, ctrt_addr,
               release_cmit, artifact_hash, abi_hash, runtime_code_hash, deploy_tx_hash,
               deploy_blck_no, immut_payload, immut_hash, ceiling_payload, ceiling_hash,
               release_uri, reg_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (?, ?, ?, 'SWEEP', 'v1', '0xsweep', 'commit', repeat('a', 64), repeat('b', 64),
               repeat('c', 64), '0xdeploy', 1234, '{}'::jsonb, repeat('d', 64), '{}'::jsonb,
               repeat('e', 64), 'repo://resume-drill', '20260819010000',
               '830001', '0001', '830001', '0001')
            """.trimIndent(),
            CONTRACT_ID,
            "$NETWORK:SWEEP",
            NETWORK,
        )
        jdbc.update(
            """
            INSERT INTO bcm_ctrt_evdc_l
              (evdc_id, ctrt_vrsn_id, snps_hash, exp_chain_id, exp_code_hash, exp_immut_hash,
               pin_blck_no, rpc1_id, rpc1_chain_id, rpc1_code_hash, rpc1_immut_hash, rpc1_obs_dttm,
               rpc2_id, rpc2_chain_id, rpc2_code_hash, rpc2_immut_hash, rpc2_obs_dttm,
               tap_mtch_yn, clbk_mtch_yn, gasless_pass_yn, audit_pass_yn, revoke_drill_yn,
               launch_gate_yn, evdc_stcd, obs_dttm, vld_until_dttm, doc_evdc, doc_evdc_hash,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (?, ?, repeat('1', 64), 987654300, repeat('c', 64), repeat('d', 64), 1234,
               'rpc-a', 987654300, repeat('c', 64), repeat('d', 64), '20260819010000',
               'rpc-b', 987654300, repeat('c', 64), repeat('d', 64), '20260819010000',
               'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'VALID', '20260819010000', '20991231235959',
               '{}'::jsonb, repeat('2', 64), '830001', '0001', '830001', '0001')
            """.trimIndent(),
            EVIDENCE_ID,
            CONTRACT_ID,
        )
        jdbc.update(
            """
            INSERT INTO bcm_ctrt_bind_m
              (ctrt_scope_id, ntwk_cd, use_dvcd, actv_ctrt_vrsn_id, bind_rvsn, last_evdc_id,
               bind_snps_hash, reg_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, 'SWEEP', ?, 1, ?, repeat('3', 64), '20260819010000', '20260819010000',
                    '830001', '0001', '830001', '0001')
            """.trimIndent(),
            "$NETWORK:SWEEP",
            NETWORK,
            CONTRACT_ID,
            EVIDENCE_ID,
        )
        insertCompletedRevocation()
    }

    private fun insertCompletedRevocation() {
        jdbc.update(
            """
            INSERT INTO bcm_acnt_m
              (acnt_id, acnt_typ_dvcd, ref, vndr_vlt_id, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('resume-account', 'CU', 'customer', 'vault-resume', '20260819010000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO bcm_addr_m
              (acnt_id, ntwk_cd, tkn_smbl, dpst_addr, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('resume-account', ?, 'USDC', '0xowner', '20260819010000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            NETWORK,
        )
        jdbc.update(
            """
            INSERT INTO bcm_vndr_ast_m
              (ntwk_cd, tkn_smbl, vndr_ast_id, cntr_addr, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, 'USDC', 'RESUME_USDC', '0xtoken', '20260819010000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            NETWORK,
        )
        jdbc.update(
            """
            INSERT INTO bcm_swp_auth_m
              (acnt_id, ntwk_cd, tkn_smbl, swp_ctrt_addr, alwnc_cap, obs_alwnc, auth_stcd,
               last_chck_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('resume-account', ?, 'USDC', '0xsweep', 100, 0, 'REVOKED', '20260819010000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            NETWORK,
        )
        jdbc.update(
            """
            INSERT INTO bcm_alwnc_rvok_exec_l
              (rvok_exec_id, ctrt_vrsn_id, ctrt_bind_rvsn, ntwk_cd, swp_ctrt_addr,
               tgt_snps_hash, item_cnt, idmp_key, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, 1, ?, '0xsweep', repeat('4', 64), 1, 'resume-revoke', '20260819010000',
                    '830001', '0001', '830001', '0001')
            """.trimIndent(),
            REVOCATION_ID,
            CONTRACT_ID,
            NETWORK,
        )
        jdbc.update(
            """
            INSERT INTO bcm_alwnc_rvok_item_l
              (rvok_exec_id, item_seq, acnt_id, ntwk_cd, tkn_smbl, swp_ctrt_addr,
               src_vlt_id, ownr_addr, tkn_ctrt_addr, bfr_obs_alwnc, ext_tx_id, req_hash,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, 1, 'resume-account', ?, 'USDC', '0xsweep', 'vault-resume', '0xowner',
                    '0xtoken', 25, 'arv-resume', repeat('5', 64),
                    '830001', '0001', '830001', '0001')
            """.trimIndent(),
            REVOCATION_ID,
            NETWORK,
        )
        jdbc.update(
            """
            INSERT INTO bcm_chng_req_l
              (req_id, tgt_dvcd, scope_id, aft_alwnc_rvok_id, risk_dvcd, base_bind_rvsn,
               tgt_snps_hash, diff_payload, diff_hash, impact_payload, impact_hash,
               req_rsn, work_tckt, idmp_key, req_role_dvcd, req_dttm, expr_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('resume-revoke-request', 'ALLOWANCE_REVOKE', ?, ?, 'FUND', 1, repeat('4', 64),
                    '{}'::jsonb, repeat('6', 64), '{}'::jsonb, repeat('7', 64), '전량 회수', 'INC-8600',
                    'resume-revoke-request', 'BCM_OPERATOR', '20260819010000', '20991231235959',
                    '830001', '0001', '830001', '0001')
            """.trimIndent(),
            "$NETWORK:SWEEP",
            REVOCATION_ID,
        )
        jdbc.update(
            """
            INSERT INTO bcm_chng_dcsn_l
              (req_id, aprv_empno, aprv_brcd, aprv_role_dvcd, dcsn_dvcd, dcsn_snps_hash,
               dcsn_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('resume-revoke-request', '830002', '0001', 'BCM_APPROVER', 'APPROVE', repeat('4', 64),
                    '20260819010001', '830002', '0001', '830002', '0001')
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO bcm_adm_actn_l
              (actn_id, corr_id, req_id, actn_dvcd, actn_stcd, try_seq, idmp_key, req_hash,
               exp_state, exp_state_hash, occr_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('resume-revoke-action', 'resume-revoke-corr', 'resume-revoke-request', 'EXECUTE',
                    'INTENT', 1, 'resume-revoke-execute', repeat('8', 64), '{}'::jsonb, repeat('8', 64),
                    '20260819010002', '830001', '0001', '830001', '0001')
            """.trimIndent(),
        )
        jdbc.update(revocationEventSql("RESERVED", "NULL"))
        jdbc.update(revocationEventSql("ZERO_CONFIRMED", "0"))
    }

    private fun insertNewerContractEvidence() {
        jdbc.update(
            """
            INSERT INTO bcm_ctrt_evdc_l
              (evdc_id, ctrt_vrsn_id, snps_hash, exp_chain_id, exp_code_hash, exp_immut_hash,
               pin_blck_no, rpc1_id, rpc1_chain_id, rpc1_code_hash, rpc1_immut_hash, rpc1_obs_dttm,
               rpc2_id, rpc2_chain_id, rpc2_code_hash, rpc2_immut_hash, rpc2_obs_dttm,
               tap_mtch_yn, clbk_mtch_yn, gasless_pass_yn, audit_pass_yn, revoke_drill_yn,
               launch_gate_yn, evdc_stcd, obs_dttm, vld_until_dttm, doc_evdc, doc_evdc_hash,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              ('resume-drill-newer-evidence', ?, repeat('7', 64), 987654300, repeat('c', 64), repeat('d', 64),
               1235, 'rpc-a', 987654300, repeat('c', 64), repeat('d', 64), '20260819010100',
               'rpc-b', 987654300, repeat('c', 64), repeat('d', 64), '20260819010100',
               'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'VALID', '20260819010100', '20991231235959',
               '{}'::jsonb, repeat('3', 64), '830001', '0001', '830001', '0001')
            """.trimIndent(),
            CONTRACT_ID,
        )
    }

    private fun insertIncompleteRevocation() {
        jdbc.update(
            """
            INSERT INTO bcm_alwnc_rvok_exec_l
              (rvok_exec_id, ctrt_vrsn_id, ctrt_bind_rvsn, ntwk_cd, swp_ctrt_addr,
               tgt_snps_hash, item_cnt, idmp_key, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, 1, ?, '0xsweep', repeat('5', 64), 1, 'resume-revoke-incomplete',
                    '20260819010000', '830001', '0001', '830001', '0001')
            """.trimIndent(),
            INCOMPLETE_REVOCATION_ID,
            CONTRACT_ID,
            NETWORK,
        )
        jdbc.update(
            """
            INSERT INTO bcm_alwnc_rvok_item_l
              (rvok_exec_id, item_seq, acnt_id, ntwk_cd, tkn_smbl, swp_ctrt_addr,
               src_vlt_id, ownr_addr, tkn_ctrt_addr, bfr_obs_alwnc, ext_tx_id, req_hash,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, 1, 'resume-account', ?, 'USDC', '0xsweep', 'vault-resume', '0xowner',
                    '0xtoken', 25, 'arv-resume-incomplete', repeat('6', 64),
                    '830001', '0001', '830001', '0001')
            """.trimIndent(),
            INCOMPLETE_REVOCATION_ID,
            NETWORK,
        )
    }

    private fun revocationEventSql(
        status: String,
        allowance: String,
    ) = """
        INSERT INTO bcm_alwnc_rvok_evt_l
          (rvok_exec_id, item_seq, evt_seq, rvok_stcd, obs_alwnc, obs_payload, obs_hash,
           occr_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
        VALUES ('$REVOCATION_ID', 1, ${if (status == "RESERVED") 1 else 2}, '$status', $allowance,
                '{}'::jsonb, repeat('${if (status == "RESERVED") "a" else "b"}', 64),
                '20260819010003', '830001', '0001', '830001', '0001')
        """.trimIndent()

    @TestConfiguration
    class ResumeVerificationConfig {
        @Bean
        fun resumeVerificationPort() = MutableResumeVerificationPort()
    }

    class MutableResumeVerificationPort : EmergencyExternalControlVerificationPort {
        var drift: Boolean = false

        override fun collect(
            version: com.whatto.bcm.domain.admin.AdminContractVersion,
            now: Instant,
        ) = ExecutionGateResumeDrillIntegrationTest.candidate(now, drift)
    }

    companion object {
        const val NETWORK = "RESUMEDRILL"
        const val CONTRACT_ID = "resume-drill-contract"
        const val EVIDENCE_ID = "resume-drill-evidence"
        const val REVOCATION_ID = "resume-drill-revocation"
        const val INCOMPLETE_REVOCATION_ID = "resume-revoke-incomplete"
        val OPERATOR_HASH = "f".repeat(64)
        val OPERATOR = AdminActor("830001", "0001", setOf(AdminRole.BCM_OPERATOR))
        val APPROVER = AdminActor("830002", "0001", setOf(AdminRole.BCM_APPROVER))
        val SECURITY_APPROVER = AdminActor("830003", "0001", setOf(AdminRole.BCM_SECURITY_APPROVER))
        val SPEC: String = File("../../docs/api/openapi.yaml").absolutePath

        fun candidate(
            now: Instant,
            drift: Boolean,
        ) = EmergencyExternalControlCandidate(
            "tap-policy",
            TapBatchObservation(false, now),
            BigInteger.valueOf(1234),
            OPERATOR_HASH,
            "rpc-a",
            EmergencyContractObservation(BigInteger.valueOf(1234), false, OPERATOR_HASH, now),
            "rpc-b",
            EmergencyContractObservation(BigInteger.valueOf(1234), drift, OPERATOR_HASH, now),
            emptyList(),
            now,
            now.plusSeconds(300),
        )
    }
}
