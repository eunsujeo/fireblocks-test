package com.whatto.bcm.infra.persistence.migration

import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import com.whatto.bcm.infra.persistence.webhook.COMPLETED_WEBHOOK_PREDICATE
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

class AdminOperationalQueryPlanPersistenceTest : PersistenceTestSupport() {
    @Test
    fun `운영 Admin 식별자 검색과 적체 집계는 대용량 원장에서 전용 index를 사용한다`() {
        val schema = "admin_plan_${UUID.randomUUID().toString().replace("-", "")}"

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            try {
                connection.schema = schema
                migrations().forEach { applyMigration(connection, it) }
                val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
                seedRepresentativeLedgers(jdbc)
                analyze(jdbc)

                assertIndexUsed(
                    jdbc,
                    "idx_bcm_outbox_sweep_item_event",
                    """
                    SELECT evnt_id
                    FROM bcm_outbox_l
                    WHERE topic = 'sweep-events'
                      AND (
                        evnt_id = 'item-00010000'
                        OR payload ->> 'sweepItemId' = 'item-00010000'
                      )
                    ORDER BY payload ->> 'sweepItemId', evnt_id
                    LIMIT 101
                    """.trimIndent(),
                )
                assertIndexUsed(
                    jdbc,
                    "idx_bcm_swp_exec_tx_hash",
                    """
                    SELECT swp_exec_id
                    FROM bcm_swp_exec_l
                    WHERE swp_exec_id = '0xsweep00002000'
                       OR ext_tx_id = '0xsweep00002000'
                       OR vndr_tx_id = '0xsweep00002000'
                       OR tx_hash = '0xsweep00002000'
                    """.trimIndent(),
                )
                assertIndexUsed(
                    jdbc,
                    "idx_bcm_outbox_vendor_event",
                    """
                    SELECT evnt_id
                    FROM bcm_outbox_l
                    WHERE vndr_tx_id = 'vendor-00010000'
                    ORDER BY evnt_id
                    LIMIT 101
                    """.trimIndent(),
                )
                assertIndexUsed(
                    jdbc,
                    "idx_bcm_whk_vendor_time",
                    """
                    SELECT noti_id
                    FROM bcm_whk_l
                    WHERE vndr_tx_id = 'vendor-00010000'
                    ORDER BY rcv_dttm, noti_id
                    LIMIT 101
                    """.trimIndent(),
                )
                assertIndexUsed(
                    jdbc,
                    "idx_bcm_whk_completed_archive",
                    """
                    SELECT count(DISTINCT webhook.vndr_tx_id)
                    FROM bcm_whk_l webhook
                    JOIN bcm_tx_l transaction
                      ON transaction.actv_tx_id = webhook.vndr_tx_id
                     AND transaction.last_pub_stcd = 'FINALIZED'
                    WHERE $COMPLETED_WEBHOOK_PREDICATE
                      AND NOT EXISTS (
                        SELECT 1
                        FROM bcm_raw_tx_l archived
                        WHERE archived.vndr_tx_id = webhook.vndr_tx_id
                          AND archived.rcv_dttm >= webhook.rcv_dttm
                      )
                    """.trimIndent(),
                )
                assertIndexUsed(
                    jdbc,
                    "idx_bcm_sbmt_sweep_execution",
                    "SELECT ext_tx_id FROM bcm_sbmt_l WHERE swp_exec_id = 'execution-00010000'",
                )
                assertIndexUsed(
                    jdbc,
                    "idx_bcm_swp_target_attempt",
                    "SELECT count(*) FROM bcm_swp_trgt WHERE try_cnt >= 3",
                )
                assertIndexUsed(
                    jdbc,
                    "idx_bcm_outbox_sweep_completion_wait",
                    """
                    SELECT outbox.pub_dttm
                    FROM bcm_outbox_l outbox
                    WHERE outbox.topic = 'sweep-events'
                      AND outbox.evnt_stcd = 'S'
                      AND outbox.pub_dttm IS NOT NULL
                      AND NOT EXISTS (
                        SELECT 1
                        FROM bcm_evnt_cmpl_l completion
                        WHERE completion.evnt_id = outbox.evnt_id
                          AND completion.cnsmr_dvcd = 'DAW_CORE'
                      )
                    ORDER BY outbox.pub_dttm, outbox.evnt_id
                    LIMIT 1
                    """.trimIndent(),
                )
            } finally {
                connection.schema = "public"
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }

    private fun seedRepresentativeLedgers(jdbc: JdbcTemplate) {
        jdbc.execute(
            """
            INSERT INTO bcm_outbox_l
              (evnt_id, evnt_dt, vndr_tx_id, agg_typ_dvcd, evt_typ_dvcd, topic, payload,
               evnt_stcd, rtry_cnt, max_rtry_cnt, pub_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            SELECT
              substr(hash, 1, 8) || '-' || substr(hash, 9, 4) || '-' || substr(hash, 13, 4) || '-' ||
                substr(hash, 17, 4) || '-' || substr(hash, 21, 12),
              '20260831',
              'vendor-' || lpad(sequence::text, 8, '0'),
              'TX',
              'TXCF',
              'sweep-events',
              jsonb_build_object(
                'sweepRequestId', 'request-' || lpad(sequence::text, 8, '0'),
                'sweepItemId', 'item-' || lpad(sequence::text, 8, '0')
              ),
              CASE WHEN sequence % 2 = 0 THEN 'S' ELSE 'P' END,
              0,
              5,
              CASE WHEN sequence % 2 = 0 THEN '20260831010000' ELSE NULL END,
              'SYSTEM', '9999', 'SYSTEM', '9999'
            FROM (
              SELECT sequence, md5('admin-plan-event:' || sequence) AS hash
              FROM generate_series(1, 10000) sequence
            ) rows
            """.trimIndent(),
        )
        jdbc.execute(
            """
            INSERT INTO bcm_whk_l
              (noti_id, evnt_typ, vndr_tx_id, payload, payload_hash, sign_vl,
               rcv_dttm, prcs_stcd, rtry_cnt, prcs_dttm, vndr_cmpl_yn,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            SELECT 'notification-' || lpad(sequence::text, 8, '0'),
                   'transaction.status.updated',
                   'vendor-' || lpad(sequence::text, 8, '0'),
                   json_build_object(
                     'data',
                     json_build_object('status', CASE WHEN sequence <= 1000 THEN 'COMPLETED' ELSE 'CONFIRMING' END)
                   )::text,
                   repeat('a', 64), 'signature', '20260831010000', 'S', 0, '20260831010100',
                   CASE WHEN sequence <= 1000 THEN 'Y' ELSE 'N' END,
                   'SYSTEM', '9999', 'SYSTEM', '9999'
            FROM generate_series(1, 10000) sequence
            """.trimIndent(),
        )
        jdbc.execute(
            """
            INSERT INTO bcm_tx_l
              (vndr_tx_id, actv_tx_id, ext_tx_id, acnt_id, ntwk_cd, tkn_smbl, tx_hash,
               last_pub_stcd, cnfm_cnt, vndr_sub_stcd, vndr_ntwk_stcd, stall_alrt_dttm,
               vndr_crt_dttm, rcnc_chck_dttm, rcnc_chck_cnt, rcnc_stop_dttm,
               frst_dtct_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            SELECT 'vendor-' || lpad(sequence::text, 8, '0'),
                   'vendor-' || lpad(sequence::text, 8, '0'),
                   NULL, 'account-' || lpad(sequence::text, 8, '0'), 'ETHEREUM', 'USDC', NULL,
                   'FINALIZED', 3, 'COMPLETED', 'CONFIRMED', NULL,
                   '20260831000000', NULL, 0, NULL, '20260831000000', '20260831010000',
                   'SYSTEM', '9999', 'SYSTEM', '9999'
            FROM generate_series(1, 1000) sequence
            """.trimIndent(),
        )
        jdbc.execute(
            """
            CREATE TABLE bcm_raw_tx_l_202608 PARTITION OF bcm_raw_tx_l
            FOR VALUES FROM ('20260801') TO ('20260901')
            """.trimIndent(),
        )
        jdbc.execute(
            """
            INSERT INTO bcm_raw_tx_l
              (base_dt, vndr_tx_id, ext_tx_id, tx_hash, addr, ntwk_cd, tkn_smbl, final_stcd,
               payload, payload_hash, sign_vl, rcv_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            SELECT '20260831', 'vendor-' || lpad(sequence::text, 8, '0'), NULL, NULL,
                   '0xdestination', 'ETHEREUM', 'USDC', 'FINALIZED', '{}', repeat('b', 64),
                   'signature', '20260831010000', 'SYSTEM', '9999', 'SYSTEM', '9999'
            FROM generate_series(1, 100) sequence
            """.trimIndent(),
        )
        jdbc.execute(
            """
            INSERT INTO bcm_sbmt_l
              (ext_tx_id, req_hash, hash_vrsn, sbmt_stcd, tx_dvcd, vndr_tx_id, swp_exec_id,
               snd_acnt_id, rcv_dvcd, rcv_vl, ntwk_cd, tkn_smbl, trsf_amt, req_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            SELECT 'submission-' || lpad(sequence::text, 8, '0'), repeat('b', 64), 'tx-v1', 'SUBMITTED',
                   'WITHDRAWAL', 'submission-vendor-' || lpad(sequence::text, 8, '0'),
                   'execution-' || lpad(sequence::text, 8, '0'), 'sender', 'ADDRESS', '0xdestination',
                   'ETHEREUM', 'USDC', 1, '20260831010000',
                   'SYSTEM', '9999', 'SYSTEM', '9999'
            FROM generate_series(1, 10000) sequence
            """.trimIndent(),
        )
        jdbc.execute(
            """
            INSERT INTO bcm_swp_trgt
              (acnt_id, ntwk_cd, tkn_smbl, reg_dttm, try_cnt, last_try_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            SELECT 'target-' || lpad(sequence::text, 8, '0'), 'ETHEREUM', 'USDC', '20260831010000',
                   CASE WHEN sequence <= 10 THEN 5 ELSE 0 END,
                   CASE WHEN sequence <= 10 THEN '20260831010100' ELSE NULL END,
                   'SYSTEM', '9999', 'SYSTEM', '9999'
            FROM generate_series(1, 10000) sequence
            """.trimIndent(),
        )
        val snapshot =
            insertActiveSweepAdminSnapshot(
                jdbc,
                "ETHEREUM",
                "USDC",
                "0x4444444444444444444444444444444444444444",
            )
        jdbc.update(
            """
            INSERT INTO bcm_swp_exec_l
              (swp_exec_id, ext_tx_id, req_hash, ntwk_cd, tkn_smbl, opr_acnt_id,
               swp_ctrt_addr, swp_exec_stcd, item_cnt, req_tot_amt, actl_tot_amt,
               gasless_yn, vndr_tx_id, tx_hash, req_dttm, fnsh_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd,
               plcy_vrsn_id, plcy_snps_hash, ctrt_vrsn_id, ctrt_evdc_id)
            SELECT 'sweep-' || lpad(sequence::text, 8, '0'),
                   'sweep-ext-' || lpad(sequence::text, 8, '0'), repeat('c', 64),
                   'ETHEREUM', 'USDC', 'operator',
                   '0x4444444444444444444444444444444444444444', 'COMPLETED', 1, 1, 1,
                   'Y', 'sweep-vendor-' || lpad(sequence::text, 8, '0'),
                   '0xsweep' || lpad(sequence::text, 8, '0'), '20260831010000', '20260831010100',
                   'SYSTEM', '9999', 'SYSTEM', '9999', ?, ?, ?, ?
            FROM generate_series(1, 2000) sequence
            """.trimIndent(),
            snapshot.policyVersionId,
            snapshot.policySnapshotHash,
            snapshot.contractVersionId,
            snapshot.contractEvidenceId,
        )
    }

    private fun analyze(jdbc: JdbcTemplate) {
        listOf(
            "bcm_outbox_l",
            "bcm_evnt_cmpl_l",
            "bcm_whk_l",
            "bcm_tx_l",
            "bcm_raw_tx_l",
            "bcm_sbmt_l",
            "bcm_swp_trgt",
            "bcm_swp_exec_l",
        ).forEach { jdbc.execute("ANALYZE $it") }
    }

    private fun assertIndexUsed(
        jdbc: JdbcTemplate,
        indexName: String,
        sql: String,
    ) {
        val plan =
            jdbc
                .queryForList("EXPLAIN (COSTS OFF) $sql", String::class.java)
                .joinToString("\n")
        assertThat(plan)
            .describedAs("query plan for %s", sql)
            .contains(indexName)
    }

    private fun migrations(): List<String> =
        requireNotNull(javaClass.classLoader.getResourceAsStream("db/migration/manifest.txt"))
            .bufferedReader()
            .useLines { lines ->
                lines
                    .map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .toList()
            }

    private fun applyMigration(
        connection: Connection,
        migration: String,
    ) {
        val sql =
            requireNotNull(javaClass.classLoader.getResourceAsStream("db/migration/$migration")) {
                "migration resource not found: $migration"
            }.bufferedReader().use { it.readText() }
        val nonTransactional = sql.lineSequence().firstOrNull()?.trim() == "-- bcm:transaction=off"
        connection.autoCommit = nonTransactional
        try {
            connection.createStatement().use { statement ->
                if (nonTransactional) {
                    sql.splitToSequence(';').filter { it.isNotBlank() }.forEach(statement::execute)
                } else {
                    statement.execute(sql)
                    connection.commit()
                }
            }
        } catch (exception: Exception) {
            if (!nonTransactional) connection.rollback()
            throw exception
        } finally {
            connection.autoCommit = true
        }
    }
}
