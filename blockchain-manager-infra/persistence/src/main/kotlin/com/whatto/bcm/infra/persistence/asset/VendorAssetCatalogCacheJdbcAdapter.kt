package com.whatto.bcm.infra.persistence.asset

import com.whatto.bcm.domain.asset.VendorAssetCatalogCacheRepository
import com.whatto.bcm.domain.asset.VendorAssetCatalogCacheState
import com.whatto.bcm.domain.asset.VendorAssetCatalogCandidate
import com.whatto.bcm.domain.asset.VendorAssetCatalogSearchResult
import com.whatto.bcm.domain.asset.VendorAssetCatalogSnapshot
import com.whatto.bcm.domain.asset.VendorAssetCatalogSource
import com.whatto.bcm.domain.exception.ConflictException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.Locale

@Repository
class VendorAssetCatalogCacheJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : VendorAssetCatalogCacheRepository {
    @Transactional
    override fun replaceSnapshot(snapshot: VendorAssetCatalogSnapshot) {
        val assetIds = snapshot.assets.map { it.vendorAssetId }
        if (assetIds.distinct().size != assetIds.size) {
            throw ConflictException("vendorAssetCatalogSnapshot", snapshot.vendorBlockchainId)
        }
        assertAssetIdsBelongToBlockchain(snapshot.vendorBlockchainId, assetIds)
        jdbc.update(
            """
            UPDATE bcm_vndr_ast_ctlg_m
               SET prst_yn = 'N', sync_dttm = :syncedAt,
                   last_chng_empno = :empno, last_chng_brcd = :brcd
             WHERE vndr_blkc_id = :blockchainId
            """.trimIndent(),
            systemParameters(snapshot.vendorBlockchainId, snapshot.syncedAt),
        )
        if (snapshot.assets.isEmpty()) return

        try {
            val results =
                jdbc.batchUpdate(
                    """
                    INSERT INTO bcm_vndr_ast_ctlg_m
                      (vndr_ast_id, vndr_blkc_id, ast_smbl, dspl_nm, ast_clss, dcml_cnt, cntr_addr,
                       prst_yn, sync_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                    VALUES
                      (:assetId, :blockchainId, :symbol, :displayName, :assetClass, :decimals, :contractAddress,
                       'Y', :syncedAt, :empno, :brcd, :empno, :brcd)
                    ON CONFLICT (vndr_ast_id) DO UPDATE
                       SET ast_smbl = EXCLUDED.ast_smbl,
                           dspl_nm = EXCLUDED.dspl_nm,
                           ast_clss = EXCLUDED.ast_clss,
                           dcml_cnt = EXCLUDED.dcml_cnt,
                           cntr_addr = EXCLUDED.cntr_addr,
                           prst_yn = 'Y',
                           sync_dttm = EXCLUDED.sync_dttm,
                           last_chng_empno = EXCLUDED.last_chng_empno,
                           last_chng_brcd = EXCLUDED.last_chng_brcd
                     WHERE bcm_vndr_ast_ctlg_m.vndr_blkc_id = EXCLUDED.vndr_blkc_id
                    """.trimIndent(),
                    snapshot.assets
                        .map { asset ->
                            MapSqlParameterSource()
                                .addValue("assetId", asset.vendorAssetId)
                                .addValue("blockchainId", snapshot.vendorBlockchainId)
                                .addValue("symbol", asset.symbol)
                                .addValue("displayName", asset.displayName)
                                .addValue("assetClass", asset.assetClass)
                                .addValue("decimals", asset.decimals)
                                .addValue("contractAddress", asset.contractAddress)
                                .addValue("syncedAt", snapshot.syncedAt)
                                .addValue("empno", SYSTEM_EMPLOYEE)
                                .addValue("brcd", SYSTEM_BRANCH)
                        }.toTypedArray(),
                )
            if (results.any { it == 0 }) {
                throw ConflictException("vendorAssetCatalog", snapshot.vendorBlockchainId)
            }
        } catch (exception: DataIntegrityViolationException) {
            throw ConflictException("vendorAssetCatalog", snapshot.vendorBlockchainId, exception)
        }
    }

