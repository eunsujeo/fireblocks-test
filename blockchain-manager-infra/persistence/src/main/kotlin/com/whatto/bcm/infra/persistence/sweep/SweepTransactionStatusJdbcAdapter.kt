package com.whatto.bcm.infra.persistence.sweep

import com.whatto.bcm.domain.sweep.SweepTargetKey
import com.whatto.bcm.domain.sweep.SweepTransactionStatusRepository
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class SweepTransactionStatusJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : SweepTransactionStatusRepository {
    override fun finalizedDepositIds(key: SweepTargetKey): Set<String> =
        jdbc
            .queryForList(
                """
                SELECT vndr_tx_id
                FROM bcm_tx_l
                WHERE ext_tx_id IS NULL
                  AND acnt_id = :accountId
                  AND ntwk_cd = :network
                  AND tkn_smbl = :symbol
                  AND last_pub_stcd = 'FINALIZED'
                """.trimIndent(),
                mapOf(
                    "accountId" to key.accountId,
                    "network" to key.network,
                    "symbol" to key.symbol,
                ),
                String::class.java,
            ).filterNotNull()
            .toSet()
}
