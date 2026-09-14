package com.whatto.bcm.infra.persistence.admin

import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.admin.VaultReconciliation
import com.whatto.bcm.domain.admin.VaultReconciliationFailureException
import com.whatto.bcm.domain.admin.VaultReconciliationItem
import com.whatto.bcm.domain.admin.VaultReconciliationItemStatus
import com.whatto.bcm.domain.admin.VaultReconciliationPage
import com.whatto.bcm.domain.admin.VaultReconciliationRepository
import com.whatto.bcm.domain.admin.VaultReconciliationStatus
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.vendor.VendorVault
import com.whatto.bcm.support.audit.SystemAudit
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet

@Repository
class VaultReconciliationJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : VaultReconciliationRepository {
    override fun create(run: VaultReconciliation): VaultReconciliation {
        try {
            jdbc.update(
                """
                INSERT INTO bcm_vlt_rcnc_l
                  (vlt_rcnc_id, qry_vl, vlt_rcnc_stcd, vndr_crsr, vndr_done_yn, vndr_page_cnt, vndr_vlt_cnt, rslt_cnt,
                   fail_cd, strt_dttm, fnsh_dttm, reg_dttm, last_chng_dttm,
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES
                  (:runId, :query, :status, NULL, 'N', 0, 0, 0, NULL, NULL, NULL, :requestedAt, :requestedAt,
                   :employeeNo, :branchCode, :employeeNo, :branchCode)
                """.trimIndent(),
                mapOf(
                    "runId" to run.runId,
                    "query" to run.query,
                    "status" to run.status.name,
                    "requestedAt" to run.requestedAt,
                    "employeeNo" to SystemAudit.EMPNO,
                    "branchCode" to SystemAudit.BRCD,
                ),
            )
        } catch (exception: DuplicateKeyException) {
            throw ConflictException("activeVaultReconciliation", run.runId, exception)
        }
        return run
    }

    override fun claim(
        runId: String,
        claimId: String,
        claimedAt: String,
        expiresAt: String,
    ): Boolean =
        jdbc.update(
            """
            UPDATE bcm_vlt_rcnc_l
               SET wrkr_clm_id = :claimId, clm_expires_dttm = :expiresAt, last_chng_dttm = :claimedAt
             WHERE vlt_rcnc_id = :runId
               AND vlt_rcnc_stcd IN ('ACCEPTED', 'RUNNING')
               AND (wrkr_clm_id IS NULL OR clm_expires_dttm <= :claimedAt)
            """.trimIndent(),
            mapOf("runId" to runId, "claimId" to claimId, "claimedAt" to claimedAt, "expiresAt" to expiresAt),
        ) == 1

    override fun renewClaim(
        runId: String,
        claimId: String,
        renewedAt: String,
        expiresAt: String,
    ): Boolean =
        jdbc.update(
            """
            UPDATE bcm_vlt_rcnc_l
               SET clm_expires_dttm = :expiresAt, last_chng_dttm = :renewedAt
             WHERE vlt_rcnc_id = :runId
               AND vlt_rcnc_stcd IN ('ACCEPTED', 'RUNNING')
               AND wrkr_clm_id = :claimId
            """.trimIndent(),
            mapOf("runId" to runId, "claimId" to claimId, "renewedAt" to renewedAt, "expiresAt" to expiresAt),
        ) == 1

