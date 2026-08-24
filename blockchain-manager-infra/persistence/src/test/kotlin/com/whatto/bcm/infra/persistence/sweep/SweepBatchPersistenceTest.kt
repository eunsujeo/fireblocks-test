package com.whatto.bcm.infra.persistence.sweep

import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.sweep.SweepAuthorization
import com.whatto.bcm.domain.sweep.SweepAuthorizationKey
import com.whatto.bcm.domain.sweep.SweepAuthorizationStatus
import com.whatto.bcm.domain.sweep.SweepExecution
import com.whatto.bcm.domain.sweep.SweepExecutionStatus
import com.whatto.bcm.domain.sweep.SweepItem
import com.whatto.bcm.domain.sweep.SweepItemReconciliation
import com.whatto.bcm.domain.sweep.SweepItemStatus
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import com.whatto.bcm.infra.persistence.sweep.fixture.SweepTargetFixture.fixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.Connection
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@DataJdbcTest
@Import(SweepAuthorizationJdbcAdapter::class, SweepExecutionJdbcAdapter::class, SweepTargetJdbcAdapter::class)
class SweepBatchPersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var authorizations: SweepAuthorizationJdbcAdapter

    @Autowired
    lateinit var executions: SweepExecutionJdbcAdapter

    @Autowired
    lateinit var targets: SweepTargetJdbcAdapter

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var dataSource: DataSource

    @BeforeEach
    fun setUpAdminSnapshotReferences() {
        jdbc.update(
            """
            INSERT INTO bcm_blkc_m
              (vndr_blkc_id, ntwk_cd, chain_id, dspl_nm, test_yn, deprc_yn, sync_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('ethereum-sweep-test', 'ETHEREUM', 1, 'Ethereum', 'N', 'N', '20260812140000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (ntwk_cd) DO NOTHING
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO bcm_ctrt_vrsn_l
              (ctrt_vrsn_id, ctrt_scope_id, ntwk_cd, use_dvcd, vrsn, ctrt_addr, release_cmit,
               artifact_hash, abi_hash, runtime_code_hash, deploy_tx_hash, deploy_blck_no,
               immut_payload, immut_hash, ceiling_payload, ceiling_hash, release_uri, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('contract-v1', 'ETHEREUM:SWEEP', 'ETHEREUM', 'SWEEP', '1.0.0', '0xsweeper', 'commit',
                    ?, ?, ?, '0xdeploy', 1, '{}'::jsonb, ?, '{}'::jsonb, ?, 'doc://release', '20260812140000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (ctrt_vrsn_id) DO NOTHING
            """.trimIndent(),
            hash("artifact"),
            hash("abi"),
            hash("runtime"),
            hash("immutable"),
            hash("contract-ceiling"),
        )
        jdbc.update(
            """
            INSERT INTO bcm_ctrt_evdc_l
              (evdc_id, ctrt_vrsn_id, snps_hash, exp_chain_id, exp_code_hash, exp_immut_hash, pin_blck_no,
               rpc1_id, rpc1_chain_id, rpc1_code_hash, rpc1_immut_hash, rpc1_obs_dttm,
               rpc2_id, rpc2_chain_id, rpc2_code_hash, rpc2_immut_hash, rpc2_obs_dttm,
               tap_mtch_yn, clbk_mtch_yn, gasless_pass_yn, audit_pass_yn, revoke_drill_yn,
               launch_gate_yn, evdc_stcd, obs_dttm, vld_until_dttm, doc_evdc, doc_evdc_hash,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('evidence-v1', 'contract-v1', ?, 1, ?, ?, 1,
                    'RPC_A', 1, ?, ?, '20260812140000', 'RPC_B', 1, ?, ?, '20260812140000',
                    'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'VALID', '20260812140000', '20991231235959',
                    '{}'::jsonb, ?, 'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (evdc_id) DO NOTHING
            """.trimIndent(),
            hash("evidence"),
            hash("runtime"),
            hash("immutable"),
            hash("runtime"),
            hash("immutable"),
            hash("runtime"),
            hash("immutable"),
            hash("document"),
        )
        jdbc.update(
            """
            INSERT INTO bcm_plcy_vrsn_l
              (plcy_vrsn_id, plcy_scope_id, vrsn_no, plcy_schm_vrsn, ctrt_vrsn_id,
               plcy_payload, plcy_hash, ceiling_snps, ceiling_hash, ceiling_pass_yn, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('policy-v1', 'POLICY:ETHEREUM:USDC', 1, 'v1', 'contract-v1',
                    '{"enabled":true,"minimumAmount":1,"batchSize":25,"allowanceCap":1000,"itemAmountCap":100,"batchAmountCap":100,"boostAttempts":1}'::jsonb, ?,
                    '{}'::jsonb, ?, 'Y', '20260812140000', 'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (plcy_vrsn_id) DO NOTHING
            """.trimIndent(),
            hash("policy"),
            hash("policy-ceiling"),
        )
        jdbc.update(
            """
            INSERT INTO bcm_ctrt_bind_m
              (ctrt_scope_id, ntwk_cd, use_dvcd, actv_ctrt_vrsn_id, bind_rvsn, last_evdc_id,
               bind_snps_hash, reg_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('ETHEREUM:SWEEP', 'ETHEREUM', 'SWEEP', 'contract-v1', 1, 'evidence-v1', ?,
                    '20260812140000', '20260812140000', 'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (ctrt_scope_id) DO NOTHING
            """.trimIndent(),
            hash("contract-binding"),
        )
        jdbc.update(
            """
            INSERT INTO bcm_plcy_bind_m
              (plcy_scope_id, actv_plcy_vrsn_id, bind_rvsn, bind_snps_hash, reg_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('POLICY:ETHEREUM:USDC', 'policy-v1', 1, ?, '20260812140000', '20260812140000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (plcy_scope_id) DO NOTHING
            """.trimIndent(),
            "a".repeat(64),
        )
    }

    @Test
    fun `allowance 관찰 상태를 sweep 컨트랙트별로 보존한다`() {
        val authorization = authorization()

        authorizations.insert(authorization)

        assertThat(authorizations.findByKey(authorization.key)).isEqualTo(authorization)
    }

    @Test
    fun `실행 snapshot은 존재하는 정책 컨트랙트 증적만 참조한다`() {
        val foreignKeys =
            jdbc.queryForList(
                """
                SELECT constraint_name
                  FROM information_schema.table_constraints
                 WHERE table_name = 'bcm_swp_exec_l'
                   AND constraint_type = 'FOREIGN KEY'
                """.trimIndent(),
                String::class.java,
            )

        assertThat(foreignKeys).contains(
            "fk_bcm_swp_exec_policy_version",
            "fk_bcm_swp_exec_contract_version",
            "fk_bcm_swp_exec_contract_evidence",
        )
    }

    @Test
    fun `실행 snapshot 네 컬럼은 모두 NOT NULL이다`() {
        val nullableByColumn =
            jdbc
                .queryForList(
                    """
                    SELECT column_name, is_nullable
                      FROM information_schema.columns
                     WHERE table_name = 'bcm_swp_exec_l'
                       AND column_name IN ('plcy_vrsn_id', 'plcy_snps_hash', 'ctrt_vrsn_id', 'ctrt_evdc_id')
                    """.trimIndent(),
                ).associate { it["column_name"] to it["is_nullable"] }

        assertThat(nullableByColumn).containsOnly(
            org.assertj.core.data.MapEntry
                .entry("plcy_vrsn_id", "NO"),
            org.assertj.core.data.MapEntry
                .entry("plcy_snps_hash", "NO"),
            org.assertj.core.data.MapEntry
                .entry("ctrt_vrsn_id", "NO"),
            org.assertj.core.data.MapEntry
                .entry("ctrt_evdc_id", "NO"),
        )
    }

    @Test
    fun `활성 policy binding snapshot과 다른 실행은 선기록하지 않는다`() {
        val execution = execution().copy(policySnapshotHash = "b".repeat(64))
        targets.insertIfAbsent(fixture(accountId = "customer-1"))
        targets.insertIfAbsent(fixture(accountId = "customer-2"))
        authorizations.insert(authorization("customer-1", "20"))
        authorizations.insert(authorization("customer-2", "30"))

        assertThatThrownBy {
            executions.createAndClaim(
                execution,
                listOf(item(1, "customer-1", "0xsource1", "20"), item(2, "customer-2", "0xsource2", "30")),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `더 최신 INVALID 증적이 있으면 과거 VALID snapshot 실행을 선기록하지 않는다`() {
        jdbc.update(
            """
            INSERT INTO bcm_ctrt_evdc_l
              (evdc_id, ctrt_vrsn_id, snps_hash, exp_chain_id, exp_code_hash, exp_immut_hash, pin_blck_no,
               rpc1_id, rpc2_id, tap_mtch_yn, clbk_mtch_yn, gasless_pass_yn, audit_pass_yn, revoke_drill_yn,
               launch_gate_yn, evdc_stcd, obs_dttm, vld_until_dttm, doc_evdc, doc_evdc_hash,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('evidence-invalid-latest', 'contract-v1', ?, 1, ?, ?, 2, 'RPC_A', 'RPC_B',
                    'N', 'N', 'N', 'N', 'N', 'N', 'INVALID', '20260812150000', '20991231235959',
                    '{}'::jsonb, ?, 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            hash("evidence-invalid-latest"),
            hash("runtime"),
            hash("immutable"),
            hash("document-invalid-latest"),
        )
        val execution = execution()
        targets.insertIfAbsent(fixture(accountId = "customer-1"))
        targets.insertIfAbsent(fixture(accountId = "customer-2"))
        authorizations.insert(authorization("customer-1", "20"))
        authorizations.insert(authorization("customer-2", "30"))

        assertThatThrownBy {
            executions.createAndClaim(
                execution,
                listOf(item(1, "customer-1", "0xsource1", "20"), item(2, "customer-2", "0xsource2", "30")),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `정책 item cap을 넘는 실행 항목은 선기록하지 않는다`() {
        val accountId = "customer-item-cap"
        val execution =
            execution(executionId = "swx-item-cap", externalTransactionId = "swp-item-cap")
                .copy(itemCount = 1, requestedTotalAmount = "110")
        targets.insertIfAbsent(fixture(accountId = accountId))
        authorizations.insert(authorization(accountId, "110"))

        assertThatThrownBy {
            executions.createAndClaim(
                execution,
                listOf(item(1, accountId, "0xitemcapsource", "110", execution.executionId)),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `정책 batch cap을 넘는 실행 총액은 선기록하지 않는다`() {
        val execution =
            execution(executionId = "swx-batch-cap", externalTransactionId = "swp-batch-cap")
                .copy(requestedTotalAmount = "110")
        targets.insertIfAbsent(fixture(accountId = "customer-batch-cap-1"))
        targets.insertIfAbsent(fixture(accountId = "customer-batch-cap-2"))
        authorizations.insert(authorization("customer-batch-cap-1", "50"))
        authorizations.insert(authorization("customer-batch-cap-2", "60"))

        assertThatThrownBy {
            executions.createAndClaim(
                execution,
                listOf(
                    item(1, "customer-batch-cap-1", "0xbatchcapsource1", "50", execution.executionId),
                    item(2, "customer-batch-cap-2", "0xbatchcapsource2", "60", execution.executionId),
                ),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `READY 뒤 최신 증적이 바뀌면 SUBMITTING으로 전이하지 않는다`() {
        val accountId = "customer-stale-before-submit"
        val execution =
            execution(executionId = "swx-stale-before-submit", externalTransactionId = "swp-stale-before-submit")
                .copy(itemCount = 1, requestedTotalAmount = "20")
        targets.insertIfAbsent(fixture(accountId = accountId))
        authorizations.insert(authorization(accountId, "20"))
        executions.createAndClaim(
            execution,
            listOf(item(1, accountId, "0xstalesource", "20", execution.executionId)),
        )
        insertInvalidEvidence("evidence-invalid-before-submit", "20260812150000")

        assertThatThrownBy { executions.markSubmitting(execution.executionId) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `SWEEP 중지가 먼저 선기록되면 READY 실행을 SUBMITTING으로 전이하지 않는다`() {
        val network = "SWEEPSTOP"
        jdbc.update(
            """
            INSERT INTO bcm_blkc_m
              (vndr_blkc_id, ntwk_cd, chain_id, dspl_nm, test_yn, deprc_yn, sync_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('sweep-stop-test', ?, 31337, 'Sweep Stop', 'Y', 'N', '20260812140000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (ntwk_cd) DO NOTHING
            """.trimIndent(),
            network,
        )
        val snapshot = insertActiveSweepAdminSnapshot(jdbc, network, "USDC", "0xstopsweeper", "contract-sweep-stop")
        val accountId = "customer-stop-before-submit"
        val execution =
            execution(executionId = "swx-stop-before-submit", externalTransactionId = "swp-stop-before-submit")
                .copy(
                    network = network,
                    sweepContractAddress = "0xstopsweeper",
                    itemCount = 1,
                    requestedTotalAmount = "20",
                    policyVersionId = snapshot.policyVersionId,
                    policySnapshotHash = snapshot.policySnapshotHash,
                    contractVersionId = snapshot.contractVersionId,
                    contractEvidenceId = snapshot.contractEvidenceId,
                )
        targets.insertIfAbsent(fixture(accountId = accountId, network = network))
        authorizations.insert(
            authorization(accountId, "20").copy(
                key = SweepAuthorizationKey(accountId, network, "USDC", "0xstopsweeper"),
            ),
        )
        executions.createAndClaim(
            execution,
            listOf(item(1, accountId, "0xstopsource", "20", execution.executionId)),
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            dataSource.connection.use { stopConnection ->
                stopConnection.autoCommit = false
                insertStoppedGate(stopConnection, "gate-stop-before-sweep-submit", network, "SWEEP")
                val result =
                    executor.submit<Throwable?> {
                        runCatching {
                            executions.markSubmitting(execution.executionId)
                        }.exceptionOrNull()
                    }

                Thread.sleep(500)
                assertThat(result.isDone).isFalse()
                stopConnection.commit()

                assertThat(result.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ConflictException::class.java)
            }
        } finally {
            executor.shutdownNow()
            jdbc.update("UPDATE bcm_swp_trgt SET actv_swp_exec_id = NULL, actv_item_seq = NULL WHERE acnt_id = ?", accountId)
            jdbc.update("DELETE FROM bcm_swp_item_l WHERE swp_exec_id = ?", execution.executionId)
            jdbc.update("DELETE FROM bcm_swp_exec_l WHERE swp_exec_id = ?", execution.executionId)
            jdbc.update("DELETE FROM bcm_swp_trgt WHERE acnt_id = ?", accountId)
            jdbc.update("DELETE FROM bcm_swp_auth_m WHERE acnt_id = ?", accountId)
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `SWEEP 중지가 먼저 선기록되면 신규 READY 실행과 target claim을 만들지 않는다`() {
        val network = "SWPCRSTOP"
        jdbc.update(
            """
            INSERT INTO bcm_blkc_m
              (vndr_blkc_id, ntwk_cd, chain_id, dspl_nm, test_yn, deprc_yn, sync_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('sweep-create-stop-test', ?, 31339, 'Sweep Create Stop', 'Y', 'N', '20260812140000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (ntwk_cd) DO NOTHING
            """.trimIndent(),
            network,
        )
        val snapshot = insertActiveSweepAdminSnapshot(jdbc, network, "USDC", "0xcreatestopsweeper", "contract-create-stop")
        val accountId = "customer-stop-before-create"
        val execution =
            execution(executionId = "swx-stop-before-create", externalTransactionId = "swp-stop-before-create")
                .copy(
                    network = network,
                    sweepContractAddress = "0xcreatestopsweeper",
                    itemCount = 1,
                    requestedTotalAmount = "20",
                    policyVersionId = snapshot.policyVersionId,
                    policySnapshotHash = snapshot.policySnapshotHash,
                    contractVersionId = snapshot.contractVersionId,
                    contractEvidenceId = snapshot.contractEvidenceId,
                )
        targets.insertIfAbsent(fixture(accountId = accountId, network = network))
        authorizations.insert(
            authorization(accountId, "20").copy(
                key = SweepAuthorizationKey(accountId, network, "USDC", "0xcreatestopsweeper"),
            ),
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            dataSource.connection.use { stopConnection ->
                stopConnection.autoCommit = false
                insertStoppedGate(stopConnection, "gate-stop-before-sweep-create", network, "SWEEP")
                val result =
                    executor.submit<Throwable?> {
                        runCatching {
                            executions.createAndClaim(
                                execution,
                                listOf(item(1, accountId, "0xcreatestopsource", "20", execution.executionId)),
                            )
                        }.exceptionOrNull()
                    }

                Thread.sleep(500)
                assertThat(result.isDone).isFalse()
                stopConnection.commit()

                assertThat(result.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ConflictException::class.java)
                assertThat(executions.findById(execution.executionId)).isNull()
                assertThat(targets.findByKey(fixture(accountId = accountId, network = network).key)?.activeSweepExecutionId)
                    .isNull()
            }
        } finally {
            executor.shutdownNow()
            jdbc.update("UPDATE bcm_swp_trgt SET actv_swp_exec_id = NULL, actv_item_seq = NULL WHERE acnt_id = ?", accountId)
            jdbc.update("DELETE FROM bcm_swp_item_l WHERE swp_exec_id = ?", execution.executionId)
            jdbc.update("DELETE FROM bcm_swp_exec_l WHERE swp_exec_id = ?", execution.executionId)
            jdbc.update("DELETE FROM bcm_swp_trgt WHERE acnt_id = ?", accountId)
            jdbc.update("DELETE FROM bcm_swp_auth_m WHERE acnt_id = ?", accountId)
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `snapshot 검사가 lock을 기다리는 동안 증적이 만료되면 선기록하지 않는다`() {
        val snapshot =
            insertActiveSweepAdminSnapshot(
                jdbc,
                "EXPIRY",
                "USDC",
                "0xexpirysweeper",
                "contract-expiry",
            )
        val observedAt =
            jdbc.queryForObject(
                "SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC', 'YYYYMMDDHH24MISS')",
                String::class.java,
            )!!
        val validUntil =
            jdbc.queryForObject(
                "SELECT to_char(clock_timestamp() AT TIME ZONE 'UTC' + interval '3 seconds', 'YYYYMMDDHH24MISS')",
                String::class.java,
            )!!
        insertValidEvidence("evidence-expiring", snapshot.contractVersionId, observedAt, validUntil)
        val accountId = "customer-expiring-evidence"
        val execution =
            execution(executionId = "swx-expiring-evidence", externalTransactionId = "swp-expiring-evidence")
                .copy(
                    network = "EXPIRY",
                    sweepContractAddress = "0xexpirysweeper",
                    itemCount = 1,
                    requestedTotalAmount = "20",
                    policyVersionId = snapshot.policyVersionId,
                    policySnapshotHash = snapshot.policySnapshotHash,
                    contractVersionId = snapshot.contractVersionId,
                    contractEvidenceId = "evidence-expiring",
                )
        targets.insertIfAbsent(fixture(accountId = accountId, network = "EXPIRY"))
        authorizations.insert(
            authorization(accountId, "20").copy(
                key = SweepAuthorizationKey(accountId, "EXPIRY", "USDC", "0xexpirysweeper"),
            ),
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            dataSource.connection.use { lockConnection ->
                lockConnection.autoCommit = false
                lockConnection
                    .prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
                    .use { statement ->
                        statement.setString(1, "BCM:SWEEP:EVIDENCE:${snapshot.contractVersionId}")
                        statement.execute()
                    }
                val result =
                    executor.submit<Throwable?> {
                        runCatching {
                            executions.createAndClaim(
                                execution,
                                listOf(item(1, accountId, "0xexpiringsource", "20", execution.executionId)),
                            )
                        }.exceptionOrNull()
                    }

                Thread.sleep(4_000)
                assertThat(result.isDone).isFalse()
                lockConnection.commit()

                assertThat(result.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(DataIntegrityViolationException::class.java)
            }
        } finally {
            executor.shutdownNow()
            jdbc.update(
                "UPDATE bcm_swp_trgt SET actv_swp_exec_id = NULL, actv_item_seq = NULL WHERE acnt_id = ?",
                accountId,
            )
            jdbc.update("DELETE FROM bcm_swp_item_l WHERE swp_exec_id = ?", execution.executionId)
            jdbc.update("DELETE FROM bcm_swp_exec_l WHERE swp_exec_id = ?", execution.executionId)
            jdbc.update("DELETE FROM bcm_swp_trgt WHERE acnt_id = ?", accountId)
            jdbc.update("DELETE FROM bcm_swp_auth_m WHERE acnt_id = ?", accountId)
        }
    }

    @Test
    fun `실행 1건과 항목 N건을 만들며 대상 claim을 같은 트랜잭션으로 묶는다`() {
        targets.insertIfAbsent(fixture(accountId = "customer-1"))
        targets.insertIfAbsent(fixture(accountId = "customer-2"))
        authorizations.insert(authorization("customer-1", "20"))
        authorizations.insert(authorization("customer-2", "30"))
        val execution = execution()
        val items = listOf(item(1, "customer-1", "0xsource1", "20"), item(2, "customer-2", "0xsource2", "30"))

        executions.createAndClaim(execution, items)

        assertThat(executions.findById(execution.executionId)).isEqualTo(execution)
        assertThat(executions.findItems(execution.executionId)).containsExactlyElementsOf(items)
        assertThat(targets.findByKey(fixture(accountId = "customer-1").key))
            .extracting("activeSweepExecutionId", "activeItemSequence")
            .containsExactly(execution.executionId, 1)
        assertThat(targets.findByKey(fixture(accountId = "customer-2").key))
            .extracting("activeSweepExecutionId", "activeItemSequence")
            .containsExactly(execution.executionId, 2)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `대상 하나라도 이미 claim이면 실행과 항목을 모두 롤백한다`() {
        val accountId = "rollback-customer"
        val first =
            execution(executionId = "swx-rollback-first", externalTransactionId = "swp-rollback-first")
                .copy(itemCount = 1, requestedTotalAmount = "20")
        val conflicting =
            execution(executionId = "swx-rollback-conflict", externalTransactionId = "swp-rollback-conflict")
                .copy(itemCount = 1, requestedTotalAmount = "20")

        try {
            targets.insertIfAbsent(fixture(accountId = accountId))
            authorizations.insert(authorization(accountId, "20"))
            executions.createAndClaim(
                first,
                listOf(item(1, accountId, "0xrollbacksource", "20", executionId = first.executionId)),
            )
            assertThatThrownBy {
                executions.createAndClaim(
                    conflicting,
                    listOf(item(1, accountId, "0xrollbacksource", "20", executionId = conflicting.executionId)),
                )
            }.isInstanceOf(ConflictException::class.java)

            assertThat(executions.findById(conflicting.executionId)).isNull()
            assertThat(
                jdbc.queryForObject(
                    "SELECT COUNT(*) FROM bcm_swp_item_l WHERE swp_exec_id IN (?, ?)",
                    Long::class.java,
                    first.executionId,
                    conflicting.executionId,
                ),
            ).isEqualTo(1)
        } finally {
            jdbc.update("UPDATE bcm_swp_trgt SET actv_swp_exec_id = NULL, actv_item_seq = NULL WHERE acnt_id = ?", accountId)
            jdbc.update("DELETE FROM bcm_swp_item_l WHERE swp_exec_id = ?", first.executionId)
            jdbc.update("DELETE FROM bcm_swp_exec_l WHERE swp_exec_id = ?", first.executionId)
            jdbc.update("DELETE FROM bcm_swp_trgt WHERE acnt_id = ?", accountId)
            jdbc.update("DELETE FROM bcm_swp_auth_m WHERE acnt_id = ?", accountId)
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `allowance가 ACTIVE가 아니거나 요청 금액보다 작으면 실행 전체를 만들지 않는다`() {
        val execution =
            execution(executionId = "swx-insufficient", externalTransactionId = "swp-insufficient")
                .copy(itemCount = 1, requestedTotalAmount = "20")
        try {
            targets.insertIfAbsent(fixture(accountId = "customer-insufficient"))
            authorizations.insert(authorization("customer-insufficient", "19"))

            assertThatThrownBy {
                executions.createAndClaim(
                    execution,
                    listOf(item(1, "customer-insufficient", "0xsource", "20", execution.executionId)),
                )
            }.isInstanceOf(ConflictException::class.java)

            assertThat(executions.findById(execution.executionId)).isNull()
            assertThat(targets.findByKey(fixture(accountId = "customer-insufficient").key)?.activeSweepExecutionId).isNull()
        } finally {
            jdbc.update("DELETE FROM bcm_swp_trgt WHERE acnt_id = 'customer-insufficient'")
            jdbc.update("DELETE FROM bcm_swp_auth_m WHERE acnt_id = 'customer-insufficient'")
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `운영 계정별 미접수 실행은 하나이며 SUBMITTED 뒤 다음 실행을 만들 수 있다`() {
        val first =
            execution(executionId = "swx-operator-1", externalTransactionId = "swp-operator-1")
                .copy(itemCount = 1, requestedTotalAmount = "20")
        val second =
            execution(executionId = "swx-operator-2", externalTransactionId = "swp-operator-2")
                .copy(itemCount = 1, requestedTotalAmount = "20")
        try {
            targets.insertIfAbsent(fixture(accountId = "operator-lock-1"))
            targets.insertIfAbsent(fixture(accountId = "operator-lock-2"))
            authorizations.insert(authorization("operator-lock-1", "20"))
            authorizations.insert(authorization("operator-lock-2", "20"))
            executions.createAndClaim(first, listOf(item(1, "operator-lock-1", "0xsource1", "20", first.executionId)))

            assertThatThrownBy {
                executions.createAndClaim(second, listOf(item(1, "operator-lock-2", "0xsource2", "20", second.executionId)))
            }.isInstanceOf(ConflictException::class.java)

            assertThat(executions.findPendingSubmission("operator-1")?.executionId).isEqualTo(first.executionId)
            assertThat(executions.markSubmitting(first.executionId).status).isEqualTo(SweepExecutionStatus.SUBMITTING)
            assertThat(executions.markSubmitted(first.executionId, "vendor-batch-1").status)
                .isEqualTo(SweepExecutionStatus.SUBMITTED)
            executions.createAndClaim(second, listOf(item(1, "operator-lock-2", "0xsource2", "20", second.executionId)))
            assertThat(executions.findById(second.executionId)).isEqualTo(second)
        } finally {
            jdbc.update("UPDATE bcm_swp_trgt SET actv_swp_exec_id = NULL, actv_item_seq = NULL WHERE acnt_id LIKE 'operator-lock-%'")
            jdbc.update("DELETE FROM bcm_swp_item_l WHERE swp_exec_id IN (?, ?)", first.executionId, second.executionId)
            jdbc.update("DELETE FROM bcm_swp_exec_l WHERE swp_exec_id IN (?, ?)", first.executionId, second.executionId)
            jdbc.update("DELETE FROM bcm_swp_trgt WHERE acnt_id LIKE 'operator-lock-%'")
            jdbc.update("DELETE FROM bcm_swp_auth_m WHERE acnt_id LIKE 'operator-lock-%'")
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `확정 제출 거절은 READY 항목을 RETRY로 바꾸고 target claim을 해제한다`() {
        val failed =
            execution(executionId = "swx-rejected", externalTransactionId = "swp-rejected")
                .copy(itemCount = 1, requestedTotalAmount = "20")
        val accountId = "rejected-customer"
        try {
            targets.insertIfAbsent(fixture(accountId = accountId))
            authorizations.insert(authorization(accountId, "20"))
            executions.createAndClaim(
                failed,
                listOf(item(1, accountId, "0xrejectedsource", "20", failed.executionId)),
            )

            val result = executions.markFailedAndRelease(failed.executionId, "20260812150100")

            assertThat(result.status).isEqualTo(SweepExecutionStatus.FAILED)
            assertThat(result.finishedAt).isEqualTo("20260812150100")
            assertThat(executions.findItems(failed.executionId).single().status).isEqualTo(SweepItemStatus.RETRY)
            assertThat(targets.findByKey(fixture(accountId = accountId).key)?.activeSweepExecutionId).isNull()
        } finally {
            jdbc.update("UPDATE bcm_swp_trgt SET actv_swp_exec_id = NULL, actv_item_seq = NULL WHERE acnt_id = ?", accountId)
            jdbc.update("DELETE FROM bcm_swp_item_l WHERE swp_exec_id = ?", failed.executionId)
            jdbc.update("DELETE FROM bcm_swp_exec_l WHERE swp_exec_id = ?", failed.executionId)
            jdbc.update("DELETE FROM bcm_swp_trgt WHERE acnt_id = ?", accountId)
            jdbc.update("DELETE FROM bcm_swp_auth_m WHERE acnt_id = ?", accountId)
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `종결 웹훅부터 항목별 부분 성공까지 실행 상태와 결과를 보존한다`() {
        val execution = execution(executionId = "swx-reconcile", externalTransactionId = "swp-reconcile")
        val firstAccount = "reconcile-customer-1"
        val secondAccount = "reconcile-customer-2"
        try {
            targets.insertIfAbsent(fixture(accountId = firstAccount))
            targets.insertIfAbsent(fixture(accountId = secondAccount))
            authorizations.insert(authorization(firstAccount, "20"))
            authorizations.insert(authorization(secondAccount, "30"))
            executions.createAndClaim(
                execution,
                listOf(
                    item(1, firstAccount, "0xsource1", "20", execution.executionId),
                    item(2, secondAccount, "0xsource2", "30", execution.executionId),
                ),
            )
            executions.markSubmitting(execution.executionId)
            executions.markSubmitted(execution.executionId, "vendor-reconcile")

            val reconciling =
                executions.markReconciling(execution.executionId, "vendor-reconcile", "0xhash-reconcile")

            assertThat(reconciling.status).isEqualTo(SweepExecutionStatus.RECONCILING)
            assertThat(reconciling.transactionHash).isEqualTo("0xhash-reconcile")
            assertThat(executions.findReconciling(10).map { it.executionId }).contains(execution.executionId)

            val completed =
                executions.completeReconciliation(
                    execution.executionId,
                    listOf(
                        SweepItemReconciliation(1, "20", SweepItemStatus.SUCCEEDED, null, 7),
                        SweepItemReconciliation(2, "0", SweepItemStatus.FAILED, "1".padStart(64, '0'), 8),
                    ),
                    SweepExecutionStatus.PARTIAL,
                    "20",
                    "20260812150200",
                )
            assertThat(targets.deleteClaim(fixture(accountId = firstAccount).key, execution.executionId, 1)).isTrue()
            assertThat(targets.releaseClaim(fixture(accountId = secondAccount).key, execution.executionId, 2)).isTrue()

            assertThat(completed.status).isEqualTo(SweepExecutionStatus.PARTIAL)
            assertThat(completed.actualTotalAmount).isEqualTo("20")
            assertThat(completed.finishedAt).isEqualTo("20260812150200")
            assertThat(
                executions.markReconciling(execution.executionId, "vendor-reconcile", "0xhash-reconcile").status,
            ).isEqualTo(SweepExecutionStatus.PARTIAL)
            assertThat(executions.findItems(execution.executionId).map { it.status })
                .containsExactly(SweepItemStatus.SUCCEEDED, SweepItemStatus.FAILED)
            assertThat(targets.findByKey(fixture(accountId = firstAccount).key)).isNull()
            assertThat(targets.findByKey(fixture(accountId = secondAccount).key)?.activeSweepExecutionId).isNull()
        } finally {
            jdbc.update(
                "UPDATE bcm_swp_trgt SET actv_swp_exec_id = NULL, actv_item_seq = NULL WHERE acnt_id IN (?, ?)",
                firstAccount,
                secondAccount,
            )
            jdbc.update("DELETE FROM bcm_swp_item_l WHERE swp_exec_id = ?", execution.executionId)
            jdbc.update("DELETE FROM bcm_swp_exec_l WHERE swp_exec_id = ?", execution.executionId)
            jdbc.update("DELETE FROM bcm_swp_trgt WHERE acnt_id IN (?, ?)", firstAccount, secondAccount)
            jdbc.update("DELETE FROM bcm_swp_auth_m WHERE acnt_id IN (?, ?)", firstAccount, secondAccount)
        }
    }

    private fun insertValidEvidence(
        id: String,
        contractVersionId: String,
        observedAt: String,
        validUntil: String,
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
            VALUES (?, ?, ?, 1, ?, ?, 3, 'RPC_A', 1, ?, ?, ?, 'RPC_B', 1, ?, ?, ?,
                    'Y', 'Y', 'Y', 'Y', 'Y', 'Y', 'VALID', ?, ?, '{}'::jsonb, ?,
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            id,
            contractVersionId,
            hash("snapshot-$id"),
            hash("runtime"),
            hash("immutable"),
            hash("runtime"),
            hash("immutable"),
            observedAt,
            hash("runtime"),
            hash("immutable"),
            observedAt,
            observedAt,
            validUntil,
            hash("document-$id"),
        )
    }

    private fun insertInvalidEvidence(
        id: String,
        observedAt: String,
    ) {
        jdbc.update(
            """
            INSERT INTO bcm_ctrt_evdc_l
              (evdc_id, ctrt_vrsn_id, snps_hash, exp_chain_id, exp_code_hash, exp_immut_hash, pin_blck_no,
               rpc1_id, rpc2_id, tap_mtch_yn, clbk_mtch_yn, gasless_pass_yn, audit_pass_yn, revoke_drill_yn,
               launch_gate_yn, evdc_stcd, obs_dttm, vld_until_dttm, doc_evdc, doc_evdc_hash,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, 'contract-v1', ?, 1, ?, ?, 4, 'RPC_A', 'RPC_B',
                    'N', 'N', 'N', 'N', 'N', 'N', 'INVALID', ?, '20991231235959',
                    '{}'::jsonb, ?, 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            id,
            hash("snapshot-$id"),
            hash("runtime"),
            hash("immutable"),
            observedAt,
            hash("document-$id"),
        )
    }

    private fun insertStoppedGate(
        connection: Connection,
        eventId: String,
        network: String,
        type: String,
    ) {
        connection
            .prepareStatement(
                """
                INSERT INTO bcm_exec_gate_evt_l
                  (gate_evt_id, ntwk_cd, gate_dvcd, evt_seq, gate_stcd, req_rsn, work_tckt,
                   idmp_key, rsm_req_id, occr_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES (?, ?, ?, 1, 'STOPPED', 'test stop', 'SEC-STOP', ?, NULL, '20260812150100',
                        '810001', '0001', '810001', '0001')
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, eventId)
                statement.setString(2, network)
                statement.setString(3, type)
                statement.setString(4, eventId)
                statement.executeUpdate()
            }
    }

    private fun authorization(
        accountId: String = "customer-1",
        observedAllowance: String = "80",
    ) = SweepAuthorization(
        key = SweepAuthorizationKey(accountId, "ETHEREUM", "USDC", "0xsweeper"),
        allowanceCap = "100",
        observedAllowance = observedAllowance,
        status = SweepAuthorizationStatus.ACTIVE,
        approvalExternalTransactionId = "swa-1",
        approvalVendorTransactionId = "vendor-approve-1",
        lastCheckedAt = "20260812150000",
    )

    private fun execution(
        executionId: String = "swx-1",
        externalTransactionId: String = "swp-1",
    ) = SweepExecution(
        executionId = executionId,
        externalTransactionId = externalTransactionId,
        requestHash = "a".repeat(64),
        network = "ETHEREUM",
        symbol = "USDC",
        operatorAccountId = "operator-1",
        sweepContractAddress = "0xsweeper",
        status = SweepExecutionStatus.READY,
        itemCount = 2,
        requestedTotalAmount = "50",
        actualTotalAmount = null,
        gasless = true,
        vendorTransactionId = null,
        transactionHash = null,
        requestedAt = "20260812150000",
        finishedAt = null,
        policyVersionId = "policy-v1",
        policySnapshotHash = "a".repeat(64),
        contractVersionId = "contract-v1",
        contractEvidenceId = "evidence-v1",
    )

    private fun item(
        sequence: Int,
        accountId: String,
        sourceAddress: String,
        amount: String,
        executionId: String = "swx-1",
    ) = SweepItem(
        executionId = executionId,
        sequence = sequence,
        accountId = accountId,
        sourceAddress = sourceAddress,
        requestedAmount = amount,
        actualAmount = null,
        status = SweepItemStatus.READY,
        failureCode = null,
        logIndex = null,
    )

    private fun hash(seed: String): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(seed.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