    override fun search(
        query: String,
        network: String?,
        staleBefore: String,
        limit: Int,
    ): VendorAssetCatalogSearchResult {
        val normalized = query.lowercase(Locale.ROOT)
        val prefix = escapeLike(normalized) + "%"
        val tsQuery = toPrefixTsQuery(normalized)
        val parameters =
            mutableMapOf<String, Any>(
                "query" to normalized,
                "prefix" to prefix,
                "staleBefore" to staleBefore,
                "limit" to limit,
            )
        network?.let { parameters["network"] = it }
        tsQuery?.let { parameters["tsQuery"] = it }
        val networkPredicate = if (network == null) "" else " AND b.ntwk_cd = :network"
        val textSearchPredicate =
            tsQuery
                ?.let {
                    " OR to_tsvector('simple', coalesce(c.ast_smbl, '') || ' ' || coalesce(c.dspl_nm, '')) " +
                        "@@ to_tsquery('simple', :tsQuery)"
                }.orEmpty()
        val textSearchRank =
            tsQuery
                ?.let {
                    "WHEN to_tsvector('simple', coalesce(c.ast_smbl, '') || ' ' || coalesce(c.dspl_nm, '')) " +
                        "@@ to_tsquery('simple', :tsQuery) THEN 4"
                }.orEmpty()
        val items =
            jdbc.query(
                """
                SELECT b.ntwk_cd, b.dspl_nm AS ntwk_dspl_nm, b.chain_id, b.test_yn,
                       c.vndr_ast_id, c.ast_smbl, c.dspl_nm, c.ast_clss, c.dcml_cnt,
                       c.cntr_addr, c.sync_dttm
                  FROM bcm_vndr_ast_ctlg_m c
                  JOIN bcm_blkc_m b ON b.vndr_blkc_id = c.vndr_blkc_id
                 WHERE b.ntwk_cd IS NOT NULL
                   AND c.prst_yn = 'Y'
                   $networkPredicate
                   AND (
                     lower(c.ast_smbl) = :query
                     OR lower(c.ast_smbl) LIKE :prefix ESCAPE '\'
                     OR lower(c.dspl_nm) = :query
                     OR lower(c.dspl_nm) LIKE :prefix ESCAPE '\'
                     $textSearchPredicate
                     OR lower(c.cntr_addr) = :query
                     OR lower(c.cntr_addr) LIKE :prefix ESCAPE '\'
                   )
                 ORDER BY CASE
                   WHEN lower(c.ast_smbl) = :query THEN 0
                   WHEN lower(c.ast_smbl) LIKE :prefix ESCAPE '\' THEN 1
                   WHEN lower(c.dspl_nm) = :query THEN 2
                   WHEN lower(c.dspl_nm) LIKE :prefix ESCAPE '\' THEN 3
                   $textSearchRank
                   WHEN lower(c.cntr_addr) = :query THEN 5
                   ELSE 6
                 END,
                 b.ntwk_cd, c.ast_smbl, coalesce(c.cntr_addr, '')
                 LIMIT :limit
                """.trimIndent(),
                parameters,
                candidateRowMapper,
            )
        return VendorAssetCatalogSearchResult(items, findSources(staleBefore, network))
    }

    private fun assertAssetIdsBelongToBlockchain(
        blockchainId: String,
        assetIds: List<String>,
    ) {
        if (assetIds.isEmpty()) return
        val conflict =
            jdbc
                .queryForList(
                    """
                    SELECT vndr_ast_id FROM bcm_vndr_ast_ctlg_m
                     WHERE vndr_ast_id IN (:assetIds) AND vndr_blkc_id <> :blockchainId
                     FOR UPDATE
                    """.trimIndent(),
                    mapOf("assetIds" to assetIds, "blockchainId" to blockchainId),
                    String::class.java,
                ).firstOrNull()
        if (conflict != null) throw ConflictException("vendorAssetCatalog", conflict)
    }

    private fun findSources(
        staleBefore: String,
        network: String?,
    ): List<VendorAssetCatalogSource> {
        val predicate = if (network == null) "" else " AND b.ntwk_cd = :network"
        val parameters = mutableMapOf<String, Any>("staleBefore" to staleBefore)
        network?.let { parameters["network"] = it }
        return jdbc.query(
            """
            SELECT b.ntwk_cd, max(c.sync_dttm) AS catalog_sync_dttm
              FROM bcm_blkc_m b
              LEFT JOIN bcm_vndr_ast_ctlg_m c ON c.vndr_blkc_id = b.vndr_blkc_id
             WHERE b.ntwk_cd IS NOT NULL$predicate
             GROUP BY b.ntwk_cd
             ORDER BY b.ntwk_cd
            """.trimIndent(),
            parameters,
        ) { rs, _ ->
            val syncedAt = rs.getString("catalog_sync_dttm")
            VendorAssetCatalogSource(
                network = rs.getString("ntwk_cd"),
                state =
                    when {
                        syncedAt == null -> VendorAssetCatalogCacheState.NEVER_SYNCED
                        syncedAt < staleBefore -> VendorAssetCatalogCacheState.STALE
                        else -> VendorAssetCatalogCacheState.READY
                    },
                catalogSyncedAt = syncedAt,
            )
        }
    }

    private fun systemParameters(
        blockchainId: String,
        syncedAt: String,
    ) = mapOf(
        "blockchainId" to blockchainId,
        "syncedAt" to syncedAt,
        "empno" to SYSTEM_EMPLOYEE,
        "brcd" to SYSTEM_BRANCH,
    )

    private fun escapeLike(value: String) =
        value
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")

    private fun toPrefixTsQuery(value: String): String? =
        value
            .split(NON_WORD)
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" & ") { "$it:*" }

    private val candidateRowMapper =
        RowMapper { rs, _ ->
            VendorAssetCatalogCandidate(
                network = rs.getString("ntwk_cd"),
                networkDisplayName = rs.getString("ntwk_dspl_nm"),
                chainId = rs.getLong("chain_id").let { if (rs.wasNull()) null else it },
                testnet = rs.getString("test_yn") == "Y",
                symbol = rs.getString("ast_smbl"),
                displayName = rs.getString("dspl_nm"),
                fireblocksAssetId = rs.getString("vndr_ast_id"),
                assetClass = rs.getString("ast_clss"),
                decimals = rs.getInt("dcml_cnt").let { if (rs.wasNull()) null else it },
                contractAddress = rs.getString("cntr_addr"),
                catalogSyncedAt = rs.getString("sync_dttm"),
            )
        }

    companion object {
        private const val SYSTEM_EMPLOYEE = "SYSTEM"
        private const val SYSTEM_BRANCH = "9999"
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
    }
}
