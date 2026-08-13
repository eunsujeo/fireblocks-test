package com.whatto.bcm.infra.persistence.fee

import com.whatto.bcm.domain.fee.NetworkFeeQuote
import com.whatto.bcm.domain.fee.NetworkFeeQuoteRepository
import com.whatto.bcm.domain.vendor.VendorFeeLevel
import com.whatto.bcm.support.audit.SystemAudit
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class NetworkFeeQuoteJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : NetworkFeeQuoteRepository {
    private val rowMapper =
        RowMapper { rs, _ ->
            NetworkFeeQuote(
                network = rs.getString("ntwk_cd"),
                symbol = rs.getString("tkn_smbl"),
                observedAt = rs.getString("obs_dttm"),
                feeLevel = VendorFeeLevel.valueOf(rs.getString("fee_lvl")),
                vendorAssetId = rs.getString("vndr_ast_id"),
                feePerByte = rs.getBigDecimal("fee_per_byte"),
                gasPrice = rs.getBigDecimal("gas_price"),
                networkFee = rs.getBigDecimal("ntwk_fee"),
                baseFee = rs.getBigDecimal("base_fee"),
                priorityFee = rs.getBigDecimal("priority_fee"),
            )
        }

    override fun saveAll(quotes: List<NetworkFeeQuote>): Int =
        quotes.sumOf { quote ->
            jdbc.update(
                """
                INSERT INTO bcm_fee_qt_l
                  (ntwk_cd, tkn_smbl, obs_dttm, fee_lvl, vndr_ast_id,
                   fee_per_byte, gas_price, ntwk_fee, base_fee, priority_fee,
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES
                  (:network, :symbol, :observedAt, :feeLevel, :vendorAssetId,
                   :feePerByte, :gasPrice, :networkFee, :baseFee, :priorityFee,
                   :employeeNo, :branchCode, :employeeNo, :branchCode)
                ON CONFLICT (ntwk_cd, tkn_smbl, obs_dttm, fee_lvl) DO NOTHING
                """.trimIndent(),
                parameters(quote),
            )
        }

    override fun findLatestAtOrBefore(
        network: String,
        symbol: String,
        feeLevel: VendorFeeLevel,
        requestedAt: String,
    ): NetworkFeeQuote? =
        jdbc
            .query(
                """
                SELECT ntwk_cd, tkn_smbl, obs_dttm, fee_lvl, vndr_ast_id,
                       fee_per_byte, gas_price, ntwk_fee, base_fee, priority_fee
                  FROM bcm_fee_qt_l
                 WHERE ntwk_cd = :network
                   AND tkn_smbl = :symbol
                   AND fee_lvl = :feeLevel
                   AND obs_dttm <= :requestedAt
                 ORDER BY obs_dttm DESC
                 LIMIT 1
                """.trimIndent(),
                mapOf(
                    "network" to network,
                    "symbol" to symbol,
                    "feeLevel" to feeLevel.name,
                    "requestedAt" to requestedAt,
                ),
                rowMapper,
            ).firstOrNull()

    override fun findForSubmission(externalTransactionId: String): NetworkFeeQuote? =
        jdbc
            .query(
                """
                SELECT quote.ntwk_cd, quote.tkn_smbl, quote.obs_dttm, quote.fee_lvl,
                       quote.vndr_ast_id, quote.fee_per_byte, quote.gas_price,
                       quote.ntwk_fee, quote.base_fee, quote.priority_fee
                  FROM bcm_sbmt_l submission
                  JOIN LATERAL (
                    SELECT fee.*
                      FROM bcm_fee_qt_l fee
                     WHERE fee.ntwk_cd = submission.ntwk_cd
                       AND fee.tkn_smbl = submission.tkn_smbl
                       AND fee.fee_lvl = 'MEDIUM'
                       AND fee.obs_dttm <= submission.req_dttm
                     ORDER BY fee.obs_dttm DESC
                     LIMIT 1
                  ) quote ON TRUE
                 WHERE submission.ext_tx_id = :externalTransactionId
                """.trimIndent(),
                mapOf("externalTransactionId" to externalTransactionId),
                rowMapper,
            ).firstOrNull()

    override fun findForBoost(
        originalTransactionId: String,
        attemptSequence: Int,
    ): NetworkFeeQuote? =
        jdbc
            .query(
                """
                SELECT quote.ntwk_cd, quote.tkn_smbl, quote.obs_dttm, quote.fee_lvl,
                       quote.vndr_ast_id, quote.fee_per_byte, quote.gas_price,
                       quote.ntwk_fee, quote.base_fee, quote.priority_fee
                  FROM bcm_boost_l boost
                  JOIN bcm_tx_l transaction ON transaction.vndr_tx_id = boost.orig_tx_id
                  JOIN LATERAL (
                    SELECT fee.*
                      FROM bcm_fee_qt_l fee
                     WHERE fee.ntwk_cd = transaction.ntwk_cd
                       AND fee.tkn_smbl = transaction.tkn_smbl
                       AND fee.fee_lvl = boost.fee_lvl
                       AND fee.obs_dttm <= boost.req_dttm
                     ORDER BY fee.obs_dttm DESC
                     LIMIT 1
                  ) quote ON TRUE
                 WHERE boost.orig_tx_id = :originalTransactionId
                   AND boost.try_seq = :attemptSequence
                """.trimIndent(),
                mapOf(
                    "originalTransactionId" to originalTransactionId,
                    "attemptSequence" to attemptSequence,
                ),
                rowMapper,
            ).firstOrNull()

    private fun parameters(quote: NetworkFeeQuote) =
        mapOf(
            "network" to quote.network,
            "symbol" to quote.symbol,
            "observedAt" to quote.observedAt,
            "feeLevel" to quote.feeLevel.name,
            "vendorAssetId" to quote.vendorAssetId,
            "feePerByte" to quote.feePerByte,
            "gasPrice" to quote.gasPrice,
            "networkFee" to quote.networkFee,
            "baseFee" to quote.baseFee,
            "priorityFee" to quote.priorityFee,
            "employeeNo" to SystemAudit.EMPNO,
            "branchCode" to SystemAudit.BRCD,
        )
}
