package com.whatto.bcm.app.api.admin

import com.whatto.bcm.app.api.support.IntegrationTestSupport
import org.junit.jupiter.api.AfterEach
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

@SpringBootTest
@AutoConfigureMockMvc
class AdminTransactionInvestigationIntegrationTest : IntegrationTestSupport() {
    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun insertEvidence() {
        jdbc.update(
            """
            INSERT INTO bcm_sbmt_l
              (ext_tx_id, req_hash, hash_vrsn, sbmt_stcd, tx_dvcd, vndr_tx_id,
               snd_acnt_id, rcv_dvcd, rcv_vl, ntwk_cd, tkn_smbl, trsf_amt,
               req_dttm, rsp_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              ('admin-e2e-ext', ?, 'v1', 'SUBMITTED', 'WITHDRAWAL', 'admin-e2e-root',
               'admin-e2e-account', 'ADDRESS', '0xdestination', 'BASE', 'USDC', 30,
               '20260817120000', '20260817120001', 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            "a".repeat(64),
        )
        jdbc.update(
            """
            INSERT INTO bcm_tx_l
              (vndr_tx_id, actv_tx_id, ext_tx_id, acnt_id, ntwk_cd, tkn_smbl, tx_hash,
               last_pub_stcd, cnfm_cnt, vndr_sub_stcd, vndr_ntwk_stcd, vndr_crt_dttm,
               rcnc_chck_dttm, rcnc_chck_cnt, frst_dtct_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              ('admin-e2e-root', 'admin-e2e-active', 'admin-e2e-ext', 'admin-e2e-account',
               'BASE', 'USDC', '0xadmin', 'CONFIRMED', 4, 'PENDING_BLOCKCHAIN_CONFIRMATIONS',
               'CONFIRMING', '20260817120000', '20260817120800', 2, '20260817120010',
               '20260817120700', 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO bcm_whk_l
              (noti_id, evnt_typ, vndr_tx_id, payload, payload_hash, sign_vl, rcv_dttm,
               prcs_stcd, rtry_cnt, prcs_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              ('admin-e2e-noti', 'transaction.status.updated', 'admin-e2e-active',
               'must-not-leak', ?, 'must-not-leak', '20260817120600', 'S', 0, '20260817120601',
               'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            "b".repeat(64),
        )
        jdbc.update(
            """
            INSERT INTO bcm_boost_l
              (orig_tx_id, try_seq, ext_tx_id, bst_stcd, rplc_tx_id, rplc_tx_hash,
               fee_lvl, gasless_yn, new_tx_id, req_dttm, rsp_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              ('admin-e2e-root', 1, 'admin-e2e-boost', 'SUBMITTED', 'admin-e2e-root', '0xold',
               'HIGH', 'Y', 'admin-e2e-active', '20260817120500', '20260817120501',
               'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
    }

    @AfterEach
    fun cleanUp() {
        jdbc.update("DELETE FROM bcm_whk_l WHERE noti_id = 'admin-e2e-noti'")
        jdbc.update("DELETE FROM bcm_boost_l WHERE orig_tx_id = 'admin-e2e-root'")
        jdbc.update("DELETE FROM bcm_tx_l WHERE vndr_tx_id = 'admin-e2e-root'")
        jdbc.update("DELETE FROM bcm_sbmt_l WHERE ext_tx_id = 'admin-e2e-ext'")
    }

    @Test
    fun `PostgreSQL 증거를 활성 거래 ID 하나로 API 조사 응답까지 재현한다`() {
        mockMvc
            .perform(get("/admin/transaction-investigations/admin-e2e-active"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.summary.rootTransactionId").value("admin-e2e-root"))
            .andExpect(jsonPath("$.data.summary.activeTransactionId").value("admin-e2e-active"))
            .andExpect(jsonPath("$.data.timeline[?(@.source == 'WEBHOOK')].identifier").value("admin-e2e-noti"))
            .andExpect(jsonPath("$.data.boosts[0].newTransactionId").value("admin-e2e-active"))
            .andExpect(jsonPath("$.data.rawPayload").doesNotExist())
            .andExpect(jsonPath("$.data.signature").doesNotExist())
    }
}
