package com.whatto.bcm.infra.persistence.sweep

import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import com.whatto.bcm.domain.sweep.SweepAuthorization
import com.whatto.bcm.domain.sweep.SweepAuthorizationKey
import com.whatto.bcm.domain.sweep.SweepAuthorizationRepository
import com.whatto.bcm.domain.sweep.SweepAuthorizationStatus
import com.whatto.bcm.support.audit.SystemAudit
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.math.BigDecimal

@Repository
class SweepAuthorizationJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : SweepAuthorizationRepository {
    override fun insert(authorization: SweepAuthorization): SweepAuthorization {
        try {
            jdbc.update(
                """
                INSERT INTO bcm_swp_auth_m
                  (acnt_id, ntwk_cd, tkn_smbl, swp_ctrt_addr, alwnc_cap, obs_alwnc, auth_stcd,
                   aprv_ext_tx_id, aprv_vndr_tx_id, last_chck_dttm,
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES
                  (:accountId, :network, :symbol, :sweepContractAddress, :allowanceCap, :observedAllowance, :status,
                   :approvalExternalTransactionId, :approvalVendorTransactionId, :lastCheckedAt,
                   :employeeNo, :branchCode, :employeeNo, :branchCode)
                """.trimIndent(),
                parameters(authorization),
            )
        } catch (exception: DuplicateKeyException) {
            throw ConflictException("sweepAuthorization", authorization.key.toString(), exception)
        }
        return requireNotNull(findByKey(authorization.key))
    }

    override fun findByKey(key: SweepAuthorizationKey): SweepAuthorization? = query(key, forUpdate = false)

    override fun findByKeyForUpdate(key: SweepAuthorizationKey): SweepAuthorization? = query(key, forUpdate = true)

    override fun findByNetworkAndContract(
        network: String,
        sweepContractAddress: String,
    ): List<SweepAuthorization> =
        jdbc.query(
            "$SELECT_COLUMNS WHERE ntwk_cd = :network AND lower(swp_ctrt_addr) = lower(:sweepContractAddress) " +
                "ORDER BY acnt_id, ntwk_cd, tkn_smbl, swp_ctrt_addr",
            mapOf("network" to network, "sweepContractAddress" to sweepContractAddress),
            ROW_MAPPER,
        )

    override fun update(authorization: SweepAuthorization): SweepAuthorization {
        val updated =
            jdbc.update(
                """
                UPDATE bcm_swp_auth_m
                SET alwnc_cap = :allowanceCap,
                    obs_alwnc = :observedAllowance,
                    auth_stcd = :status,
                    aprv_ext_tx_id = :approvalExternalTransactionId,
                    aprv_vndr_tx_id = :approvalVendorTransactionId,
                    last_chck_dttm = :lastCheckedAt,
                    last_chng_empno = :employeeNo,
                    last_chng_brcd = :branchCode
                WHERE acnt_id = :accountId AND ntwk_cd = :network AND tkn_smbl = :symbol
                  AND swp_ctrt_addr = :sweepContractAddress
                """.trimIndent(),
                parameters(authorization),
            )
        if (updated != 1) throw ResourceNotFoundException("sweepAuthorization", authorization.key.toString())
        return requireNotNull(findByKey(authorization.key))
    }

    private fun query(
        key: SweepAuthorizationKey,
        forUpdate: Boolean,
    ): SweepAuthorization? =
        jdbc
            .query(
                "$SELECT_COLUMNS WHERE acnt_id = :accountId AND ntwk_cd = :network AND tkn_smbl = :symbol " +
                    "AND swp_ctrt_addr = :sweepContractAddress" + if (forUpdate) " FOR UPDATE" else "",
                keyParameters(key),
                ROW_MAPPER,
            ).firstOrNull()

    private fun parameters(authorization: SweepAuthorization): Map<String, Any?> =
        keyParameters(authorization.key) +
            mapOf(
                "allowanceCap" to BigDecimal(authorization.allowanceCap),
                "observedAllowance" to BigDecimal(authorization.observedAllowance),
                "status" to authorization.status.name,
                "approvalExternalTransactionId" to authorization.approvalExternalTransactionId,
                "approvalVendorTransactionId" to authorization.approvalVendorTransactionId,
                "lastCheckedAt" to authorization.lastCheckedAt,
            )

    private fun keyParameters(key: SweepAuthorizationKey): Map<String, Any?> =
        mapOf(
            "accountId" to key.accountId,
            "network" to key.network,
            "symbol" to key.symbol,
            "sweepContractAddress" to key.sweepContractAddress,
            "employeeNo" to SystemAudit.EMPNO,
            "branchCode" to SystemAudit.BRCD,
        )

    private companion object {
        const val SELECT_COLUMNS =
            """SELECT acnt_id, ntwk_cd, tkn_smbl, swp_ctrt_addr, alwnc_cap, obs_alwnc, auth_stcd,
                      aprv_ext_tx_id, aprv_vndr_tx_id, last_chck_dttm
               FROM bcm_swp_auth_m"""
        val ROW_MAPPER =
            RowMapper { rs, _ ->
                SweepAuthorization(
                    key =
                        SweepAuthorizationKey(
                            accountId = rs.getString("acnt_id"),
                            network = rs.getString("ntwk_cd"),
                            symbol = rs.getString("tkn_smbl"),
                            sweepContractAddress = rs.getString("swp_ctrt_addr"),
                        ),
                    allowanceCap = rs.getBigDecimal("alwnc_cap").stripTrailingZeros().toPlainString(),
                    observedAllowance = rs.getBigDecimal("obs_alwnc").stripTrailingZeros().toPlainString(),
                    status = SweepAuthorizationStatus.valueOf(rs.getString("auth_stcd")),
                    approvalExternalTransactionId = rs.getString("aprv_ext_tx_id"),
                    approvalVendorTransactionId = rs.getString("aprv_vndr_tx_id"),
                    lastCheckedAt = rs.getString("last_chck_dttm"),
                )
            }
    }
}
