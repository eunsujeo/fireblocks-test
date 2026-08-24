package com.whatto.bcm.infra.persistence.admin

import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate

@DataJdbcTest
class BandSLedgerConstraintTest : PersistenceTestSupport() {
    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `snapshot 원장은 update를 거절한다`() {
        insertReadyFixture("immutable", complete = true)

        assertThatThrownBy {
            jdbc.update("UPDATE bcm_bnds_snps_l SET input_cmplt_yn = 'N' WHERE snps_id = 'snapshot-immutable'")
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `proposal 원장은 delete를 거절한다`() {
        insertReadyFixture("delete", complete = true)

        assertThatThrownBy {
            jdbc.update("DELETE FROM bcm_bnds_prop_l WHERE prop_id = 'proposal-delete'")
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `누락 입력 snapshot은 승인과 intent가 있어도 execution을 만들 수 없다`() {
        insertReadyFixture("blocked", complete = false)

        assertThatThrownBy {
            insertExecution("blocked")
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `승인된 proposal은 모든 item을 예약하고 event 순서를 건너뛸 수 없다`() {
        insertReadyFixture("valid", complete = true)
        assertThat(insertExecution("valid")).isEqualTo(1)
        insertExecutionItem("valid")
        insertEvent("valid", 1, "RESERVED", null, null)

        assertThatThrownBy {
            insertEvent("valid", 3, "SUBMITTED", null, "vendor-1")
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    private fun insertReadyFixture(
        suffix: String,
        complete: Boolean,
    ) {
        val policyId = "policy-$suffix"
        val scope = "BAND_S:$suffix"
        val snapshotId = "snapshot-$suffix"
        val proposalId = "proposal-$suffix"
        val requestId = "request-$suffix"
        jdbc.update(
            """
            INSERT INTO bcm_plcy_vrsn_l
              (plcy_vrsn_id, plcy_scope_id, vrsn_no, plcy_schm_vrsn, plcy_payload, plcy_hash,
               ceiling_snps, ceiling_hash, ceiling_pass_yn, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, 1, 'v1', '{}'::jsonb, ?, '{}'::jsonb, ?, 'Y', '20260817120000',
                    '123456', '0001', '123456', '0001')
            """.trimIndent(),
            policyId,
            scope,
            hash("policy-$suffix"),
            hash("ceiling-$suffix"),
        )
        jdbc.update(
            """
            INSERT INTO bcm_plcy_bind_m
              (plcy_scope_id, actv_plcy_vrsn_id, bind_rvsn, bind_snps_hash, reg_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, 1, ?, '20260817120000', '20260817120000', '123456', '0001', '123456', '0001')
            """.trimIndent(),
            scope,
            policyId,
            hash("binding-$suffix"),
        )
        jdbc.update(
            """
            INSERT INTO bcm_bnds_snps_l
              (snps_id, src_req_id, plcy_vrsn_id, snps_hash, input_hash, base_dttm, expr_dttm,
               input_cmplt_yn, total_ast_krw_amt, obs_hot_krw_amt, obs_cold_krw_amt, efct_hot_krw_amt,
               hot_ratio, low_ratio, trgt_ratio, up_ratio, input_payload, issue_payload, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, ?, ?, ?, '20260817120000', '20991231235959', ?, 1000, 250, 750, 240,
                    24, 8, 12.5, 18, '{}'::jsonb, '[]'::jsonb, '20260817120000',
                    '123456', '0001', '123456', '0001')
            """.trimIndent(),
            snapshotId,
            "daw-request-$suffix",
            policyId,
            hash("snapshot-$suffix"),
            hash("input-$suffix"),
            if (complete) "Y" else "N",
        )
        jdbc.update(
            """
            INSERT INTO bcm_bnds_prop_l
              (prop_id, src_prop_id, snps_id, plcy_vrsn_id, drct_dvcd, prop_hash, input_hash,
               item_cnt, total_krw_amt, aft_hot_ratio, exec_able_yn, prop_payload, block_payload, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, ?, ?, 'HOT_TO_COLD', ?, ?, 1, 140000, 12.5, 'Y', '{}'::jsonb, '[]'::jsonb,
                    '20260817120000', '123456', '0001', '123456', '0001')
            """.trimIndent(),
            proposalId,
            "daw-proposal-$suffix",
            snapshotId,
            policyId,
            hash("proposal-$suffix"),
            hash("input-$suffix"),
        )
        jdbc.update(
            """
            INSERT INTO bcm_bnds_prop_item_l
              (prop_id, item_seq, leg_dvcd, ntwk_cd, tkn_smbl, src_vlt_id, dst_addr,
               amt, krw_amt, exp_fee_amt, item_hash, exec_able_yn,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, 1, 'EXTERNAL_COLD', 'BASE', 'USDC', 'omnibus-base', 'cold-base-usdc',
                    100, 140000, 0.1, ?, 'Y', '123456', '0001', '123456', '0001')
            """.trimIndent(),
            proposalId,
            hash("item-$suffix"),
        )
        jdbc.update(
            """
            INSERT INTO bcm_chng_req_l
              (req_id, tgt_dvcd, scope_id, aft_bnds_prop_id, risk_dvcd, base_bind_rvsn,
               tgt_snps_hash, diff_payload, diff_hash, impact_payload, impact_hash,
               req_rsn, work_tckt, idmp_key, req_role_dvcd, req_dttm, expr_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, 'BAND_S', ?, ?, 'FUND', 0, ?, '{}'::jsonb, ?, '{}'::jsonb, ?,
                    '밴드S 이동', 'OPS-1', ?, 'BCM_OPERATOR', '20260817120000', '20991231235959',
                    '123456', '0001', '123456', '0001')
            """.trimIndent(),
            requestId,
            scope,
            proposalId,
            hash("proposal-$suffix"),
            hash("diff-$suffix"),
            hash("impact-$suffix"),
            "request-idmp-$suffix",
        )
        jdbc.update(
            """
            INSERT INTO bcm_chng_dcsn_l
              (req_id, aprv_empno, aprv_brcd, aprv_role_dvcd, dcsn_dvcd, dcsn_snps_hash,
               dcsn_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, '222222', '0002', 'BCM_APPROVER', 'APPROVE', ?, '20260817121000',
                    '222222', '0002', '222222', '0002')
            """.trimIndent(),
            requestId,
            hash("proposal-$suffix"),
        )
        jdbc.update(
            """
            INSERT INTO bcm_adm_actn_l
              (actn_id, corr_id, req_id, actn_dvcd, actn_stcd, try_seq, idmp_key,
               req_hash, exp_state, exp_state_hash, occr_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, ?, 'EXECUTE', 'INTENT', 1, ?, ?, '{}'::jsonb, ?, '20260817122000',
                    '333333', '0003', '333333', '0003')
            """.trimIndent(),
            "action-$suffix",
            "correlation-$suffix",
            requestId,
            "execute-idmp-$suffix",
            hash("request-$suffix"),
            hash("expected-$suffix"),
        )
    }

    private fun insertExecution(suffix: String): Int =
        jdbc.update(
            """
            INSERT INTO bcm_bnds_exec_l
              (exec_id, req_id, prop_id, snps_id, plcy_vrsn_id, prop_hash, input_hash,
               idmp_key, exec_hash, rsv_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '20260817122000',
                    '333333', '0003', '333333', '0003')
            """.trimIndent(),
            "execution-$suffix",
            "request-$suffix",
            "proposal-$suffix",
            "snapshot-$suffix",
            "policy-$suffix",
            hash("proposal-$suffix"),
            hash("input-$suffix"),
            "execute-idmp-$suffix",
            hash("execution-$suffix"),
        )

    private fun insertExecutionItem(suffix: String) {
        jdbc.update(
            """
            INSERT INTO bcm_bnds_exec_item_key
              (exec_id, item_seq, prop_id, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, 1, ?, '333333', '0003', '333333', '0003')
            """.trimIndent(),
            "execution-$suffix",
            "proposal-$suffix",
        )
    }

    private fun insertEvent(
        suffix: String,
        eventSequence: Int,
        status: String,
        externalTransactionId: String?,
        vendorTransactionId: String?,
    ) {
        jdbc.update(
            """
            INSERT INTO bcm_bnds_exec_evt_l
              (exec_id, item_seq, evt_seq, exec_stcd, ext_tx_id, vndr_tx_id,
               obs_payload, obs_hash, occr_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, 1, ?, ?, ?, ?, '{}'::jsonb, ?, '20260817122000',
                    '333333', '0003', '333333', '0003')
            """.trimIndent(),
            "execution-$suffix",
            eventSequence,
            status,
            externalTransactionId,
            vendorTransactionId,
            hash("event-$suffix-$eventSequence"),
        )
    }

    private fun hash(seed: String): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(seed.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
