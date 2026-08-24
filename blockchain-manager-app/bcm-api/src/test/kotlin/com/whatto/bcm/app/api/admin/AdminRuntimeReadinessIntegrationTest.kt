package com.whatto.bcm.app.api.admin

import com.whatto.bcm.testsupport.integration.IntegrationTestSupport
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
class AdminRuntimeReadinessIntegrationTest : IntegrationTestSupport() {
    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var mockMvc: MockMvc

    @BeforeEach
    fun insertRuntimeEvidence() {
        jdbc.update(
            """
            INSERT INTO bcm_whk_l
              (noti_id, evnt_typ, vndr_tx_id, payload, payload_hash, sign_vl, rcv_dttm,
               prcs_stcd, rtry_cnt, err_msg, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              ('readiness-pending', 'transaction.status.updated', 'readiness-tx-1', '{}', ?, 'masked',
               '20260821100000', 'P', 0, NULL, 'SYSTEM', '9999', 'SYSTEM', '9999'),
              ('readiness-poisoned', 'transaction.status.updated', 'readiness-tx-2', '{}', ?, 'masked',
               '20260821100100', 'F', 3, 'safe-code', 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            "a".repeat(64),
            "b".repeat(64),
        )
        jdbc.update(
            """
            INSERT INTO bcm_outbox_l
              (evnt_id, evnt_dt, vndr_tx_id, agg_typ_dvcd, evt_typ_dvcd, topic, payload,
               evnt_stcd, rtry_cnt, max_rtry_cnt,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('0198c000-0000-7000-8000-000000000001', '20260821', 'readiness-tx-1', 'TX', 'TXCF',
                    'deposit-events', '{}'::jsonb, 'P', 0, 5, 'SYSTEM', '9999', 'SYSTEM', '9999'),
                   ('0198c000-0000-7000-8000-000000000002', '20260821', 'readiness-tx-2', 'TX', 'TXCF',
                    'deposit-events', '{}'::jsonb, 'F', 5, 5, 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
    }

    @AfterEach
    fun cleanUp() {
        jdbc.update("DELETE FROM bcm_outbox_l WHERE vndr_tx_id LIKE 'readiness-tx-%'")
        jdbc.update("DELETE FROM bcm_whk_l WHERE noti_id LIKE 'readiness-%'")
    }

    @Test
    fun `Webhook runtime은 원문 없이 마지막 수신과 적체만 반환한다`() {
        mockMvc
            .perform(get("/admin/runtime-readiness"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.webhook.state").value("POISONED"))
            .andExpect(jsonPath("$.data.webhook.lastReceivedAt").value("2026-08-21T10:01:00Z"))
            .andExpect(jsonPath("$.data.webhook.pendingInboxCount").value(1))
            .andExpect(jsonPath("$.data.webhook.poisonedInboxCount").value(1))
            .andExpect(jsonPath("$.data.webhook.pendingOutboxCount").value(1))
            .andExpect(jsonPath("$.data.webhook.poisonedOutboxCount").value(1))
            .andExpect(jsonPath("$.data.webhook.statusPath").value("/admin/emergency"))
            .andExpect(jsonPath("$.data.webhook.payload").doesNotExist())
            .andExpect(jsonPath("$.data.webhook.signature").doesNotExist())
            .andExpect(jsonPath("$.data.sweep.enabled").value(false))
            .andExpect(jsonPath("$.data.sweep.state").value("DISABLED"))
            .andExpect(jsonPath("$.data.sweep.disabledReasons").isNotEmpty)
    }
}
