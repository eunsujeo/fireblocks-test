package com.whatto.bcm.infra.persistence.sweep

import com.whatto.bcm.domain.admin.SweepExecutionPolicy
import com.whatto.bcm.domain.sweep.ActiveSweepRuntimeContext
import com.whatto.bcm.domain.sweep.SweepRuntimePolicyRepository
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

@Repository
class SweepRuntimePolicyJdbcAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : SweepRuntimePolicyRepository {
    override fun findActive(
        network: String,
        symbol: String,
        observedAt: Instant,
    ): ActiveSweepRuntimeContext? =
        jdbc
            .query(
                """
                SELECT policy.plcy_vrsn_id, policy_binding.bind_snps_hash,
                       policy.plcy_payload->>'enabled' AS enabled,
                       policy.plcy_payload->>'minimumAmount' AS minimum_amount,
                       policy.plcy_payload->>'batchSize' AS batch_size,
                       policy.plcy_payload->>'allowanceCap' AS allowance_cap,
                       policy.plcy_payload->>'itemAmountCap' AS item_amount_cap,
                       policy.plcy_payload->>'batchAmountCap' AS batch_amount_cap,
                       policy.plcy_payload->>'boostAttempts' AS boost_attempts,
                       contract.ctrt_vrsn_id, contract.ctrt_addr, evidence.evdc_id
                  FROM bcm_plcy_bind_m policy_binding
                  JOIN bcm_plcy_vrsn_l policy ON policy.plcy_vrsn_id = policy_binding.actv_plcy_vrsn_id
                  JOIN bcm_ctrt_vrsn_l contract ON contract.ctrt_vrsn_id = policy.ctrt_vrsn_id
                  JOIN bcm_ctrt_bind_m contract_binding
                    ON contract_binding.ctrt_scope_id = contract.ctrt_scope_id
                   AND contract_binding.actv_ctrt_vrsn_id = contract.ctrt_vrsn_id
                  JOIN LATERAL (
                    SELECT current_evidence.evdc_id
                      FROM bcm_ctrt_evdc_l current_evidence
                     WHERE current_evidence.ctrt_vrsn_id = contract.ctrt_vrsn_id
                     ORDER BY current_evidence.obs_dttm DESC, current_evidence.evdc_id DESC
                     LIMIT 1
                  ) evidence ON true
                 WHERE policy_binding.plcy_scope_id = :scopeId
                   AND contract.ntwk_cd = :network
                   AND contract.use_dvcd = 'SWEEP'
                   AND policy.ceiling_pass_yn = 'Y'
                   AND policy.plcy_payload->>'enabled' = 'true'
                   AND EXISTS (
                     SELECT 1 FROM bcm_ctrt_evdc_l current_evidence
                      WHERE current_evidence.evdc_id = evidence.evdc_id
                        AND current_evidence.evdc_stcd = 'VALID'
                        AND current_evidence.launch_gate_yn = 'Y'
                        AND current_evidence.vld_until_dttm > :observedAt
                   )
                """.trimIndent(),
                mapOf(
                    "scopeId" to "POLICY:$network:$symbol",
                    "network" to network,
                    "observedAt" to CoreDateTimes.format(LocalDateTime.ofInstant(observedAt, ZoneOffset.UTC)),
                ),
            ) { rs, _ ->
                ActiveSweepRuntimeContext(
                    network = network,
                    symbol = symbol,
                    policyVersionId = rs.getString("plcy_vrsn_id"),
                    policySnapshotHash = rs.getString("bind_snps_hash"),
                    policy =
                        SweepExecutionPolicy(
                            enabled = rs.getBoolean("enabled"),
                            minimumAmount = rs.getBigDecimal("minimum_amount"),
                            batchSize = rs.getInt("batch_size"),
                            allowanceCap = rs.getBigDecimal("allowance_cap"),
                            itemAmountCap = rs.getBigDecimal("item_amount_cap"),
                            batchAmountCap = rs.getBigDecimal("batch_amount_cap"),
                            boostAttempts = rs.getInt("boost_attempts"),
                        ),
                    contractVersionId = rs.getString("ctrt_vrsn_id"),
                    contractEvidenceId = rs.getString("evdc_id"),
                    contractAddress = rs.getString("ctrt_addr"),
                )
            }.singleOrNull()
}
