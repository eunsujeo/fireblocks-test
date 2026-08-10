package com.whatto.bcm.infra.persistence.asset

import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.asset.VendorAssetMappingRepository
import com.whatto.bcm.domain.exception.ConflictException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/** bcm_vndr_ast_m SQL 어댑터 — 복합 PK와 실제 Admin 감사 값을 보존한다. */
@Repository
class VendorAssetMappingJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : VendorAssetMappingRepository {
    private val rowMapper =
        RowMapper { rs, _ ->
            VendorAssetMapping(
                network = rs.getString("ntwk_cd"),
                symbol = rs.getString("tkn_smbl"),
                vendorAssetId = rs.getString("vndr_ast_id"),
                contractAddress = rs.getString("cntr_addr"),
                registeredAt = rs.getString("reg_dttm"),
                registeredByEmployeeNo = rs.getString("frst_reg_empno"),
                registeredByBranchCode = rs.getString("frst_reg_brcd"),
            )
        }

    override fun find(
        network: String,
        symbol: String,
    ): VendorAssetMapping? =
        jdbc
            .query(
                """
                SELECT ntwk_cd, tkn_smbl, vndr_ast_id, cntr_addr, reg_dttm,
                       frst_reg_empno, frst_reg_brcd
                  FROM bcm_vndr_ast_m
                 WHERE ntwk_cd = :ntwkCd AND tkn_smbl = :tknSmbl
                """.trimIndent(),
                keyParameters(network, symbol),
                rowMapper,
            ).firstOrNull()

    override fun findByVendorAssetId(vendorAssetId: String): VendorAssetMapping? =
        jdbc
            .query(
                """
                SELECT ntwk_cd, tkn_smbl, vndr_ast_id, cntr_addr, reg_dttm,
                       frst_reg_empno, frst_reg_brcd
                  FROM bcm_vndr_ast_m
                 WHERE vndr_ast_id = :vendorAssetId
                """.trimIndent(),
                mapOf("vendorAssetId" to vendorAssetId),
                rowMapper,
            ).firstOrNull()

    override fun findAll(
        network: String?,
        symbol: String?,
    ): List<VendorAssetMapping> {
        val predicates = mutableListOf<String>()
        val parameters = mutableMapOf<String, Any>()
        network?.let {
            predicates += "ntwk_cd = :ntwkCd"
            parameters["ntwkCd"] = it
        }
        symbol?.let {
            predicates += "tkn_smbl = :tknSmbl"
            parameters["tknSmbl"] = it
        }
        val where = predicates.takeIf { it.isNotEmpty() }?.joinToString(" AND ", prefix = " WHERE ").orEmpty()
        return jdbc.query(
            """
            SELECT ntwk_cd, tkn_smbl, vndr_ast_id, cntr_addr, reg_dttm,
                   frst_reg_empno, frst_reg_brcd
              FROM bcm_vndr_ast_m$where
             ORDER BY ntwk_cd, tkn_smbl
            """.trimIndent(),
            parameters,
            rowMapper,
        )
    }

    override fun existsByNetwork(network: String): Boolean =
        checkNotNull(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM bcm_vndr_ast_m WHERE ntwk_cd = :ntwkCd)",
                mapOf("ntwkCd" to network),
                Boolean::class.java,
            ),
        )

    override fun insert(mapping: VendorAssetMapping): VendorAssetMapping {
        try {
            jdbc.update(
                """
                INSERT INTO bcm_vndr_ast_m
                  (ntwk_cd, tkn_smbl, vndr_ast_id, cntr_addr, reg_dttm,
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES
                  (:ntwkCd, :tknSmbl, :vndrAstId, :cntrAddr, :regDttm,
                   :empno, :brcd, :empno, :brcd)
                """.trimIndent(),
                mapOf(
                    "ntwkCd" to mapping.network,
                    "tknSmbl" to mapping.symbol,
                    "vndrAstId" to mapping.vendorAssetId,
                    "cntrAddr" to mapping.contractAddress,
                    "regDttm" to mapping.registeredAt,
                    "empno" to mapping.registeredByEmployeeNo,
                    "brcd" to mapping.registeredByBranchCode,
                ),
            )
        } catch (exception: DataIntegrityViolationException) {
            if (!exception.isConstraintViolation(UNIQUE_VIOLATION, FOREIGN_KEY_VIOLATION)) throw exception
            throw ConflictException(
                resource = "vendorAssetMapping",
                key = "${mapping.network}:${mapping.symbol}:${mapping.vendorAssetId}",
                cause = exception,
            )
        }
        return mapping
    }

    override fun delete(
        network: String,
        symbol: String,
    ) {
        jdbc.update(
            "DELETE FROM bcm_vndr_ast_m WHERE ntwk_cd = :ntwkCd AND tkn_smbl = :tknSmbl",
            keyParameters(network, symbol),
        )
    }

    private fun keyParameters(
        network: String,
        symbol: String,
    ) = mapOf("ntwkCd" to network, "tknSmbl" to symbol)

    companion object {
        private const val UNIQUE_VIOLATION = "23505"
        private const val FOREIGN_KEY_VIOLATION = "23503"
    }
}
