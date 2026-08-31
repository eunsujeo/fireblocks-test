package com.whatto.bcm.infra.persistence.migration

import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

class V14SweepRequestUpgradePersistenceTest : PersistenceTestSupport() {
    @Test
    fun `V13 실행 원장을 V14 요청 원장으로 보존하고 무요청 target만 정리한다`() {
        val schema = "v14_upgrade_${UUID.randomUUID().toString().replace("-", "")}"

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
            connection.createStatement().use { it.execute("CREATE SCHEMA $schema") }
            try {
                connection.schema = schema
                migrationsBeforeV14().forEach { applyMigration(connection, it) }
                val jdbc = JdbcTemplate(SingleConnectionDataSource(connection, true))
                seedV13(jdbc)

                val executionsBefore = executionRows(jdbc)
                val itemsBefore = itemRows(jdbc)

                applyMigration(connection, V14_MIGRATION)

                assertThat(executionRows(jdbc)).containsExactlyElementsOf(executionsBefore)
                assertThat(itemRows(jdbc)).containsExactlyElementsOf(itemsBefore)
                assertThat(legacyRequests(jdbc))
                    .containsExactlyInAnyOrder(
                        LegacyRequest("LEGACY:legacy-ready", "PROCESSING", 1, null),
                        LegacyRequest("LEGACY:legacy-submitting", "PROCESSING", 1, null),
                        LegacyRequest("LEGACY:legacy-partial", "PARTIAL", 2, "20260813093000"),
                        LegacyRequest("LEGACY:legacy-failed", "FAILED", 1, "20260813094000"),
                        LegacyRequest("LEGACY:legacy-completed", "COMPLETED", 1, "20260813095000"),
                    )
                assertThat(legacyRequestItems(jdbc))
                    .containsExactlyInAnyOrder(
                        LegacyRequestItem("LEGACY:legacy-ready", 1, "PROCESSING", null),
                        LegacyRequestItem("LEGACY:legacy-submitting", 1, "PROCESSING", null),
                        LegacyRequestItem("LEGACY:legacy-partial", 1, "COMPLETED", null),
                        LegacyRequestItem("LEGACY:legacy-partial", 2, "FAILED", "LEG_FAILED"),
                        LegacyRequestItem("LEGACY:legacy-failed", 1, "PENDING", "LEG_RETRY"),
                        LegacyRequestItem("LEGACY:legacy-completed", 1, "COMPLETED", null),
                    )
                assertThat(
                    jdbc.queryForObject(
                        "SELECT count(*) FROM bcm_swp_item_l WHERE swp_req_item_id IS NOT NULL",
                        Int::class.java,
                    ),
                ).isEqualTo(itemsBefore.size)
                assertThat(
                    jdbc.queryForObject(
                        """
                        SELECT count(*)
                        FROM bcm_swp_item_l item
                        JOIN bcm_swp_req_item_l request_item
                          ON request_item.swp_req_item_id = item.swp_req_item_id
                        """.trimIndent(),
                        Int::class.java,
                    ),
                ).isEqualTo(itemsBefore.size)
                assertThat(
                    jdbc.queryForObject(
                        """
                        SELECT is_nullable
                        FROM information_schema.columns
                        WHERE table_schema = current_schema()
                          AND table_name = 'bcm_swp_item_l'
                          AND column_name = 'swp_req_item_id'
                        """.trimIndent(),
                        String::class.java,
                    ),
                ).isEqualTo("NO")
                assertThat(targetRows(jdbc))
                    .containsExactly(TargetRow("account-ready", "legacy-ready", 1))
            } finally {
                connection.schema = "public"
                connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
            }
        }
    }

    private fun seedV13(jdbc: JdbcTemplate) {
        val fixtures =
            listOf(
                ExecutionFixture("legacy-ready", "READY", listOf(ItemFixture("account-ready", "READY"))),
                ExecutionFixture("legacy-submitting", "SUBMITTING", listOf(ItemFixture("account-submitting", "READY"))),
                ExecutionFixture(
                    "legacy-partial",
                    "PARTIAL",
                    listOf(
                        ItemFixture("account-partial-success", "SUCCEEDED"),
                        ItemFixture("account-partial-failed", "FAILED", "LEG_FAILED"),
                    ),
                    "20260813093000",
                ),
                ExecutionFixture(
                    "legacy-failed",
                    "FAILED",
                    listOf(ItemFixture("account-retry", "RETRY", "LEG_RETRY")),
                    "20260813094000",
                ),
                ExecutionFixture(
                    "legacy-completed",
                    "COMPLETED",
                    listOf(ItemFixture("account-completed", "SUCCEEDED")),
                    "20260813095000",
                ),
            )
        (fixtures.flatMap { it.items.map(ItemFixture::accountId) } + "account-unclaimed").forEach { accountId ->
            jdbc.update(
                """
                INSERT INTO bcm_acnt_m
                  (acnt_id, acnt_typ_dvcd, ref, vndr_vlt_id, reg_dttm,
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES (?, 'CU', ?, ?, '20260813090000', 'SYSTEM', '9999', 'SYSTEM', '9999')
                """.trimIndent(),
                accountId,
                "ref-$accountId",
                "vault-$accountId",
            )
        }
        val snapshot =
            insertActiveSweepAdminSnapshot(
                jdbc,
                NETWORK,
                SYMBOL,
                CONTRACT_ADDRESS,
            )
        fixtures.forEachIndexed { index, execution ->
            jdbc.update(
                """
                INSERT INTO bcm_swp_exec_l
                  (swp_exec_id, ext_tx_id, req_hash, ntwk_cd, tkn_smbl, opr_acnt_id,
                   swp_ctrt_addr, swp_exec_stcd, item_cnt, req_tot_amt, actl_tot_amt,
                   gasless_yn, vndr_tx_id, tx_hash, req_dttm, fnsh_dttm,
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd,
                   plcy_vrsn_id, plcy_snps_hash, ctrt_vrsn_id, ctrt_evdc_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL,
                        'Y', NULL, NULL, ?, ?, 'SYSTEM', '9999', 'SYSTEM', '9999', ?, ?, ?, ?)
                """.trimIndent(),
                execution.id,
                "external-${execution.id}",
                ("${index + 1}").repeat(64),
                NETWORK,
                SYMBOL,
                "operator-${execution.id}",
                CONTRACT_ADDRESS,
                execution.status,
                execution.items.size,
                execution.items.size * 10,
                "2026081309${index}000",
                execution.finishedAt,
                snapshot.policyVersionId,
                snapshot.policySnapshotHash,
                snapshot.contractVersionId,
                snapshot.contractEvidenceId,
            )
            execution.items.forEachIndexed { itemIndex, item ->
                jdbc.update(
                    """
                    INSERT INTO bcm_swp_item_l
                      (swp_exec_id, item_seq, acnt_id, src_addr, req_amt, actl_amt,
                       swp_item_stcd, fail_cd, log_idx,
                       frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                    VALUES (?, ?, ?, ?, 10, NULL, ?, ?, NULL,
                            'SYSTEM', '9999', 'SYSTEM', '9999')
                    """.trimIndent(),
                    execution.id,
                    itemIndex + 1,
                    item.accountId,
                    "0x${index + 1}${itemIndex + 1}",
                    item.status,
                    item.failureCode,
                )
            }
        }
        jdbc.update(
            """
            INSERT INTO bcm_swp_trgt
              (acnt_id, ntwk_cd, tkn_smbl, reg_dttm, actv_swp_exec_id, actv_item_seq,
               try_cnt, last_try_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('account-ready', ?, ?, '20260813090000', 'legacy-ready', 1,
                    1, '20260813091000', 'SYSTEM', '9999', 'SYSTEM', '9999'),
                   ('account-unclaimed', ?, ?, '20260813090000', NULL, NULL,
                    0, NULL, 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
            NETWORK,
            SYMBOL,
            NETWORK,
            SYMBOL,
        )
    }

    private fun migrationsBeforeV14(): List<String> =
        requireNotNull(javaClass.classLoader.getResourceAsStream("db/migration/manifest.txt"))
            .bufferedReader()
            .useLines { lines ->
                lines
                    .map(String::trim)
                    .filter { it.isNotEmpty() && !it.startsWith("#") }
                    .takeWhile { it != V14_MIGRATION }
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

    private fun executionRows(jdbc: JdbcTemplate): List<ExecutionRow> =
        jdbc.query(
            """
            SELECT swp_exec_id, swp_exec_stcd, item_cnt
            FROM bcm_swp_exec_l
            ORDER BY swp_exec_id
            """.trimIndent(),
        ) { resultSet, _ ->
            ExecutionRow(
                resultSet.getString("swp_exec_id"),
                resultSet.getString("swp_exec_stcd"),
                resultSet.getInt("item_cnt"),
            )
        }

    private fun itemRows(jdbc: JdbcTemplate): List<ItemRow> =
        jdbc.query(
            """
            SELECT swp_exec_id, item_seq, acnt_id, swp_item_stcd, fail_cd
            FROM bcm_swp_item_l
            ORDER BY swp_exec_id, item_seq
            """.trimIndent(),
        ) { resultSet, _ ->
            ItemRow(
                resultSet.getString("swp_exec_id"),
                resultSet.getInt("item_seq"),
                resultSet.getString("acnt_id"),
                resultSet.getString("swp_item_stcd"),
                resultSet.getString("fail_cd"),
            )
        }

    private fun legacyRequests(jdbc: JdbcTemplate): List<LegacyRequest> =
        jdbc.query(
            """
            SELECT ext_swp_req_id, swp_req_stcd, item_cnt, fnsh_dttm
            FROM bcm_swp_req_l
            ORDER BY ext_swp_req_id
            """.trimIndent(),
        ) { resultSet, _ ->
            LegacyRequest(
                resultSet.getString("ext_swp_req_id"),
                resultSet.getString("swp_req_stcd"),
                resultSet.getInt("item_cnt"),
                resultSet.getString("fnsh_dttm"),
            )
        }

    private fun legacyRequestItems(jdbc: JdbcTemplate): List<LegacyRequestItem> =
        jdbc.query(
            """
            SELECT request.ext_swp_req_id, item.item_seq, item.swp_req_item_stcd, item.last_fail_cd
            FROM bcm_swp_req_item_l item
            JOIN bcm_swp_req_l request ON request.swp_req_id = item.swp_req_id
            ORDER BY request.ext_swp_req_id, item.item_seq
            """.trimIndent(),
        ) { resultSet, _ ->
            LegacyRequestItem(
                resultSet.getString("ext_swp_req_id"),
                resultSet.getInt("item_seq"),
                resultSet.getString("swp_req_item_stcd"),
                resultSet.getString("last_fail_cd"),
            )
        }

    private fun targetRows(jdbc: JdbcTemplate): List<TargetRow> =
        jdbc.query(
            """
            SELECT acnt_id, actv_swp_exec_id, actv_item_seq
            FROM bcm_swp_trgt
            ORDER BY acnt_id
            """.trimIndent(),
        ) { resultSet, _ ->
            TargetRow(
                resultSet.getString("acnt_id"),
                resultSet.getString("actv_swp_exec_id"),
                resultSet.getInt("actv_item_seq"),
            )
        }

    private data class ExecutionFixture(
        val id: String,
        val status: String,
        val items: List<ItemFixture>,
        val finishedAt: String? = null,
    )

    private data class ItemFixture(
        val accountId: String,
        val status: String,
        val failureCode: String? = null,
    )

    private data class ExecutionRow(
        val id: String,
        val status: String,
        val itemCount: Int,
    )

    private data class ItemRow(
        val executionId: String,
        val sequence: Int,
        val accountId: String,
        val status: String,
        val failureCode: String?,
    )

    private data class LegacyRequest(
        val externalRequestId: String,
        val status: String,
        val itemCount: Int,
        val finishedAt: String?,
    )

    private data class LegacyRequestItem(
        val externalRequestId: String,
        val sequence: Int,
        val status: String,
        val failureCode: String?,
    )

    private data class TargetRow(
        val accountId: String,
        val activeExecutionId: String,
        val activeItemSequence: Int,
    )

    private companion object {
        const val V14_MIGRATION = "V14__daw_sweep_request_and_event_completion.sql"
        const val NETWORK = "ETHEREUM"
        const val SYMBOL = "USDC"
        const val CONTRACT_ADDRESS = "0x4444444444444444444444444444444444444444"
    }
}