    @Transactional
    override fun startWithAccountSnapshot(
        runId: String,
        claimId: String,
        startedAt: String,
    ): Boolean {
        val run = lock(runId) ?: return false
        if (!ownsClaim(runId, claimId)) return false
        if (run.status == VaultReconciliationStatus.RUNNING) return true
        if (run.status != VaultReconciliationStatus.ACCEPTED) return false
        val hasLogicalAccounts =
            jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM bcm_acnt_m WHERE acnt_mdl = 'LOGICAL')",
                emptyMap<String, Any>(),
                Boolean::class.java,
            ) == true
        if (hasLogicalAccounts) throw ConflictException("vaultReconciliationAccountModel", runId)
        jdbc.update(
            """
            INSERT INTO bcm_vlt_rcnc_item_l
              (vlt_rcnc_id, item_key, item_seq, rcnc_stcd, acnt_id, acnt_typ_dvcd, ref,
               vndr_vlt_id, vndr_vlt_nm, wllt_cnt, acnt_reg_dttm, reg_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            SELECT :runId, 'A:' || acnt_id, NULL, 'PENDING', acnt_id, acnt_typ_dvcd, ref,
                   vndr_vlt_id, NULL, NULL, reg_dttm, :startedAt, :startedAt,
                   :employeeNo, :branchCode, :employeeNo, :branchCode
              FROM bcm_acnt_m
            """.trimIndent(),
            auditParameters(runId, startedAt) + mapOf("startedAt" to startedAt),
        )
        jdbc.update(
            """
            UPDATE bcm_vlt_rcnc_l
               SET vlt_rcnc_stcd = 'RUNNING',
                   strt_dttm = :startedAt,
                   last_chng_dttm = :startedAt
             WHERE vlt_rcnc_id = :runId
               AND vlt_rcnc_stcd = 'ACCEPTED'
            """.trimIndent(),
            mapOf("runId" to runId, "startedAt" to startedAt),
        )
        return true
    }

    override fun hasDuplicateVendorVaultMapping(runId: String): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1
                FROM bcm_vlt_rcnc_item_l
               WHERE vlt_rcnc_id = :runId AND acnt_id IS NOT NULL
               GROUP BY vndr_vlt_id
              HAVING COUNT(*) > 1
            )
            """.trimIndent(),
            mapOf("runId" to runId),
            Boolean::class.java,
        ) == true

    @Transactional
    override fun recordVendorPage(
        runId: String,
        claimId: String,
        cursor: String?,
        nextCursor: String?,
        vaults: List<VendorVault>,
        recordedAt: String,
    ): Boolean {
        val run = lock(runId) ?: return false
        if (run.status != VaultReconciliationStatus.RUNNING || run.vendorCursor != cursor || !ownsClaim(runId, claimId)) {
            return false
        }
        recordVendorVaults(runId, vaults, recordedAt)
        jdbc.update(
            """
            UPDATE bcm_vlt_rcnc_l
               SET vndr_crsr = :nextCursor,
                   vndr_done_yn = :paginationDone,
                   vndr_page_cnt = vndr_page_cnt + 1,
                   vndr_vlt_cnt = vndr_vlt_cnt + :vaultCount,
                   last_chng_dttm = :recordedAt
             WHERE vlt_rcnc_id = :runId
            """.trimIndent(),
            mapOf(
                "runId" to runId,
                "nextCursor" to nextCursor,
                "paginationDone" to if (nextCursor == null) "Y" else "N",
                "vaultCount" to vaults.size,
                "recordedAt" to recordedAt,
            ),
        )
        return true
    }

    @Transactional
    override fun complete(
        runId: String,
        claimId: String,
        finishedAt: String,
    ): Boolean {
        val run = lock(runId) ?: return false
        if (run.status != VaultReconciliationStatus.RUNNING || !run.vendorPaginationDone || !ownsClaim(runId, claimId)) {
            return false
        }
        jdbc.update(
            """
            UPDATE bcm_vlt_rcnc_item_l
               SET rcnc_stcd = 'MISSING_IN_FIREBLOCKS', last_chng_dttm = :finishedAt
             WHERE vlt_rcnc_id = :runId AND rcnc_stcd = 'PENDING'
            """.trimIndent(),
            mapOf("runId" to runId, "finishedAt" to finishedAt),
        )
        finish(runId, VaultReconciliationStatus.COMPLETED, null, finishedAt)
        return true
    }

    @Transactional
    override fun fail(
        runId: String,
        claimId: String,
        failureCode: String,
        finishedAt: String,
    ): Boolean {
        val run = lock(runId) ?: return false
        if (run.status !in setOf(VaultReconciliationStatus.ACCEPTED, VaultReconciliationStatus.RUNNING) ||
            !ownsClaim(runId, claimId)
        ) {
            return false
        }
        val visibleCount = rankVisibleItems(runId)
        val status = run.failureStatus()
        jdbc.update(
            """
            UPDATE bcm_vlt_rcnc_l
               SET vlt_rcnc_stcd = :status, rslt_cnt = :resultCount, fail_cd = :failureCode,
                   wrkr_clm_id = NULL, clm_expires_dttm = NULL,
                   fnsh_dttm = :finishedAt, last_chng_dttm = :finishedAt
             WHERE vlt_rcnc_id = :runId AND vlt_rcnc_stcd IN ('ACCEPTED', 'RUNNING')
            """.trimIndent(),
            mapOf(
                "runId" to runId,
                "status" to status.name,
                "resultCount" to visibleCount,
                "failureCode" to failureCode,
                "finishedAt" to finishedAt,
            ),
        )
        return true
    }

    override fun failAccepted(
        runId: String,
        failureCode: String,
        finishedAt: String,
    ) {
        jdbc.update(
            """
            UPDATE bcm_vlt_rcnc_l
               SET vlt_rcnc_stcd = 'FAILED', rslt_cnt = 0, fail_cd = :failureCode,
                   fnsh_dttm = :finishedAt, last_chng_dttm = :finishedAt
             WHERE vlt_rcnc_id = :runId AND vlt_rcnc_stcd = 'ACCEPTED' AND wrkr_clm_id IS NULL
            """.trimIndent(),
            mapOf("runId" to runId, "failureCode" to failureCode, "finishedAt" to finishedAt),
        )
    }

    override fun find(runId: String): VaultReconciliation? =
        jdbc.query("$RUN_SELECT WHERE vlt_rcnc_id = :runId", mapOf("runId" to runId)) { rs, _ -> rs.toRun() }.firstOrNull()

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun findPage(
        runId: String,
        afterSequence: Long?,
        limit: Int,
    ): VaultReconciliationPage? {
        val run = find(runId) ?: return null
        val fetched =
            jdbc.query(
                """
                SELECT item_seq, rcnc_stcd, acnt_id, acnt_typ_dvcd, ref, vndr_vlt_id,
                       vndr_vlt_nm, wllt_cnt, acnt_reg_dttm
                  FROM bcm_vlt_rcnc_item_l
                 WHERE vlt_rcnc_id = :runId
                   AND item_seq IS NOT NULL
                   AND item_seq > COALESCE(:afterSequence, 0)
                 ORDER BY item_seq
                 LIMIT :fetchLimit
                """.trimIndent(),
                mapOf("runId" to runId, "afterSequence" to afterSequence, "fetchLimit" to limit + 1),
            ) { rs, _ -> rs.toItem() }
        val items = fetched.take(limit)
        return VaultReconciliationPage(run, items, items.lastOrNull()?.sequence?.takeIf { fetched.size > limit })
    }

    override fun findResumable(): List<VaultReconciliation> =
        jdbc.query(
            "$RUN_SELECT WHERE vlt_rcnc_stcd IN ('ACCEPTED', 'RUNNING') ORDER BY reg_dttm, vlt_rcnc_id",
            emptyMap<String, Any>(),
        ) { rs, _ -> rs.toRun() }

    private fun recordVendorVaults(
        runId: String,
        vaults: List<VendorVault>,
        recordedAt: String,
    ) {
        if (vaults.isEmpty()) return
        if (vaults.map(VendorVault::vaultId).toSet().size != vaults.size) {
            throw VaultReconciliationFailureException("VENDOR_VAULT_DUPLICATED")
        }
        val vaultIds = vaults.map(VendorVault::vaultId)
        val alreadySeen =
            jdbc.queryForObject(
                """
                SELECT EXISTS (
                  SELECT 1 FROM bcm_vlt_rcnc_item_l
                   WHERE vlt_rcnc_id = :runId AND vndr_vlt_id IN (:vaultIds)
                     AND rcnc_stcd IN ('MANAGED', 'UNMANAGED')
                )
                """.trimIndent(),
                mapOf("runId" to runId, "vaultIds" to vaultIds),
                Boolean::class.java,
            ) == true
        if (alreadySeen) throw VaultReconciliationFailureException("VENDOR_VAULT_DUPLICATED")

        val parameters =
            vaults
                .map { vault ->
                    MapSqlParameterSource(
                        auditParameters(runId, recordedAt) +
                            mapOf("vaultId" to vault.vaultId, "vaultName" to vault.name, "walletCount" to vault.walletCount),
                    )
                }.toTypedArray()
        val updated =
            jdbc.batchUpdate(
                """
                UPDATE bcm_vlt_rcnc_item_l
                   SET rcnc_stcd = 'MANAGED', vndr_vlt_nm = :vaultName, wllt_cnt = :walletCount,
                       last_chng_dttm = :recordedAt
                 WHERE vlt_rcnc_id = :runId AND vndr_vlt_id = :vaultId
                   AND acnt_id IS NOT NULL AND rcnc_stcd = 'PENDING'
                """.trimIndent(),
                parameters,
            )
        val unmanaged = parameters.filterIndexed { index, _ -> updated[index] == 0 }.toTypedArray()
        if (unmanaged.isEmpty()) return
        jdbc.batchUpdate(
            """
            INSERT INTO bcm_vlt_rcnc_item_l
              (vlt_rcnc_id, item_key, item_seq, rcnc_stcd, acnt_id, acnt_typ_dvcd, ref,
               vndr_vlt_id, vndr_vlt_nm, wllt_cnt, acnt_reg_dttm, reg_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (:runId, 'V:' || :vaultId, NULL, 'UNMANAGED', NULL, NULL, NULL,
               :vaultId, :vaultName, :walletCount, NULL, :recordedAt, :recordedAt,
               :employeeNo, :branchCode, :employeeNo, :branchCode)
            """.trimIndent(),
            unmanaged,
        )
    }

    private fun finish(
        runId: String,
        status: VaultReconciliationStatus,
        failureCode: String?,
        finishedAt: String,
    ) {
        val resultCount = rankVisibleItems(runId)
        jdbc.update(
            """
            UPDATE bcm_vlt_rcnc_l
               SET vlt_rcnc_stcd = :status, rslt_cnt = :resultCount, fail_cd = :failureCode,
                   wrkr_clm_id = NULL, clm_expires_dttm = NULL,
                   fnsh_dttm = :finishedAt, last_chng_dttm = :finishedAt
             WHERE vlt_rcnc_id = :runId AND vlt_rcnc_stcd = 'RUNNING'
            """.trimIndent(),
            mapOf(
                "runId" to runId,
                "status" to status.name,
                "resultCount" to resultCount,
                "failureCode" to failureCode,
                "finishedAt" to finishedAt,
            ),
        )
    }

    private fun rankVisibleItems(runId: String): Long {
        jdbc.update(
            "UPDATE bcm_vlt_rcnc_item_l SET item_seq = NULL WHERE vlt_rcnc_id = :runId",
            mapOf("runId" to runId),
        )
        jdbc.update(
            """
            WITH ranked AS (
              SELECT item.item_key,
                     ROW_NUMBER() OVER (
                       ORDER BY CASE item.rcnc_stcd
                                  WHEN 'MANAGED' THEN 0
                                  WHEN 'UNMANAGED' THEN 1
                                  ELSE 2
                                END,
                                LOWER(COALESCE(item.vndr_vlt_nm, '')),
                                item.vndr_vlt_id,
                                item.item_key
                     ) AS sequence
                FROM bcm_vlt_rcnc_item_l item
                JOIN bcm_vlt_rcnc_l run ON run.vlt_rcnc_id = item.vlt_rcnc_id
               WHERE item.vlt_rcnc_id = :runId
                 AND item.rcnc_stcd <> 'PENDING'
                 AND (
                   run.qry_vl IS NULL OR
                   POSITION(LOWER(run.qry_vl) IN LOWER(COALESCE(item.acnt_id, ''))) > 0 OR
                   POSITION(LOWER(run.qry_vl) IN LOWER(COALESCE(item.ref, ''))) > 0 OR
                   POSITION(LOWER(run.qry_vl) IN LOWER(item.vndr_vlt_id)) > 0 OR
                   POSITION(LOWER(run.qry_vl) IN LOWER(COALESCE(item.vndr_vlt_nm, ''))) > 0
                 )
            )
            UPDATE bcm_vlt_rcnc_item_l item
               SET item_seq = ranked.sequence
              FROM ranked
             WHERE item.vlt_rcnc_id = :runId AND item.item_key = ranked.item_key
            """.trimIndent(),
            mapOf("runId" to runId),
        )
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM bcm_vlt_rcnc_item_l WHERE vlt_rcnc_id = :runId AND item_seq IS NOT NULL",
            mapOf("runId" to runId),
            Long::class.java,
        ) ?: 0
    }

    private fun lock(runId: String): VaultReconciliation? =
        jdbc.query("$RUN_SELECT WHERE vlt_rcnc_id = :runId FOR UPDATE", mapOf("runId" to runId)) { rs, _ -> rs.toRun() }.firstOrNull()

    private fun ownsClaim(
        runId: String,
        claimId: String,
    ): Boolean =
        jdbc.queryForObject(
            "SELECT COUNT(*) FROM bcm_vlt_rcnc_l WHERE vlt_rcnc_id = :runId AND wrkr_clm_id = :claimId",
            mapOf("runId" to runId, "claimId" to claimId),
            Int::class.java,
        ) == 1

    private fun auditParameters(
        runId: String,
        recordedAt: String,
    ): Map<String, Any> =
        mapOf(
            "runId" to runId,
            "recordedAt" to recordedAt,
            "employeeNo" to SystemAudit.EMPNO,
            "branchCode" to SystemAudit.BRCD,
        )

    private fun ResultSet.toRun() =
        VaultReconciliation(
            runId = getString("vlt_rcnc_id"),
            query = getString("qry_vl"),
            status = VaultReconciliationStatus.valueOf(getString("vlt_rcnc_stcd")),
            vendorPageCount = getInt("vndr_page_cnt"),
            vendorVaultCount = getLong("vndr_vlt_cnt"),
            resultCount = getLong("rslt_cnt"),
            failureCode = getString("fail_cd"),
            requestedAt = getString("reg_dttm"),
            startedAt = getString("strt_dttm"),
            finishedAt = getString("fnsh_dttm"),
            vendorCursor = getString("vndr_crsr"),
            vendorPaginationDone = getString("vndr_done_yn") == "Y",
        )

    private fun ResultSet.toItem() =
        VaultReconciliationItem(
            sequence = getLong("item_seq"),
            reconciliationStatus = VaultReconciliationItemStatus.valueOf(getString("rcnc_stcd")),
            accountId = getString("acnt_id"),
            accountType =
                getString("acnt_typ_dvcd")?.let {
                    when (it) {
                        "CU" -> AccountType.CUSTOMER
                        "SY" -> AccountType.SYSTEM
                        else -> error("unknown account type code")
                    }
                },
            ref = getString("ref"),
            vendorVaultId = getString("vndr_vlt_id"),
            vendorVaultName = getString("vndr_vlt_nm"),
            walletCount = (getObject("wllt_cnt") as? Number)?.toInt(),
            registeredAt = getString("acnt_reg_dttm"),
        )

    companion object {
        private const val RUN_SELECT =
            """SELECT vlt_rcnc_id, qry_vl, vlt_rcnc_stcd, vndr_crsr, vndr_done_yn, vndr_page_cnt, vndr_vlt_cnt,
                      rslt_cnt, fail_cd, strt_dttm, fnsh_dttm, reg_dttm
                 FROM bcm_vlt_rcnc_l"""
    }
}
