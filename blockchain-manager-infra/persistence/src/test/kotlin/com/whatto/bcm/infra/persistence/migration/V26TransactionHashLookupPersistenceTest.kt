package com.whatto.bcm.infra.persistence.migration

import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * V26 — 논리 거래의 온체인 hash 조회 인덱스(03).
 * Dfns 입금의 `vndr_tx_id`는 파생 값이라 운영 조사와 발신 사건 대조를 hash로 한다.
 */
class V26TransactionHashLookupPersistenceTest : PersistenceTestSupport() {
    @Test
    fun `기존 데이터가 있는 원장에 온라인으로 부분 index를 더하고 hash 조회가 그것을 쓴다`() {
        val schema = "tx_hash_${UUID.randomUUID().toString().replace("-", "")}"

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            try {
                connection.schema = schema
                // 업그레이드 경로 — V25까지 적용해 기존 거래를 쌓은 뒤 V26을 적용한다.
                val all = migrations()
                val upgrade = all.last()
                assertThat(upgrade).isEqualTo(MIGRATION)
                all.dropLast(1).forEach { applyMigration(connection, it) }
                val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
                seedTransactions(jdbc)
                assertThat(indexNames(jdbc)).doesNotContain(INDEX)

                applyMigration(connection, upgrade)

                assertThat(indexNames(jdbc)).contains(INDEX)
                // 실제 index 정의에 부분 조건이 들어 있는지 확인한다 — 행 수 집계로는 predicate를 증명하지 못한다.
                val definition =
                    jdbc.queryForObject(
                        "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema() AND indexname = ?",
                        String::class.java,
                        INDEX,
                    )
                assertThat(definition).contains("bcm_tx_l").contains("tx_hash").contains("WHERE (tx_hash IS NOT NULL)")
                // 온라인 생성이 끝났으면 index는 유효 상태여야 한다.
                assertThat(
                    jdbc.queryForObject(
                        """
                        SELECT i.indisvalid FROM pg_index i
                        JOIN pg_class c ON c.oid = i.indexrelid
                        WHERE c.relnamespace = current_schema()::regnamespace AND c.relname = ?
                        """.trimIndent(),
                        Boolean::class.java,
                        INDEX,
                    ),
                ).isTrue()

                jdbc.execute("ANALYZE bcm_tx_l")
                val plan =
                    jdbc
                        .queryForList(
                            "EXPLAIN (COSTS OFF) SELECT vndr_tx_id FROM bcm_tx_l WHERE tx_hash = '0xhash00000500'",
                            String::class.java,
                        ).joinToString("\n")
                assertThat(plan).contains(INDEX)

                // 같은 hash를 가진 거래가 둘 이상 있어도 저장을 막지 않는다(RBF 계열·재관찰) — UNIQUE로 두지 않았다.
                assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM bcm_tx_l WHERE tx_hash = '0xhash00000001'",
                        Long::class.java,
                    ),
                ).isEqualTo(2L)
                assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_tx_l", Long::class.java)).isEqualTo(1_500L)
            } finally {
                connection.schema = null
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }

    private fun indexNames(jdbc: JdbcTemplate): List<String?> =
        jdbc.queryForList(
            "SELECT indexname FROM pg_indexes WHERE schemaname = current_schema() AND tablename = 'bcm_tx_l'",
            String::class.java,
        )

    private fun seedTransactions(jdbc: JdbcTemplate) {
        jdbc.execute(
            """
            INSERT INTO bcm_tx_l
              (vndr_tx_id, actv_tx_id, ext_tx_id, acnt_id, ntwk_cd, tkn_smbl, tx_hash,
               last_pub_stcd, cnfm_cnt, vndr_crt_dttm, rcnc_chck_cnt,
               frst_dtct_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            SELECT 'dfns-' || lpad(sequence::text, 8, '0'),
                   'dfns-' || lpad(sequence::text, 8, '0'),
                   NULL, 'account-' || lpad(sequence::text, 8, '0'), 'ETHEREUM_SEPOLIA', 'USDC',
                   CASE WHEN sequence <= 1000 THEN '0xhash' || lpad((sequence % 999)::text, 8, '0') ELSE NULL END,
                   'CONFIRMED', 1, '20260916000000', 0, '20260916000000', '20260916010000',
                   'SYSTEM', '9999', 'SYSTEM', '9999'
            FROM generate_series(1, 1500) sequence
            """.trimIndent(),
        )
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

    private companion object {
        const val INDEX = "idx_bcm_tx_hash"
        const val MIGRATION = "V26__transaction_hash_lookup_index.sql"
    }
}
