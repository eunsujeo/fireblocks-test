package com.whatto.bcm.infra.persistence.migration

import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

class V17CompletedWebhookMarkerUpgradePersistenceTest : PersistenceTestSupport() {
    @Test
    fun `V17과 V18은 기존 원문을 backfill하고 구버전 worker의 롤링 배포 간극을 막는다`() {
        val schema = "v17_upgrade_${UUID.randomUUID().toString().replace("-", "")}"

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            try {
                connection.schema = schema
                migrationsBeforeV17().forEach { applyMigration(connection, it) }
                val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
                seedProcessedWebhooks(jdbc)

                applyMigration(connection, "V17__completed_webhook_marker_expand.sql")
                applySqlResource(connection, "db/operations/prepare_v18_completed_webhook_backfill_index.sql")
                jdbc.update(
                    "UPDATE bcm_whk_l SET prcs_stcd = 'S', prcs_dttm = '20260831010200' WHERE noti_id = 'old-worker-before-backfill'",
                )
                applyMigration(connection, "V18__completed_webhook_marker_backfill_and_index.sql")
                applyMigration(connection, "V19__completed_webhook_backfill_index_cleanup.sql")
                jdbc.update(
                    "UPDATE bcm_whk_l SET prcs_stcd = 'S', prcs_dttm = '20260831010300' WHERE noti_id = 'old-worker-after-backfill'",
                )
                applyMigration(connection, "V18__completed_webhook_marker_backfill_and_index.sql")

                assertThat(
                    jdbc.queryForList(
                        "SELECT noti_id, vndr_cmpl_yn FROM bcm_whk_l WHERE noti_id NOT LIKE 'bulk-%' ORDER BY noti_id",
                    ),
                ).containsExactly(
                    mapOf("noti_id" to "completed", "vndr_cmpl_yn" to "Y"),
                    mapOf("noti_id" to "confirming", "vndr_cmpl_yn" to "N"),
                    mapOf("noti_id" to "old-worker-after-backfill", "vndr_cmpl_yn" to "Y"),
                    mapOf("noti_id" to "old-worker-before-backfill", "vndr_cmpl_yn" to "Y"),
                    mapOf("noti_id" to "unsupported", "vndr_cmpl_yn" to "N"),
                )
                assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM bcm_whk_l WHERE noti_id LIKE 'bulk-%' AND vndr_cmpl_yn = 'N'",
                        Int::class.java,
                    ),
                ).isEqualTo(1001)
                assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM pg_indexes WHERE schemaname = ? AND indexname = ?",
                        Int::class.java,
                        schema,
                        "idx_bcm_whk_completed_archive",
                    ),
                ).isEqualTo(1)
                assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM pg_indexes WHERE schemaname = ? AND indexname = ?",
                        Int::class.java,
                        schema,
                        "idx_bcm_whk_completed_backfill",
                    ),
                ).isZero()
            } finally {
                connection.schema = "public"
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }

    @Test
    fun `V18 backfill 후보는 처리된 PK prefix 대신 NULL partial index에서 찾는다`() {
        val schema = "v18_plan_${UUID.randomUUID().toString().replace("-", "")}"

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            try {
                connection.schema = schema
                migrationsBeforeV17().forEach { applyMigration(connection, it) }
                val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
                seedBackfillPlanWebhooks(jdbc)

                applyMigration(connection, "V17__completed_webhook_marker_expand.sql")
                applySqlResource(connection, "db/operations/prepare_v18_completed_webhook_backfill_index.sql")
                jdbc.update("UPDATE bcm_whk_l SET vndr_cmpl_yn = 'N' WHERE noti_id < 'plan-09001'")
                jdbc.execute("ANALYZE bcm_whk_l")

                val plan =
                    jdbc
                        .queryForList(
                            """
                            EXPLAIN (FORMAT TEXT, COSTS OFF)
                            SELECT noti_id
                            FROM bcm_whk_l
                            WHERE vndr_cmpl_yn IS NULL
                            ORDER BY noti_id
                            LIMIT 1000
                            FOR UPDATE SKIP LOCKED
                            """.trimIndent(),
                            String::class.java,
                        ).joinToString("\n")

                assertThat(plan).contains("idx_bcm_whk_completed_backfill")
            } finally {
                connection.schema = "public"
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }

    private fun seedProcessedWebhooks(jdbc: JdbcTemplate) {
        jdbc.execute(
            """
            INSERT INTO bcm_whk_l
              (noti_id, evnt_typ, vndr_tx_id, payload, payload_hash, sign_vl,
               rcv_dttm, prcs_stcd, rtry_cnt, err_msg, prcs_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              ('completed', 'transaction.status.updated', 'tx-completed',
               '{"data":{"status":"COMPLETED"}}', repeat('a', 64), 'signature',
               '20260831010000', 'S', 0, NULL, '20260831010100', 'SYSTEM', '9999', 'SYSTEM', '9999'),
              ('confirming', 'transaction.status.updated', 'tx-confirming',
               '{"data":{"status":"CONFIRMING"}}', repeat('b', 64), 'signature',
               '20260831010000', 'S', 0, NULL, '20260831010100', 'SYSTEM', '9999', 'SYSTEM', '9999'),
              ('unsupported', 'unsupported.event', 'tx-unsupported', 'not-json', repeat('c', 64), 'signature',
               '20260831010000', 'S', 0, NULL, '20260831010100', 'SYSTEM', '9999', 'SYSTEM', '9999'),
              ('old-worker-before-backfill', 'transaction.status.updated', 'tx-old-before',
               '{"data":{"status":"COMPLETED"}}', repeat('d', 64), 'signature',
               '20260831010000', 'P', 0, NULL, NULL, 'SYSTEM', '9999', 'SYSTEM', '9999'),
              ('old-worker-after-backfill', 'transaction.status.updated', 'tx-old-after',
               '{"data":{"status":"COMPLETED"}}', repeat('e', 64), 'signature',
               '20260831010000', 'P', 0, NULL, NULL, 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
        jdbc.execute(
            """
            INSERT INTO bcm_whk_l
              (noti_id, evnt_typ, vndr_tx_id, payload, payload_hash, sign_vl,
               rcv_dttm, prcs_stcd, rtry_cnt, err_msg, prcs_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            SELECT 'bulk-' || lpad(sequence::text, 4, '0'),
                   'transaction.status.updated', 'tx-bulk-' || sequence,
                   '{"data":{"status":"CONFIRMING"}}', repeat('f', 64), 'signature',
                   '20260831010000', 'S', 0, NULL, '20260831010100',
                   'SYSTEM', '9999', 'SYSTEM', '9999'
            FROM generate_series(1, 1001) sequence
            """.trimIndent(),
        )
    }

    private fun seedBackfillPlanWebhooks(jdbc: JdbcTemplate) {
        jdbc.execute(
            """
            INSERT INTO bcm_whk_l
              (noti_id, evnt_typ, vndr_tx_id, payload, payload_hash, sign_vl,
               rcv_dttm, prcs_stcd, rtry_cnt, err_msg, prcs_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            SELECT 'plan-' || lpad(sequence::text, 5, '0'),
                   'transaction.status.updated', 'tx-plan-' || sequence,
                   '{"data":{"status":"CONFIRMING"}}', repeat('a', 64), 'signature',
                   '20260831010000', 'S', 0, NULL, '20260831010100',
                   'SYSTEM', '9999', 'SYSTEM', '9999'
            FROM generate_series(1, 10000) sequence
            """.trimIndent(),
        )
    }

    private fun migrationsBeforeV17(): List<String> =
        requireNotNull(javaClass.classLoader.getResourceAsStream("db/migration/manifest.txt"))
            .bufferedReader()
            .useLines { lines ->
                lines
                    .map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .takeWhile { it != "V17__completed_webhook_marker_expand.sql" }
                    .toList()
            }

    private fun applyMigration(
        connection: Connection,
        migration: String,
    ) = applySqlResource(connection, "db/migration/$migration")

    private fun applySqlResource(
        connection: Connection,
        resource: String,
    ) {
        val sql =
            requireNotNull(javaClass.classLoader.getResourceAsStream(resource)) {
                "SQL resource not found: $resource"
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
