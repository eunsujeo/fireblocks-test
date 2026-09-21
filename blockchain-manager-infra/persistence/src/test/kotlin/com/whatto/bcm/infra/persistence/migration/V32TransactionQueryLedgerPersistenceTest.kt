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
 * V32·V33 — 거래 조회 원장(03 V32).
 * 공개 조회가 BCM 원장만 읽으려면 금액·발신/수신 주소·거래 구분이 있어야 하고, 제출 마감이 만든 행은
 * 벤더 시각이 비어 있어야 한다. 네 컬럼은 추가 전용이라 **기존 행을 건드리지 않는다**는 것이 이 테스트의 핵심이다.
 */
class V32TransactionQueryLedgerPersistenceTest : PersistenceTestSupport() {
    @Test
    fun `기존 행을 그대로 둔 채 조회 컬럼을 열고 벤더 시각을 비울 수 있게 한다`() {
        val schema = "tx_query_${UUID.randomUUID().toString().replace("-", "")}"

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            try {
                connection.schema = schema
                val all = migrations()
                val upgradeIndex = all.indexOf(COLUMNS_MIGRATION)
                assertThat(upgradeIndex).describedAs("manifest must list %s", COLUMNS_MIGRATION).isNotNegative()
                all.take(upgradeIndex).forEach { applyMigration(connection, it) }
                val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
                seedTransaction(jdbc)

                // 업그레이드 전에는 벤더 시각이 필수라 비운 행을 넣을 수 없다.
                assertThat(isNullable(jdbc, "vndr_crt_dttm")).isFalse()

                applyMigration(connection, COLUMNS_MIGRATION)
                applyMigration(connection, INDEX_MIGRATION)

                // 기존 행은 값이 그대로고 새 컬럼만 비어 있다 — 백필은 Q1이 따로 한다.
                val existing = jdbc.queryForMap("SELECT * FROM bcm_tx_l WHERE vndr_tx_id = 'tx-before'")
                assertThat(existing["vndr_crt_dttm"]).isEqualTo("20260916000000")
                assertThat(existing["trsf_amt"]).isNull()
                assertThat(existing["src_addr"]).isNull()
                assertThat(existing["dst_addr"]).isNull()
                assertThat(existing["tx_dvcd"]).isNull()

                assertThat(columnType(jdbc, "trsf_amt")).isEqualTo("numeric")
                // 자릿수를 고정하지 않는다 — Dfns 자산 정밀도는 0..255다.
                assertThat(numericPrecision(jdbc, "trsf_amt")).isNull()
                assertThat(characterMaxLength(jdbc, "src_addr")).isEqualTo(256)
                assertThat(characterMaxLength(jdbc, "dst_addr")).isEqualTo(256)
                assertThat(characterMaxLength(jdbc, "tx_dvcd")).isEqualTo(16)
                listOf("trsf_amt", "src_addr", "dst_addr", "tx_dvcd").forEach {
                    assertThat(isNullable(jdbc, it)).describedAs(it).isTrue()
                }

                // 벤더 시각이 비어도 저장된다 — 제출 마감이 만든 행이다.
                assertThat(isNullable(jdbc, "vndr_crt_dttm")).isTrue()
                jdbc.update(
                    "INSERT INTO bcm_tx_l SELECT * FROM bcm_tx_l WHERE vndr_tx_id = 'tx-before'" +
                        " ON CONFLICT DO NOTHING",
                )
                jdbc.update(
                    """
                    INSERT INTO bcm_tx_l
                      (vndr_tx_id, actv_tx_id, acnt_id, ntwk_cd, tkn_smbl, last_pub_stcd, cnfm_cnt,
                       vndr_crt_dttm, rcnc_chck_cnt, frst_dtct_dttm, last_chng_dttm,
                       frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                    VALUES ('tx-submitted', 'tx-submitted', 'account-1', 'ETHEREUM', 'USDC', 'SUBMITTED', 0,
                            NULL, 0, '20260916000000', '20260916000000', 'SYSTEM', '9999', 'SYSTEM', '9999')
                    """.trimIndent(),
                )

                val definition =
                    jdbc.queryForObject(
                        "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema() AND indexname = ?",
                        String::class.java,
                        INDEX,
                    )
                // 02가 정한 목록 정렬 keyset 순서 그대로여야 인덱스를 탄다.
                assertThat(definition).contains("(acnt_id, frst_dtct_dttm, vndr_tx_id)")
                assertThat(isIndexValid(jdbc, INDEX)).isTrue()

                // 두 마이그레이션 모두 다시 돌려도 안전하다.
                applyMigration(connection, COLUMNS_MIGRATION)
                applyMigration(connection, INDEX_MIGRATION)
                assertThat(isIndexValid(jdbc, INDEX)).isTrue()
                assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_tx_l", Long::class.java)).isEqualTo(2L)
            } finally {
                connection.schema = null
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }

    private fun seedTransaction(jdbc: JdbcTemplate) {
        jdbc.execute(
            """
            INSERT INTO bcm_tx_l
              (vndr_tx_id, actv_tx_id, acnt_id, ntwk_cd, tkn_smbl, last_pub_stcd, cnfm_cnt,
               vndr_crt_dttm, rcnc_chck_cnt, frst_dtct_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('tx-before', 'tx-before', 'account-1', 'ETHEREUM', 'USDC', 'CONFIRMED', 1,
                    '20260916000000', 0, '20260916000000', '20260916010000', 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
    }

    private fun isNullable(
        jdbc: JdbcTemplate,
        column: String,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT is_nullable = 'YES' FROM information_schema.columns
             WHERE table_schema = current_schema() AND table_name = 'bcm_tx_l' AND column_name = ?
            """.trimIndent(),
            Boolean::class.java,
            column,
        ) ?: error("column not found: $column")

    private fun columnType(
        jdbc: JdbcTemplate,
        column: String,
    ): String? = columnAttribute(jdbc, column, "data_type", String::class.java)

    private fun numericPrecision(
        jdbc: JdbcTemplate,
        column: String,
    ): Int? = columnAttribute(jdbc, column, "numeric_precision", Int::class.javaObjectType)

    private fun characterMaxLength(
        jdbc: JdbcTemplate,
        column: String,
    ): Int? = columnAttribute(jdbc, column, "character_maximum_length", Int::class.javaObjectType)

    private fun <T : Any> columnAttribute(
        jdbc: JdbcTemplate,
        column: String,
        attribute: String,
        type: Class<T>,
    ): T? =
        jdbc.queryForObject(
            """
            SELECT $attribute FROM information_schema.columns
             WHERE table_schema = current_schema() AND table_name = 'bcm_tx_l' AND column_name = ?
            """.trimIndent(),
            type,
            column,
        )

    private fun isIndexValid(
        jdbc: JdbcTemplate,
        index: String,
    ): Boolean? =
        jdbc.queryForObject(
            """
            SELECT i.indisvalid FROM pg_index i
            JOIN pg_class c ON c.oid = i.indexrelid
            WHERE c.relnamespace = current_schema()::regnamespace AND c.relname = ?
            """.trimIndent(),
            Boolean::class.java,
            index,
        )

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
        const val COLUMNS_MIGRATION = "V32__transaction_query_ledger.sql"
        const val INDEX_MIGRATION = "V33__transaction_account_listing_index.sql"
        const val INDEX = "idx_bcm_tx_account_listing"
    }
}
