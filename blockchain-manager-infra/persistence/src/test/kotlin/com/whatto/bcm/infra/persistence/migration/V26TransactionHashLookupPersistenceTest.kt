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
    fun `온체인 hash 조회는 전용 index를 쓰고 hash 없는 거래는 색인하지 않는다`() {
        val schema = "tx_hash_${UUID.randomUUID().toString().replace("-", "")}"

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            try {
                connection.schema = schema
                migrations().forEach { applyMigration(connection, it) }
                val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
                seedTransactions(jdbc)
                jdbc.execute("ANALYZE bcm_tx_l")

                val plan =
                    jdbc
                        .queryForList(
                            "EXPLAIN (COSTS OFF) SELECT vndr_tx_id FROM bcm_tx_l WHERE tx_hash = '0xhash00000500'",
                            String::class.java,
                        ).joinToString("\n")
                assertThat(plan).contains("idx_bcm_tx_hash")

                // 부분 index라 hash 없는 거래(제출 직후·입금 전)는 색인 대상이 아니다.
                val indexed =
                    jdbc.queryForObject(
                        "SELECT count(*) FROM bcm_tx_l WHERE tx_hash IS NOT NULL",
                        Long::class.java,
                    )
                assertThat(indexed).isEqualTo(1_000L)
                assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_tx_l", Long::class.java)).isEqualTo(1_500L)
                // 같은 hash를 가진 거래가 둘 이상 있어도 저장을 막지 않는다(RBF 계열·재관찰) — UNIQUE로 두지 않았다.
                assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM bcm_tx_l WHERE tx_hash = '0xhash00000001'",
                        Long::class.java,
                    ),
                ).isEqualTo(2L)
            } finally {
                connection.schema = null
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }

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
}
