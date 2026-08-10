package com.whatto.bcm.infra.persistence.account

import com.whatto.bcm.domain.account.DepositAddress
import com.whatto.bcm.domain.account.DepositAddressRepository
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.support.audit.SystemAudit
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

/**
 * bcm_addr_m 은 복합 PK(acnt_id, ntwk_cd, tkn_smbl) — Spring Data JDBC 가 복합 @Id 를 지원하지 않아 SQL 매핑.
 * 물리 컬럼명은 이 어댑터 안에서만 쓴다.
 */
@Repository
class DepositAddressJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : DepositAddressRepository {
    private val rowMapper =
        RowMapper { rs, _ ->
            DepositAddress(
                accountId = rs.getString("acnt_id"),
                network = rs.getString("ntwk_cd"),
                symbol = rs.getString("tkn_smbl"),
                address = rs.getString("dpst_addr"),
                registeredAt = rs.getString("reg_dttm"),
            )
        }

    override fun insert(depositAddress: DepositAddress): DepositAddress {
        try {
            insertRow(depositAddress)
        } catch (exception: DuplicateKeyException) {
            // (acnt_id, ntwk_cd, tkn_smbl) PK 경합 — 도메인 예외로 변환, 호출부가 이긴 값을 재조회한다
            throw ConflictException(
                resource = "depositAddress",
                key = "${depositAddress.accountId}:${depositAddress.network}:${depositAddress.symbol}",
                cause = exception,
            )
        }
        return depositAddress
    }

    private fun insertRow(depositAddress: DepositAddress) {
        jdbc.update(
            """
            INSERT INTO bcm_addr_m
              (acnt_id, ntwk_cd, tkn_smbl, dpst_addr, reg_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              (:acntId, :ntwkCd, :tknSmbl, :dpstAddr, :regDttm, :empno, :brcd, :empno, :brcd)
            """.trimIndent(),
            mapOf(
                "acntId" to depositAddress.accountId,
                "ntwkCd" to depositAddress.network,
                "tknSmbl" to depositAddress.symbol,
                "dpstAddr" to depositAddress.address,
                "regDttm" to depositAddress.registeredAt,
                "empno" to SystemAudit.EMPNO,
                "brcd" to SystemAudit.BRCD,
            ),
        )
    }

    override fun find(
        accountId: String,
        network: String,
        symbol: String,
    ): DepositAddress? =
        jdbc
            .query(
                "SELECT acnt_id, ntwk_cd, tkn_smbl, dpst_addr, reg_dttm FROM bcm_addr_m WHERE acnt_id = :acntId AND ntwk_cd = :ntwkCd AND tkn_smbl = :tknSmbl",
                mapOf("acntId" to accountId, "ntwkCd" to network, "tknSmbl" to symbol),
                rowMapper,
            ).firstOrNull()

    override fun findAll(
        accountId: String,
        symbol: String?,
        network: String?,
    ): List<DepositAddress> =
        jdbc.query(
            """
            SELECT acnt_id, ntwk_cd, tkn_smbl, dpst_addr, reg_dttm FROM bcm_addr_m
             WHERE acnt_id = :acntId
               AND (:tknSmbl::varchar IS NULL OR tkn_smbl = :tknSmbl)
               AND (:ntwkCd::varchar IS NULL OR ntwk_cd = :ntwkCd)
             ORDER BY ntwk_cd, tkn_smbl
            """.trimIndent(),
            mapOf("acntId" to accountId, "tknSmbl" to symbol, "ntwkCd" to network),
            rowMapper,
        )

    override fun findByAddress(
        address: String,
        network: String,
    ): DepositAddress? =
        jdbc
            .query(
                "SELECT acnt_id, ntwk_cd, tkn_smbl, dpst_addr, reg_dttm FROM bcm_addr_m WHERE dpst_addr = :dpstAddr AND ntwk_cd = :ntwkCd",
                mapOf("dpstAddr" to address, "ntwkCd" to network),
                rowMapper,
            ).firstOrNull()

    override fun existsByAsset(
        network: String,
        symbol: String,
    ): Boolean =
        checkNotNull(
            jdbc.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM bcm_addr_m WHERE ntwk_cd = :ntwkCd AND tkn_smbl = :tknSmbl)",
                mapOf("ntwkCd" to network, "tknSmbl" to symbol),
                Boolean::class.java,
            ),
        )
}
