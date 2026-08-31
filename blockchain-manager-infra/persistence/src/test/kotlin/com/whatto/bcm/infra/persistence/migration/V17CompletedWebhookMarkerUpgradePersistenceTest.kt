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
    fun `V17은 기존 처리 완료 원문의 COMPLETED 표식을 안전하게 backfill한다`() {
        val schema = "v17_upgrade_${UUID.randomUUID().toString().replace("-", "")}"

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            try {
                connection.schema = schema
                migrationsBeforeV17().forEach { applyMigration(connection, it) }
                val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
                seedProcessedWebhooks(jdbc)

                applyMigration(connection, "V17__completed_webhook_archive_index.sql")

                assertThat(
                    jdbc.queryForList(
                        "SELECT noti_id, vndr_cmpl_yn FROM bcm_whk_l ORDER BY noti_id",
                    ),
                ).containsExactly(
                    mapOf("noti_id" to "completed", "vndr_cmpl_yn" to "Y"),
                    mapOf("noti_id" to "confirming", "vndr_cmpl_yn" to "N"),
                    mapOf("noti_id" to "unsupported", "vndr_cmpl_yn" to "N"),
                )
                assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM pg_indexes WHERE schemaname = ? AND indexname = ?",
                        Int::class.java,
                        schema,
                        "idx_bcm_whk_completed_archive",
                    ),
                ).isEqualTo(1)
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
               '20260831010000', 'S', 0, NULL, '20260831010100', 'SYSTEM', '9999', 'SYSTEM', '9999')
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
                    .takeWhile { it != "V17__completed_webhook_archive_index.sql" }
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
        connection.autoCommit = false
        try {
            connection.createStatement().use { it.execute(sql) }
            connection.commit()
        } catch (exception: Exception) {
            connection.rollback()
            throw exception
        } finally {
            connection.autoCommit = true
        }
    }
}
