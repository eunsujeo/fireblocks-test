package com.whatto.bcm.infra.persistence.admin

import com.whatto.bcm.domain.admin.TransactionInvestigationRepository
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

@DataJdbcTest
@Import(AdminTransactionInvestigationJdbcAdapter::class)
class AdminTransactionInvestigationPersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var investigations: TransactionInvestigationRepository

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @AfterEach
    fun cleanUp() {
        listOf(
            "bcm_swp_item_l",
            "bcm_swp_exec_l",
            "bcm_swp_auth_m",
            "bcm_outbox_l",
            "bcm_whk_l",
            "bcm_boost_l",
            "bcm_tx_l",
            "bcm_sbmt_l",
            "bcm_fee_qt_l",
        ).forEach { table -> jdbc.update("DELETE FROM $table") }
    }

    @Test
    fun `externalTxId와 대체 txId는 제출 웹훅 발행 대사 boost fee quote를 같은 root로 연결한다`() {
        insertSubmission("wd-1", "WITHDRAWAL", "tx-root")
        insertTransaction("tx-root", "tx-new", "wd-1", "CONFIRMED")
        insertBoost()
        insertWebhook("noti-root", "tx-root", "20260817120100")
        insertWebhook("noti-new", "tx-new", "20260817120600")
        insertOutbox("0198b8ad-2e00-7000-8000-000000000001", "TXCK", "CONFIRMED", "20260817120200")
        insertOutbox("0198b8ad-2e00-7000-8000-000000000002", "TXCF", "FINALIZED", "20260817120700")
        insertFee("20260817115900", "MEDIUM", "2.1")
        insertFee("20260817120400", "HIGH", "3.1")

        val byExternal = investigations.findByIdentifier("wd-1")
        val byReplacement = investigations.findByIdentifier("tx-new")

        assertThat(byExternal).isNotNull
        assertThat(byReplacement).isEqualTo(byExternal)
        assertThat(byExternal!!.summary.rootTransactionId).isEqualTo("tx-root")
        assertThat(byExternal.summary.activeTransactionId).isEqualTo("tx-new")
        assertThat(byExternal.summary.transactionType).isEqualTo("WITHDRAWAL")
        assertThat(byExternal.timeline.map { it.source })
            .contains("SUBMISSION", "WEBHOOK", "OUTBOX", "RECONCILIATION", "BOOST")
        assertThat(byExternal.timeline.mapNotNull { it.identifier })
            .contains("noti-root", "noti-new")
            .doesNotContain("raw-payload", "signature")
        assertThat(byExternal.boosts.single().newTransactionId).isEqualTo("tx-new")
        assertThat(byExternal.feeQuotes.map { it.context }).containsExactly("SUBMISSION", "BOOST_1")
        assertThat(byExternal.feeQuotes.map { it.gasPrice }).containsExactly("2.1".toBigDecimal(), "3.1".toBigDecimal())
        assertThat(byExternal.truncatedSources).isEmpty()
    }

    @Test
    fun `sweep 실행 식별자는 항목 1대N과 원천 vault allowance를 함께 연결한다`() {
        insertSubmission("swp-1", "SWEEP_BATCH", "tx-sweep", "swx-1")
        insertTransaction("tx-sweep", "tx-sweep", "swp-1", "FINALIZED")
        insertSweepExecution()
        insertSweepItem(1, "acct-1", "0xsource1", "10", "10", "SUCCEEDED")
        insertSweepItem(2, "acct-2", "0xsource2", "20", null, "RETRY")
        insertAllowance("acct-1", "100", "90", "ACTIVE")
        insertAllowance("acct-2", "100", "20", "ACTIVE")

        val result = investigations.findByIdentifier("swx-1")

        assertThat(result).isNotNull
        assertThat(result!!.sweepExecution?.executionId).isEqualTo("swx-1")
        assertThat(result.sweepExecution?.items).hasSize(2)
        assertThat(result.sweepExecution?.items?.map { it.status }).containsExactly("SUCCEEDED", "RETRY")
        assertThat(result.allowances.map { it.accountId }).containsExactly("acct-1", "acct-2")
        assertThat(result.allowances.map { it.observedAllowance }).containsExactly("90".toBigDecimal(), "20".toBigDecimal())
    }

    @Test
    fun `상세 상한을 넘은 sweep 항목은 100건만 반환하고 잘림 근거를 표시한다`() {
        insertSubmission("swp-1", "SWEEP_BATCH", "tx-sweep", "swx-1")
        insertTransaction("tx-sweep", "tx-sweep", "swp-1", "FINALIZED")
        insertSweepExecution()
        (1..101).forEach { sequence ->
            insertSweepItem(sequence, "acct-$sequence", "0xsource$sequence", "1", null, "RETRY")
        }

        val result = investigations.findByIdentifier("swx-1")

        assertThat(result?.sweepExecution?.items).hasSize(100)
        assertThat(result?.truncatedSources).containsExactly("SWEEP_ITEM")
    }

    private fun insertSubmission(
        externalTransactionId: String,
        type: String,
        vendorTransactionId: String,
        sweepExecutionId: String? = null,
    ) {
        jdbc.update(
            """
            INSERT INTO bcm_sbmt_l
              (ext_tx_id, req_hash, hash_vrsn, sbmt_stcd, tx_dvcd, vndr_tx_id, swp_exec_id,
               snd_acnt_id, rcv_dvcd, rcv_vl, ntwk_cd, tkn_smbl, trsf_amt, call_data,
               req_dttm, rsp_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (?, ?, ?, 'SUBMITTED', ?, ?, ?, 'acct-1', 'ADDRESS', '0xdestination', 'BASE', 'USDC', 30, ?,
               '20260817120000', '20260817120001', 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            externalTransactionId,
            "a".repeat(64),
            if (type.startsWith("SWEEP")) "cc-v1" else "v1",
            type,
            vendorTransactionId,
            sweepExecutionId,
            if (type.startsWith("SWEEP")) "0x1234" else null,
        )
    }

    private fun insertTransaction(
        rootTransactionId: String,
        activeTransactionId: String,
        externalTransactionId: String,
        status: String,
    ) {
        jdbc.update(
            """
            INSERT INTO bcm_tx_l
              (vndr_tx_id, actv_tx_id, ext_tx_id, acnt_id, ntwk_cd, tkn_smbl, tx_hash,
               last_pub_stcd, cnfm_cnt, vndr_sub_stcd, vndr_ntwk_stcd, vndr_crt_dttm,
               rcnc_chck_dttm, rcnc_chck_cnt, frst_dtct_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (?, ?, ?, 'acct-1', 'BASE', 'USDC', '0xactive', ?, 0, 'PENDING_BLOCKCHAIN_CONFIRMATIONS',
               'CONFIRMING', '20260817120000', '20260817120800', 2, '20260817120010', '20260817120700',
               'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            rootTransactionId,
            activeTransactionId,
            externalTransactionId,
            status,
        )
    }

    private fun insertBoost() {
        jdbc.update(
            """
            INSERT INTO bcm_boost_l
              (orig_tx_id, try_seq, ext_tx_id, bst_stcd, rplc_tx_id, rplc_tx_hash, fee_lvl,
               gasless_yn, new_tx_id, req_dttm, rsp_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              ('tx-root', 1, 'bst-1', 'SUBMITTED', 'tx-root', '0xold', 'HIGH', 'Y', 'tx-new',
               '20260817120500', '20260817120501', 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
    }

    private fun insertWebhook(
        notificationId: String,
        vendorTransactionId: String,
        receivedAt: String,
    ) {
        jdbc.update(
            """
            INSERT INTO bcm_whk_l
              (noti_id, evnt_typ, vndr_tx_id, payload, payload_hash, sign_vl, rcv_dttm,
               prcs_stcd, rtry_cnt, prcs_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, 'transaction.status.updated', ?, 'raw-payload', ?, 'signature', ?, 'S', 0, ?,
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            notificationId,
            vendorTransactionId,
            "b".repeat(64),
            receivedAt,
            receivedAt,
        )
    }

    private fun insertOutbox(
        eventId: String,
        eventType: String,
        status: String,
        publishedAt: String,
    ) {
        jdbc.update(
            """
            INSERT INTO bcm_outbox_l
              (evnt_id, evnt_dt, vndr_tx_id, agg_typ_dvcd, evt_typ_dvcd, topic, payload,
               evnt_stcd, rtry_cnt, max_rtry_cnt, pub_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, '20260817', 'tx-root', 'TX', ?, 'withdrawal-events', CAST(? AS jsonb),
                    'S', 0, 3, ?, 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            eventId,
            eventType,
            """{"status":"$status"}""",
            publishedAt,
        )
    }

    private fun insertFee(
        observedAt: String,
        level: String,
        gasPrice: String,
    ) {
        jdbc.update(
            """
            INSERT INTO bcm_fee_qt_l
              (ntwk_cd, tkn_smbl, obs_dttm, fee_lvl, vndr_ast_id, gas_price,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('BASE', 'USDC', ?, ?, 'USDC_BASE', ?, 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            observedAt,
            level,
            gasPrice.toBigDecimal(),
        )
    }

    private fun insertSweepExecution() {
        val snapshot = insertActiveSweepAdminSnapshot(jdbc, "BASE", "USDC", "0xsweeper")
        jdbc.update(
            """
            INSERT INTO bcm_swp_exec_l
              (swp_exec_id, ext_tx_id, req_hash, ntwk_cd, tkn_smbl, opr_acnt_id, swp_ctrt_addr,
               plcy_vrsn_id, plcy_snps_hash, ctrt_vrsn_id, ctrt_evdc_id,
               swp_exec_stcd, item_cnt, req_tot_amt, actl_tot_amt, gasless_yn, vndr_tx_id, tx_hash,
               req_dttm, fnsh_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              ('swx-1', 'swp-1', ?, 'BASE', 'USDC', 'operator-1', '0xsweeper', ?, ?, ?, ?, 'PARTIAL', 2, 30, 10,
               'Y', 'tx-sweep', '0xsweep', '20260817120000', '20260817121000',
               'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            "c".repeat(64),
            snapshot.policyVersionId,
            snapshot.policySnapshotHash,
            snapshot.contractVersionId,
            snapshot.contractEvidenceId,
        )
    }

    private fun insertSweepItem(
        sequence: Int,
        accountId: String,
        sourceAddress: String,
        requestedAmount: String,
        actualAmount: String?,
        status: String,
    ) {
        jdbc.update(
            """
            INSERT INTO bcm_swp_item_l
              (swp_exec_id, item_seq, acnt_id, src_addr, req_amt, actl_amt, swp_item_stcd,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('swx-1', ?, ?, ?, ?, ?, ?, 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            sequence,
            accountId,
            sourceAddress,
            requestedAmount.toBigDecimal(),
            actualAmount?.toBigDecimal(),
            status,
        )
    }

    private fun insertAllowance(
        accountId: String,
        cap: String,
        observed: String,
        status: String,
    ) {
        jdbc.update(
            """
            INSERT INTO bcm_swp_auth_m
              (acnt_id, ntwk_cd, tkn_smbl, swp_ctrt_addr, alwnc_cap, obs_alwnc, auth_stcd,
               last_chck_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, 'BASE', 'USDC', '0xsweeper', ?, ?, ?, '20260817115900',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            accountId,
            cap.toBigDecimal(),
            observed.toBigDecimal(),
            status,
        )
    }
}
