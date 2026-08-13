package com.whatto.bcm.infra.persistence.sweep

import com.whatto.bcm.domain.sweep.SweepTarget
import com.whatto.bcm.domain.sweep.SweepTargetKey
import com.whatto.bcm.domain.sweep.SweepTargetRepository
import com.whatto.bcm.support.audit.SystemAudit
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class SweepTargetJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : SweepTargetRepository {
    override fun insertIfAbsent(target: SweepTarget): Boolean =
        jdbc.update(
            """
            WITH existing AS MATERIALIZED (
              SELECT 1
              FROM bcm_swp_trgt
              WHERE acnt_id = :accountId AND ntwk_cd = :network AND tkn_smbl = :symbol
              FOR UPDATE
            )
            INSERT INTO bcm_swp_trgt
              (acnt_id, ntwk_cd, tkn_smbl, reg_dttm, actv_swp_exec_id, actv_item_seq, try_cnt, last_try_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            SELECT
              :accountId, :network, :symbol, :registeredAt, :activeSweepExecutionId, :activeItemSequence,
              :attemptCount, :lastAttemptedAt,
              :employeeNo, :branchCode, :employeeNo, :branchCode
            WHERE NOT EXISTS (SELECT 1 FROM existing)
            ON CONFLICT (acnt_id, ntwk_cd, tkn_smbl) DO NOTHING
            """.trimIndent(),
            targetParameters(target),
        ) == 1

    override fun findByKey(key: SweepTargetKey): SweepTarget? =
        jdbc
            .query(
                "$SELECT_COLUMNS WHERE acnt_id = :accountId AND ntwk_cd = :network AND tkn_smbl = :symbol",
                keyParameters(key),
                ROW_MAPPER,
            ).firstOrNull()

    override fun findByKeyForUpdate(key: SweepTargetKey): SweepTarget? =
        jdbc
            .query(
                """
                $SELECT_COLUMNS
                WHERE acnt_id = :accountId AND ntwk_cd = :network AND tkn_smbl = :symbol
                FOR UPDATE
                """.trimIndent(),
                keyParameters(key),
                ROW_MAPPER,
            ).firstOrNull()

    override fun findPending(limit: Int): List<SweepTarget> {
        require(limit > 0) { "sweep target query limit must be positive: limit=$limit" }
        return jdbc.query(
            """
            $SELECT_COLUMNS
            WHERE actv_swp_exec_id IS NULL AND actv_item_seq IS NULL
            ORDER BY reg_dttm, acnt_id, ntwk_cd, tkn_smbl
            LIMIT :limit
            """.trimIndent(),
            mapOf("limit" to limit),
            ROW_MAPPER,
        )
    }

    override fun findPendingForUpdate(key: SweepTargetKey): SweepTarget? =
        jdbc
            .query(
                """
                $SELECT_COLUMNS
                WHERE acnt_id = :accountId AND ntwk_cd = :network AND tkn_smbl = :symbol
                  AND actv_swp_exec_id IS NULL AND actv_item_seq IS NULL
                FOR UPDATE
                """.trimIndent(),
                keyParameters(key),
                ROW_MAPPER,
            ).firstOrNull()

    override fun releaseClaim(
        key: SweepTargetKey,
        executionId: String,
        itemSequence: Int,
    ): Boolean =
        jdbc.update(
            """
            UPDATE bcm_swp_trgt
            SET actv_swp_exec_id = NULL,
                actv_item_seq = NULL,
                last_chng_empno = :employeeNo,
                last_chng_brcd = :branchCode
            WHERE acnt_id = :accountId AND ntwk_cd = :network AND tkn_smbl = :symbol
              AND actv_swp_exec_id = :executionId
              AND actv_item_seq = :itemSequence
            """.trimIndent(),
            keyParameters(key) + mapOf("executionId" to executionId, "itemSequence" to itemSequence),
        ) == 1

    override fun deleteClaim(
        key: SweepTargetKey,
        executionId: String,
        itemSequence: Int,
    ): Boolean =
        jdbc.update(
            """
            DELETE FROM bcm_swp_trgt
            WHERE acnt_id = :accountId AND ntwk_cd = :network AND tkn_smbl = :symbol
              AND actv_swp_exec_id = :executionId
              AND actv_item_seq = :itemSequence
            """.trimIndent(),
            keyParameters(key) + mapOf("executionId" to executionId, "itemSequence" to itemSequence),
        ) == 1

    override fun deletePending(key: SweepTargetKey): Boolean =
        jdbc.update(
            """
            DELETE FROM bcm_swp_trgt
            WHERE acnt_id = :accountId AND ntwk_cd = :network AND tkn_smbl = :symbol
              AND actv_swp_exec_id IS NULL AND actv_item_seq IS NULL
            """.trimIndent(),
            keyParameters(key),
        ) == 1

    private fun targetParameters(target: SweepTarget): Map<String, Any?> =
        keyParameters(target.key) +
            mapOf(
                "registeredAt" to target.registeredAt,
                "activeSweepExecutionId" to target.activeSweepExecutionId,
                "activeItemSequence" to target.activeItemSequence,
                "attemptCount" to target.attemptCount,
                "lastAttemptedAt" to target.lastAttemptedAt,
            )

    private fun keyParameters(key: SweepTargetKey): Map<String, Any?> =
        mapOf(
            "accountId" to key.accountId,
            "network" to key.network,
            "symbol" to key.symbol,
            "employeeNo" to SystemAudit.EMPNO,
            "branchCode" to SystemAudit.BRCD,
        )

    private companion object {
        val SELECT_COLUMNS =
            """
            SELECT acnt_id, ntwk_cd, tkn_smbl, reg_dttm, actv_swp_exec_id, actv_item_seq, try_cnt, last_try_dttm
            FROM bcm_swp_trgt
            """.trimIndent()

        val ROW_MAPPER =
            RowMapper { rs, _ ->
                SweepTarget(
                    accountId = rs.getString("acnt_id"),
                    network = rs.getString("ntwk_cd"),
                    symbol = rs.getString("tkn_smbl"),
                    registeredAt = rs.getString("reg_dttm"),
                    activeSweepExecutionId = rs.getString("actv_swp_exec_id"),
                    activeItemSequence = rs.getObject("actv_item_seq")?.let { rs.getInt("actv_item_seq") },
                    attemptCount = rs.getInt("try_cnt"),
                    lastAttemptedAt = rs.getString("last_try_dttm"),
                )
            }
    }
}
