package com.whatto.bcm.infra.persistence.sweep

import com.whatto.bcm.domain.sweep.SweepExecutionGatePort
import com.whatto.bcm.domain.sweep.SweepExecutionGateSnapshot
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class SweepExecutionGateJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : SweepExecutionGatePort {
    override fun findCurrent(network: String): SweepExecutionGateSnapshot =
        jdbc
            .query(
                """
                SELECT evt_seq, gate_stcd
                  FROM bcm_exec_gate_evt_l
                 WHERE ntwk_cd = :network
                   AND gate_dvcd = 'SWEEP'
                 ORDER BY evt_seq DESC
                 LIMIT 1
                """.trimIndent(),
                mapOf("network" to network),
            ) { rs, _ -> SweepExecutionGateSnapshot(network, rs.getInt("evt_seq"), rs.getString("gate_stcd")) }
            .firstOrNull() ?: SweepExecutionGateSnapshot(network, 0, "OPEN")
}
