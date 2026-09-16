package com.whatto.bcm.infra.persistence.asset

import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.asset.VendorAssetMappingRepository
import com.whatto.bcm.domain.exception.VendorAssetMappingRegistrationConflictException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** bcm_vndr_ast_m 현재 binding과 추가 전용 변경 snapshot을 한 트랜잭션으로 보존한다. */
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
                active = rs.getString("actv_yn") == "Y",
                decimals = rs.getObject("dcml_cnt", Integer::class.java)?.toInt(),
            )
        }

    override fun find(
        network: String,
        symbol: String,
    ): VendorAssetMapping? = queryOne(network, symbol, true)

    override fun findCurrent(
        network: String,
        symbol: String,
    ): VendorAssetMapping? = queryOne(network, symbol, false)

    private fun queryOne(
        network: String,
        symbol: String,
        activeOnly: Boolean,
    ): VendorAssetMapping? =
        jdbc
            .query(
                """
                SELECT ntwk_cd, tkn_smbl, vndr_ast_id, cntr_addr, dcml_cnt, actv_yn, reg_dttm,
                       frst_reg_empno, frst_reg_brcd
                  FROM bcm_vndr_ast_m
                 WHERE ntwk_cd = :ntwkCd AND tkn_smbl = :tknSmbl
                   ${if (activeOnly) "AND actv_yn = 'Y'" else ""}
                """.trimIndent(),
                keyParameters(network, symbol),
                rowMapper,
            ).firstOrNull()

    override fun findByVendorAssetId(vendorAssetId: String): VendorAssetMapping? =
        jdbc
            .query(
                """
                SELECT ntwk_cd, tkn_smbl, vndr_ast_id, cntr_addr, dcml_cnt, actv_yn, reg_dttm,
                       frst_reg_empno, frst_reg_brcd
                  FROM bcm_vndr_ast_m
                 WHERE vndr_ast_id = :vendorAssetId AND actv_yn = 'Y'
                """.trimIndent(),
                mapOf("vendorAssetId" to vendorAssetId),
                rowMapper,
            ).firstOrNull()

    override fun findAll(
        network: String?,
        symbol: String?,
    ): List<VendorAssetMapping> {
        val predicates = mutableListOf("actv_yn = 'Y'")
        val parameters = mutableMapOf<String, Any>()
        network?.let {
            predicates += "ntwk_cd = :ntwkCd"
            parameters["ntwkCd"] = it
        }
        symbol?.let {
            predicates += "tkn_smbl = :tknSmbl"
            parameters["tknSmbl"] = it
        }
        val where = predicates.joinToString(" AND ", prefix = " WHERE ")
        return jdbc.query(
            """
            SELECT ntwk_cd, tkn_smbl, vndr_ast_id, cntr_addr, dcml_cnt, actv_yn, reg_dttm,
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
                "SELECT EXISTS(SELECT 1 FROM bcm_vndr_ast_m WHERE ntwk_cd = :ntwkCd AND actv_yn = 'Y')",
                mapOf("ntwkCd" to network),
                Boolean::class.java,
            ),
        )

    @Transactional
    override fun save(
        mapping: VendorAssetMapping,
        requestId: String,
    ): VendorAssetMapping = saveOne(mapping, requestId)

    @Transactional
    override fun saveAll(
        mappings: List<VendorAssetMapping>,
        requestId: String,
    ): List<VendorAssetMapping> = mappings.map { saveOne(it, requestId) }

    private fun saveOne(
        mapping: VendorAssetMapping,
        requestId: String,
    ): VendorAssetMapping {
        val before = findCurrentForUpdate(mapping.network, mapping.symbol)
        if (before?.active == true) {
            throw VendorAssetMappingRegistrationConflictException(mapping.network, mapping.symbol)
        }
        val action =
            when {
                before == null -> "REGISTER"
                before.vendorAssetId == mapping.vendorAssetId && before.contractAddress.sameAddress(mapping.contractAddress) -> "REACTIVATE"
                else -> "REPLACE"
            }
        try {
            if (before == null) insertCurrent(mapping) else replaceCurrent(mapping)
            insertChange(before, mapping.copy(active = true), action, requestId, mapping.registeredAt)
        } catch (exception: DataIntegrityViolationException) {
            if (!exception.isConstraintViolation(UNIQUE_VIOLATION, FOREIGN_KEY_VIOLATION)) throw exception
            throw VendorAssetMappingRegistrationConflictException(
                network = mapping.network,
                symbol = mapping.symbol,
                cause = exception,
            )
        }
        return mapping.copy(active = true)
    }

    override fun insert(mapping: VendorAssetMapping): VendorAssetMapping = save(mapping, "SYSTEM_BOOTSTRAP")

    @Transactional
    override fun deactivate(
        network: String,
        symbol: String,
        employeeNo: String,
        branchCode: String,
        requestId: String,
        changedAt: String,
    ) {
        val before = findCurrentForUpdate(network, symbol) ?: return
        if (!before.active) return
        jdbc.update(
            """
            UPDATE bcm_vndr_ast_m
               SET actv_yn = 'N', last_chng_empno = :empno, last_chng_brcd = :brcd
             WHERE ntwk_cd = :ntwkCd AND tkn_smbl = :tknSmbl AND actv_yn = 'Y'
            """.trimIndent(),
            keyParameters(network, symbol) + mapOf("empno" to employeeNo, "brcd" to branchCode),
        )
        insertChange(
            before,
            before.copy(active = false),
            "DEACTIVATE",
            requestId,
            changedAt,
            employeeNo,
            branchCode,
        )
    }

    private fun findCurrentForUpdate(
        network: String,
        symbol: String,
    ): VendorAssetMapping? =
        jdbc
            .query(
                """
                SELECT ntwk_cd, tkn_smbl, vndr_ast_id, cntr_addr, dcml_cnt, actv_yn, reg_dttm,
                       frst_reg_empno, frst_reg_brcd
                  FROM bcm_vndr_ast_m
                 WHERE ntwk_cd = :ntwkCd AND tkn_smbl = :tknSmbl
                   FOR UPDATE
                """.trimIndent(),
                keyParameters(network, symbol),
                rowMapper,
            ).firstOrNull()

    private fun insertCurrent(mapping: VendorAssetMapping) {
        jdbc.update(
            """
            INSERT INTO bcm_vndr_ast_m
              (ntwk_cd, tkn_smbl, vndr_ast_id, cntr_addr, dcml_cnt, actv_yn, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (:ntwkCd, :tknSmbl, :vndrAstId, :cntrAddr, :dcmlCnt, 'Y', :regDttm,
               :empno, :brcd, :empno, :brcd)
            """.trimIndent(),
            mapping.parameters(),
        )
    }

    private fun replaceCurrent(mapping: VendorAssetMapping) {
        jdbc.update(
            """
            UPDATE bcm_vndr_ast_m
               SET vndr_ast_id = :vndrAstId, cntr_addr = :cntrAddr, dcml_cnt = :dcmlCnt, actv_yn = 'Y', reg_dttm = :regDttm,
                   last_chng_empno = :empno, last_chng_brcd = :brcd
             WHERE ntwk_cd = :ntwkCd AND tkn_smbl = :tknSmbl AND actv_yn = 'N'
            """.trimIndent(),
            mapping.parameters(),
        )
    }

    private fun insertChange(
        before: VendorAssetMapping?,
        after: VendorAssetMapping?,
        action: String,
        requestId: String,
        changedAt: String,
        employeeNo: String = requireNotNull(after ?: before).registeredByEmployeeNo,
        branchCode: String = requireNotNull(after ?: before).registeredByBranchCode,
    ) {
        val subject = requireNotNull(after ?: before)
        jdbc.update(
            """
            INSERT INTO bcm_vndr_ast_chng_l
              (chng_id, ntwk_cd, tkn_smbl, actn_dvcd, before_snps, after_snps, req_id, chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (:changeId, :ntwkCd, :tknSmbl, :action,
               CASE WHEN :hasBefore THEN jsonb_build_object(
                 'network', CAST(:beforeNetwork AS VARCHAR), 'symbol', CAST(:beforeSymbol AS VARCHAR),
                 'vendorAssetId', CAST(:beforeVendorAssetId AS VARCHAR),
                 'contractAddress', CAST(:beforeContractAddress AS VARCHAR),
                 'decimals', CAST(:beforeDecimals AS INT),
                 'activeYn', CAST(:beforeActiveYn AS VARCHAR)) ELSE NULL END,
               CASE WHEN :hasAfter THEN jsonb_build_object(
                 'network', CAST(:afterNetwork AS VARCHAR), 'symbol', CAST(:afterSymbol AS VARCHAR),
                 'vendorAssetId', CAST(:afterVendorAssetId AS VARCHAR),
                 'contractAddress', CAST(:afterContractAddress AS VARCHAR),
                 'decimals', CAST(:afterDecimals AS INT),
                 'activeYn', CAST(:afterActiveYn AS VARCHAR)) ELSE NULL END,
               :requestId, :changedAt, :empno, :brcd, :empno, :brcd)
            """.trimIndent(),
            mapOf(
                "changeId" to UUID.randomUUID().toString(),
                "ntwkCd" to subject.network,
                "tknSmbl" to subject.symbol,
                "action" to action,
                "hasBefore" to (before != null),
                "beforeNetwork" to before?.network,
                "beforeDecimals" to before?.decimals,
                "afterDecimals" to after?.decimals,
                "beforeSymbol" to before?.symbol,
                "beforeVendorAssetId" to before?.vendorAssetId,
                "beforeContractAddress" to before?.contractAddress,
                "beforeActiveYn" to before?.active.toYn(),
                "hasAfter" to (after != null),
                "afterNetwork" to after?.network,
                "afterSymbol" to after?.symbol,
                "afterVendorAssetId" to after?.vendorAssetId,
                "afterContractAddress" to after?.contractAddress,
                "afterActiveYn" to after?.active.toYn(),
                "requestId" to requestId,
                "changedAt" to changedAt,
                "empno" to employeeNo,
                "brcd" to branchCode,
            ),
        )
    }

    private fun VendorAssetMapping.parameters() =
        mapOf(
            "ntwkCd" to network,
            "tknSmbl" to symbol,
            "vndrAstId" to vendorAssetId,
            "cntrAddr" to contractAddress,
            "dcmlCnt" to decimals,
            "regDttm" to registeredAt,
            "empno" to registeredByEmployeeNo,
            "brcd" to registeredByBranchCode,
        )

    private fun keyParameters(
        network: String,
        symbol: String,
    ) = mapOf("ntwkCd" to network, "tknSmbl" to symbol)

    private fun String?.sameAddress(other: String?): Boolean =
        if (this == null || other == null) this == other else equals(other, ignoreCase = true)

    private fun Boolean?.toYn(): String? = this?.let { if (it) "Y" else "N" }

    companion object {
        private const val UNIQUE_VIOLATION = "23505"
        private const val FOREIGN_KEY_VIOLATION = "23503"
    }
}
