package com.whatto.bcm.infra.persistence.admin

import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DataJdbcTest
class AdminPolicyLedgerConstraintTest : PersistenceTestSupport() {
    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    @Test
    fun `version과 요청 원장은 update와 delete를 물리적으로 거절한다`() {
        insertPolicy("policy-v1", "POLICY:BASE:USDC", 1, ceilingPassed = true)

        assertThatThrownBy {
            jdbc.update("UPDATE bcm_plcy_vrsn_l SET plcy_schm_vrsn = 'v2' WHERE plcy_vrsn_id = 'policy-v1'")
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `요청자는 자기 요청을 승인할 수 없다`() {
        insertPolicy("policy-v2", "POLICY:BASE:USDC", 2, ceilingPassed = true)
        insertBinding("POLICY:BASE:USDC")
        insertRequest("req-self", "policy-v2", "POLICY:BASE:USDC", "a".repeat(64), "SECURITY")

        assertThatThrownBy {
            insertDecision("req-self", "123456", "BCM_SECURITY_APPROVER", "a".repeat(64))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `hard ceiling을 통과하지 못한 정책은 정족수가 있어도 binding할 수 없다`() {
        insertPolicy("policy-over", "POLICY:BASE:USDC", 2, ceilingPassed = false)
        insertBinding("POLICY:BASE:USDC")
        insertRequest("req-over", "policy-over", "POLICY:BASE:USDC", "b".repeat(64), "GENERAL")
        insertDecision("req-over", "222222", "BCM_APPROVER", "b".repeat(64))
        insertIntent("req-over", "activate-over", "c".repeat(64))

        assertThatThrownBy {
            activatePolicy("POLICY:BASE:USDC", "policy-over", "req-over", "b".repeat(64))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `보안 변경은 두 승인자 중 보안 승인자가 없으면 binding할 수 없다`() {
        insertPolicy("policy-secure", "POLICY:BASE:USDC", 2, ceilingPassed = true)
        insertBinding("POLICY:BASE:USDC")
        insertRequest("req-secure", "policy-secure", "POLICY:BASE:USDC", "d".repeat(64), "SECURITY")
        insertDecision("req-secure", "222222", "BCM_APPROVER", "d".repeat(64))
        insertDecision("req-secure", "333333", "BCM_APPROVER", "d".repeat(64))
        insertIntent("req-secure", "activate-secure", "e".repeat(64))

        assertThatThrownBy {
            activatePolicy("POLICY:BASE:USDC", "policy-secure", "req-secure", "d".repeat(64))
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `일반 변경은 독립 승인과 intent가 있으면 revision을 한 번만 올려 활성화한다`() {
        insertPolicy("policy-normal", "POLICY:BASE:USDC", 2, ceilingPassed = true)
        insertBinding("POLICY:BASE:USDC")
        insertRequest("req-normal", "policy-normal", "POLICY:BASE:USDC", "f".repeat(64), "GENERAL")
        insertDecision("req-normal", "222222", "BCM_APPROVER", "f".repeat(64))
        insertIntent("req-normal", "activate-normal", "1".repeat(64))

        val changed = activatePolicy("POLICY:BASE:USDC", "policy-normal", "req-normal", "f".repeat(64))
        val binding =
            jdbc.queryForMap(
                "SELECT actv_plcy_vrsn_id, bind_rvsn, last_req_id FROM bcm_plcy_bind_m WHERE plcy_scope_id = ?",
                "POLICY:BASE:USDC",
            )

        assertThat(changed).isEqualTo(1)
        assertThat(binding["actv_plcy_vrsn_id"]).isEqualTo("policy-normal")
        assertThat(binding["bind_rvsn"]).isEqualTo(1L)
        assertThat(binding["last_req_id"]).isEqualTo("req-normal")
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `동시에 같은 정책을 활성화해도 binding revision은 한 번만 증가한다`() {
        val scope = "POLICY:BASE:USDC:RACE"
        insertPolicy("policy-race", scope, 2, ceilingPassed = true)
        insertBinding(scope)
        insertRequest("req-race", "policy-race", scope, "2".repeat(64), "GENERAL")
        insertDecision("req-race", "222222", "BCM_APPROVER", "2".repeat(64))
        insertIntent("req-race", "activate-race", "3".repeat(64))

        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val transaction = TransactionTemplate(transactionManager)

        try {
            val attempts =
                (1..2).map {
                    executor.submit<Int> {
                        transaction.execute {
                            ready.countDown()
                            check(start.await(5, TimeUnit.SECONDS))
                            activatePolicy(scope, "policy-race", "req-race", "2".repeat(64))
                        }
                    }
                }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue()
            start.countDown()

            assertThat(attempts.map { it.get(5, TimeUnit.SECONDS) }).containsExactlyInAnyOrder(0, 1)
            assertThat(
                jdbc.queryForObject(
                    "SELECT bind_rvsn FROM bcm_plcy_bind_m WHERE plcy_scope_id = ?",
                    Long::class.java,
                    scope,
                ),
            ).isEqualTo(1L)
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    private fun insertPolicy(
        id: String,
        scope: String,
        version: Int,
        ceilingPassed: Boolean,
    ) {
        jdbc.update(
            """
            INSERT INTO bcm_plcy_vrsn_l
              (plcy_vrsn_id, plcy_scope_id, vrsn_no, plcy_schm_vrsn, plcy_payload, plcy_hash,
               ceiling_snps, ceiling_hash, ceiling_pass_yn, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, ?, 'v1', CAST('{}' AS jsonb), ?, CAST('{}' AS jsonb), ?, ?, '20260817120000',
                    '123456', '0001', '123456', '0001')
            """.trimIndent(),
            id,
            scope,
            version,
            hash(id),
            hash("ceiling-$id"),
            if (ceilingPassed) "Y" else "N",
        )
    }

    private fun insertBinding(scope: String) {
        jdbc.update(
            """
            INSERT INTO bcm_plcy_bind_m
              (plcy_scope_id, bind_rvsn, bind_snps_hash, reg_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, 0, ?, '20260817120000', '20260817120000', '123456', '0001', '123456', '0001')
            """.trimIndent(),
            scope,
            "0".repeat(64),
        )
    }

    private fun insertRequest(
        requestId: String,
        policyId: String,
        scope: String,
        snapshotHash: String,
        risk: String,
    ) {
        jdbc.update(
            """
            INSERT INTO bcm_chng_req_l
              (req_id, tgt_dvcd, scope_id, aft_plcy_vrsn_id, risk_dvcd, base_bind_rvsn,
               tgt_snps_hash, diff_payload, diff_hash, impact_payload, impact_hash,
               req_rsn, work_tckt, idmp_key, req_role_dvcd, req_dttm, expr_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, 'POLICY', ?, ?, ?, 0, ?, CAST('{}' AS jsonb), ?, CAST('{}' AS jsonb), ?,
                    '정책 변경', 'OPS-1', ?, 'BCM_OPERATOR', '20260817120000', '20991231235959',
                    '123456', '0001', '123456', '0001')
            """.trimIndent(),
            requestId,
            scope,
            policyId,
            risk,
            snapshotHash,
            hash("diff-$requestId"),
            hash("impact-$requestId"),
            "idmp-$requestId",
        )
    }

    private fun insertDecision(
        requestId: String,
        employeeNo: String,
        role: String,
        snapshotHash: String,
    ) {
        jdbc.update(
            """
            INSERT INTO bcm_chng_dcsn_l
              (req_id, aprv_empno, aprv_brcd, aprv_role_dvcd, dcsn_dvcd, dcsn_snps_hash,
               dcsn_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, '0002', ?, 'APPROVE', ?, '20260817121000', ?, '0002', ?, '0002')
            """.trimIndent(),
            requestId,
            employeeNo,
            role,
            snapshotHash,
            employeeNo,
            employeeNo,
        )
    }

    private fun insertIntent(
        requestId: String,
        correlationId: String,
        requestHash: String,
    ) {
        jdbc.update(
            """
            INSERT INTO bcm_adm_actn_l
              (actn_id, corr_id, req_id, actn_dvcd, actn_stcd, try_seq, idmp_key,
               req_hash, exp_state, exp_state_hash, occr_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, ?, 'ACTIVATE', 'INTENT', 1, ?, ?, CAST('{}' AS jsonb), ?,
                    '20260817122000', '444444', '0004', '444444', '0004')
            """.trimIndent(),
            "actn-$requestId",
            correlationId,
            requestId,
            "idmp-$correlationId",
            requestHash,
            hash("expected-$requestId"),
        )
    }

    private fun activatePolicy(
        scope: String,
        policyId: String,
        requestId: String,
        snapshotHash: String,
    ): Int =
        jdbc.update(
            """
            UPDATE bcm_plcy_bind_m
               SET actv_plcy_vrsn_id = ?, bind_rvsn = bind_rvsn + 1, last_req_id = ?,
                   bind_snps_hash = ?, last_chng_dttm = '20260817122000',
                   last_chng_empno = '444444', last_chng_brcd = '0004'
             WHERE plcy_scope_id = ? AND bind_rvsn = 0
            """.trimIndent(),
            policyId,
            requestId,
            snapshotHash,
            scope,
        )

    private fun hash(seed: String): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(seed.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
