package com.whatto.bcm.infra.persistence.asset

import com.whatto.bcm.domain.asset.VendorBlockchainCatalog
import com.whatto.bcm.domain.asset.VendorBlockchainCatalogRepository
import com.whatto.bcm.domain.exception.ConflictException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/** bcm_blkc_m SQL 어댑터 — 동기화 스냅샷과 사람의 네트워크 채택을 한 행에 보존한다. */
@Repository
class VendorBlockchainCatalogJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : VendorBlockchainCatalogRepository {
    private val rowMapper =
        RowMapper { rs, _ ->
            val chainId = rs.getLong("chain_id").let { if (rs.wasNull()) null else it }
            VendorBlockchainCatalog(
                candidateId = rs.getString("vndr_blkc_id"),
                network = rs.getString("ntwk_cd"),
                chainId = chainId,
                displayName = rs.getString("dspl_nm"),
                testnet = rs.getString("test_yn") == YES,
                deprecated = rs.getString("deprc_yn") == YES,
                syncedAt = rs.getString("sync_dttm"),
            )
        }

    override fun findAll(
        query: String?,
        chainId: Long?,
        adopted: Boolean?,
        testnet: Boolean?,
    ): List<VendorBlockchainCatalog> {
        val predicates = mutableListOf<String>()
        val parameters = mutableMapOf<String, Any>()
        query?.let {
            predicates +=
                "(POSITION(LOWER(:query) IN LOWER(dspl_nm)) > 0 " +
                "OR POSITION(LOWER(:query) IN LOWER(COALESCE(ntwk_cd, ''))) > 0)"
            parameters["query"] = it
        }
        chainId?.let {
            predicates += "chain_id = :chainId"
            parameters["chainId"] = it
        }
        adopted?.let { predicates += if (it) "ntwk_cd IS NOT NULL" else "ntwk_cd IS NULL" }
        testnet?.let {
            predicates += "test_yn = :testYn"
            parameters["testYn"] = yn(it)
        }
        val where = predicates.takeIf { it.isNotEmpty() }?.joinToString(" AND ", prefix = " WHERE ").orEmpty()
        return jdbc.query(
            """
            SELECT vndr_blkc_id, ntwk_cd, chain_id, dspl_nm, test_yn, deprc_yn, sync_dttm
              FROM bcm_blkc_m$where
             ORDER BY dspl_nm, vndr_blkc_id
            """.trimIndent(),
            parameters,
            rowMapper,
        )
    }

    override fun findByCandidateId(candidateId: String): VendorBlockchainCatalog? = findOne("vndr_blkc_id = :value", candidateId)

    override fun findByNetwork(network: String): VendorBlockchainCatalog? = findOne("ntwk_cd = :value", network)

    override fun insert(catalog: VendorBlockchainCatalog): VendorBlockchainCatalog {
        try {
            jdbc.update(
                """
                INSERT INTO bcm_blkc_m
                  (vndr_blkc_id, ntwk_cd, chain_id, dspl_nm, test_yn, deprc_yn, sync_dttm,
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES
                  (:candidateId, :network, :chainId, :displayName, :testYn, :deprecatedYn, :syncedAt,
                   :empno, :brcd, :empno, :brcd)
                """.trimIndent(),
                snapshotParameters(catalog),
            )
        } catch (exception: DuplicateKeyException) {
            throw ConflictException("vendorBlockchainCatalog", catalog.candidateId, exception)
        }
        return catalog
    }

    override fun updateSnapshot(catalog: VendorBlockchainCatalog): VendorBlockchainCatalog {
        jdbc.update(
            """
            UPDATE bcm_blkc_m
               SET chain_id = COALESCE(chain_id, :chainId),
                   dspl_nm = :displayName,
                   test_yn = :testYn,
                   deprc_yn = :deprecatedYn,
                   sync_dttm = :syncedAt,
                   last_chng_empno = :empno,
                   last_chng_brcd = :brcd
             WHERE vndr_blkc_id = :candidateId
            """.trimIndent(),
            snapshotParameters(catalog),
        )
        return checkNotNull(findByCandidateId(catalog.candidateId))
    }

    override fun adopt(
        candidateId: String,
        network: String,
        employeeNo: String,
        branchCode: String,
    ): VendorBlockchainCatalog {
        try {
            val updated =
                jdbc.update(
                    """
                    UPDATE bcm_blkc_m
                       SET ntwk_cd = :network, last_chng_empno = :empno, last_chng_brcd = :brcd
                     WHERE vndr_blkc_id = :candidateId AND ntwk_cd IS NULL
                    """.trimIndent(),
                    auditParameters(candidateId, network, employeeNo, branchCode),
                )
            if (updated == 0) {
                val current = findByCandidateId(candidateId)
                if (current?.network == network) return current
                throw ConflictException("networkCandidate", candidateId)
            }
        } catch (exception: DuplicateKeyException) {
            throw ConflictException("network", network, exception)
        }
        return checkNotNull(findByCandidateId(candidateId))
    }

    override fun release(
        network: String,
        employeeNo: String,
        branchCode: String,
    ): Boolean =
        try {
            jdbc.update(
                """
                UPDATE bcm_blkc_m
                   SET ntwk_cd = NULL, last_chng_empno = :empno, last_chng_brcd = :brcd
                 WHERE ntwk_cd = :network
                """.trimIndent(),
                mapOf("network" to network, "empno" to employeeNo, "brcd" to branchCode),
            ) > 0
        } catch (exception: DataIntegrityViolationException) {
            if (!exception.isConstraintViolation(FOREIGN_KEY_VIOLATION)) throw exception
            throw ConflictException("networkInUse", network, exception)
        }

    private fun findOne(
        predicate: String,
        value: String,
    ): VendorBlockchainCatalog? =
        jdbc
            .query(
                """
                SELECT vndr_blkc_id, ntwk_cd, chain_id, dspl_nm, test_yn, deprc_yn, sync_dttm
                  FROM bcm_blkc_m WHERE $predicate
                """.trimIndent(),
                mapOf("value" to value),
                rowMapper,
            ).firstOrNull()

    private fun snapshotParameters(catalog: VendorBlockchainCatalog) =
        mapOf(
            "candidateId" to catalog.candidateId,
            "network" to catalog.network,
            "chainId" to catalog.chainId,
            "displayName" to catalog.displayName,
            "testYn" to yn(catalog.testnet),
            "deprecatedYn" to yn(catalog.deprecated),
            "syncedAt" to catalog.syncedAt,
            "empno" to SYSTEM_EMPLOYEE,
            "brcd" to SYSTEM_BRANCH,
        )

    private fun auditParameters(
        candidateId: String,
        network: String,
        employeeNo: String,
        branchCode: String,
    ) = mapOf(
        "candidateId" to candidateId,
        "network" to network,
        "empno" to employeeNo,
        "brcd" to branchCode,
    )

    companion object {
        private const val YES = "Y"
        private const val NO = "N"
        private const val SYSTEM_EMPLOYEE = "SYSTEM"
        private const val SYSTEM_BRANCH = "9999"
        private const val FOREIGN_KEY_VIOLATION = "23503"

        private fun yn(value: Boolean) = if (value) YES else NO
    }
}
