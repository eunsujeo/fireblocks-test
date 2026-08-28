package com.whatto.bcm.infra.persistence.admin

import com.whatto.bcm.domain.admin.SweepRequestInvestigationRepository
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

@DataJdbcTest
@Import(AdminSweepRequestInvestigationJdbcAdapter::class)
class AdminSweepRequestInvestigationPersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var investigations: SweepRequestInvestigationRepository

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun seed() {
        jdbc.update(
            """
            INSERT INTO bcm_acnt_m
              (acnt_id, acnt_typ_dvcd, ref, vndr_vlt_id, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('account-1', 'CU', 'customer-1', 'vault-1', '20260828000000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
        insertOutbox(SOURCE_EVENT, "deposit-events", "tx-source", """{"status":"FINALIZED"}""", "S", "20260828000100")
        insertOutbox(
            RESULT_EVENT,
            "sweep-events",
            "tx-sweep",
            """{"sweepRequestId":"request-1","sweepItemId":"item-1","chainStatus":"FINALIZED","itemOutcome":"FAILED","failureCode":"LEG_FAILED"}""",
            "S",
            "20260828000500",
        )
        jdbc.update(
            """
            INSERT INTO bcm_evnt_cmpl_l
              (evnt_id, cnsmr_dvcd, cmpl_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, 'DAW_CORE', '20260828000200', 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            SOURCE_EVENT,
        )
        jdbc.update(
            """
            INSERT INTO bcm_swp_req_l
              (swp_req_id, ext_swp_req_id, req_hash, ntwk_cd, tkn_smbl, swp_req_stcd,
               item_cnt, req_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('request-1', 'daw-request-1', ?, 'BASE', 'USDC', 'PARTIAL',
                    1, '20260828000300', '123456', '0001', 'SYSTEM', '9999')
            """.trimIndent(),
            "a".repeat(64),
        )
        jdbc.update(
            """
            INSERT INTO bcm_swp_req_item_l
              (swp_req_item_id, swp_req_id, item_seq, acnt_id, swp_req_item_stcd, last_fail_cd,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('item-1', 'request-1', 1, 'account-1', 'PENDING', 'LEG_FAILED',
                    '123456', '0001', 'SYSTEM', '9999')
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO bcm_swp_req_src_l
              (evnt_id, swp_req_item_id, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, 'item-1', '123456', '0001', 'SYSTEM', '9999')
            """.trimIndent(),
            SOURCE_EVENT,
        )
    }

    @Test
    fun `요청 item source event 결과 event와 DAW completion을 한 세로줄로 조회한다`() {
        val byExternalRequest = investigations.findByIdentifier("daw-request-1")
        val byResultEvent = investigations.findByIdentifier(RESULT_EVENT)

        assertThat(byResultEvent).isEqualTo(byExternalRequest)
        assertThat(byExternalRequest?.summary?.requesterEmployeeNo).isEqualTo("123456")
        assertThat(byExternalRequest?.summary?.nextAction).isEqualTo("RETRY_PENDING_ITEMS")
        assertThat(
            byExternalRequest
                ?.items
                ?.single()
                ?.sourceEvents
                ?.single()
                ?.dawCompletedAt,
        ).isNotNull()
        assertThat(
            byExternalRequest
                ?.items
                ?.single()
                ?.resultEvents
                ?.single()
                ?.itemOutcome,
        ).isEqualTo("FAILED")
        assertThat(
            byExternalRequest
                ?.items
                ?.single()
                ?.resultEvents
                ?.single()
                ?.dawCompletedAt,
        ).isNull()
    }

    @Test
    fun `운영 요약은 요청 적체와 발행 후 미완료 event를 분리한다`() {
        val result = investigations.operationsOverview()

        assertThat(result.partialRequestCount).isEqualTo(1)
        assertThat(result.pendingItemCount).isEqualTo(1)
        assertThat(result.pendingEventCount).isZero()
        assertThat(result.awaitingDawCompletionCount).isEqualTo(1)
        assertThat(result.oldestAwaitingDawCompletionAt).isNotNull()
    }

    private fun insertOutbox(
        eventId: String,
        topic: String,
        transactionId: String,
        payload: String,
        status: String,
        publishedAt: String,
    ) {
        jdbc.update(
            """
            INSERT INTO bcm_outbox_l
              (evnt_id, evnt_dt, vndr_tx_id, agg_typ_dvcd, evt_typ_dvcd, topic, payload,
               evnt_stcd, rtry_cnt, max_rtry_cnt, pub_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, '20260828', ?, 'TX', 'TXCF', ?, CAST(? AS jsonb), ?, 0, 5, ?,
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            eventId,
            transactionId,
            topic,
            payload,
            status,
            publishedAt,
        )
    }

    companion object {
        private const val SOURCE_EVENT = "0198b8ad-2e00-7000-8000-000000000101"
        private const val RESULT_EVENT = "0198b8ad-2e00-7000-8000-000000000102"
    }
}
