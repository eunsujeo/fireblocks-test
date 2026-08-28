package com.whatto.bcm.infra.persistence.sweep

import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.sweep.SweepNoSweepRequiredCompletion
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

    override fun findPending(
        networks: Set<String>,
        limit: Int,
    ): List<SweepTarget> {
        require(limit > 0) { "sweep target query limit must be positive: limit=$limit" }
        if (networks.isEmpty()) return emptyList()
        return jdbc.query(
            """
            SELECT target.acnt_id, target.ntwk_cd, target.tkn_smbl, target.reg_dttm,
                   target.actv_swp_exec_id, target.actv_item_seq, target.try_cnt, target.last_try_dttm,
                   pending.swp_req_item_id, pending.swp_req_id, pending.req_dttm
            FROM bcm_swp_trgt target
            JOIN LATERAL (
              SELECT item.swp_req_item_id, request.swp_req_id, request.req_dttm
              FROM bcm_swp_req_item_l item
              JOIN bcm_swp_req_l request ON request.swp_req_id = item.swp_req_id
              WHERE item.acnt_id = target.acnt_id
                AND request.ntwk_cd = target.ntwk_cd
                AND request.tkn_smbl = target.tkn_smbl
                AND item.swp_req_item_stcd = 'PENDING'
                AND request.swp_req_stcd IN ('ACCEPTED', 'BLOCKED', 'PROCESSING', 'PARTIAL')
              ORDER BY request.req_dttm, request.swp_req_id, item.item_seq
              LIMIT 1
            ) pending ON TRUE
            WHERE target.actv_swp_exec_id IS NULL AND target.actv_item_seq IS NULL
              AND target.ntwk_cd IN (:networks)
            ORDER BY pending.req_dttm, pending.swp_req_id, target.reg_dttm,
                     target.acnt_id, target.ntwk_cd, target.tkn_smbl
            LIMIT :limit
            """.trimIndent(),
            mapOf("networks" to networks, "limit" to limit),
            PENDING_ROW_MAPPER,
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

    override fun hasUnfinishedRequest(key: SweepTargetKey): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1
              FROM bcm_swp_req_item_l item
              JOIN bcm_swp_req_l request ON request.swp_req_id = item.swp_req_id
              WHERE item.acnt_id = :accountId
                AND request.ntwk_cd = :network
                AND request.tkn_smbl = :symbol
                AND item.swp_req_item_stcd IN ('PENDING', 'PROCESSING')
                AND request.swp_req_stcd IN ('ACCEPTED', 'BLOCKED', 'PROCESSING', 'PARTIAL')
            )
            """.trimIndent(),
            keyParameters(key),
            Boolean::class.java,
        ) == true

    override fun completeOldestPendingWithoutExecution(
        key: SweepTargetKey,
        completedAt: String,
    ): SweepNoSweepRequiredCompletion? {
        val completion =
            jdbc
                .query(
                    """
                    SELECT request.swp_req_id, item.swp_req_item_id
                    FROM bcm_swp_req_item_l item
                    JOIN bcm_swp_req_l request ON request.swp_req_id = item.swp_req_id
                    WHERE item.acnt_id = :accountId
                      AND request.ntwk_cd = :network
                      AND request.tkn_smbl = :symbol
                      AND item.swp_req_item_stcd = 'PENDING'
                      AND request.swp_req_stcd IN ('ACCEPTED', 'BLOCKED', 'PROCESSING', 'PARTIAL')
                    ORDER BY request.req_dttm, request.swp_req_id, item.item_seq
                    LIMIT 1
                    FOR UPDATE OF request, item
                    """.trimIndent(),
                    keyParameters(key),
                ) { rs, _ ->
                    SweepNoSweepRequiredCompletion(
                        sweepRequestId = rs.getString("swp_req_id"),
                        sweepRequestItemId = rs.getString("swp_req_item_id"),
                    )
                }.firstOrNull() ?: return null
        val itemUpdated =
            jdbc.update(
                """
                UPDATE bcm_swp_req_item_l
                SET swp_req_item_stcd = 'COMPLETED',
                    last_fail_cd = NULL,
                    last_chng_empno = :employeeNo,
                    last_chng_brcd = :branchCode
                WHERE swp_req_item_id = :requestItemId AND swp_req_item_stcd = 'PENDING'
                """.trimIndent(),
                keyParameters(key) + mapOf("requestItemId" to completion.sweepRequestItemId),
            )
        if (itemUpdated != 1) throw ConflictException("sweepRequestItem", completion.sweepRequestItemId)
        refreshRequestStatus(completion.sweepRequestId, completedAt)
        return completion
    }

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

    private fun refreshRequestStatus(
        requestId: String,
        completedAt: String,
    ) {
        jdbc.update(
            """
            UPDATE bcm_swp_req_l request
            SET swp_req_stcd = status.next_status,
                fnsh_dttm = CASE WHEN status.next_status = 'COMPLETED' THEN :completedAt ELSE NULL END,
                last_chng_empno = :employeeNo,
                last_chng_brcd = :branchCode
            FROM (
              SELECT swp_req_id,
                     CASE
                       WHEN bool_and(swp_req_item_stcd = 'COMPLETED') THEN 'COMPLETED'
                       WHEN bool_or(swp_req_item_stcd = 'PROCESSING') THEN 'PROCESSING'
                       WHEN bool_or(swp_req_item_stcd = 'COMPLETED') THEN 'PARTIAL'
                       ELSE 'ACCEPTED'
                     END AS next_status
              FROM bcm_swp_req_item_l
              WHERE swp_req_id = :requestId
              GROUP BY swp_req_id
            ) status
            WHERE request.swp_req_id = status.swp_req_id
            """.trimIndent(),
            auditParameters() + mapOf("requestId" to requestId, "completedAt" to completedAt),
        )
    }

    private fun keyParameters(key: SweepTargetKey): Map<String, Any?> =
        mapOf(
            "accountId" to key.accountId,
            "network" to key.network,
            "symbol" to key.symbol,
            "employeeNo" to SystemAudit.EMPNO,
            "branchCode" to SystemAudit.BRCD,
        )

    private fun auditParameters(): Map<String, Any> = mapOf("employeeNo" to SystemAudit.EMPNO, "branchCode" to SystemAudit.BRCD)

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
        val PENDING_ROW_MAPPER =
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
                    pendingSweepRequestItemId = rs.getString("swp_req_item_id"),
                    pendingSweepRequestId = rs.getString("swp_req_id"),
                    pendingSweepRequestedAt = rs.getString("req_dttm"),
                )
            }
    }
}
