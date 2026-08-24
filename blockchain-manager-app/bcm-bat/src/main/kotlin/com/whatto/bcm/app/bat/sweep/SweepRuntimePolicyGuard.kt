package com.whatto.bcm.app.bat.sweep

import com.whatto.bcm.domain.admin.SweepPolicyCeilingEvaluator
import com.whatto.bcm.domain.admin.SweepPolicyHardCeiling
import com.whatto.bcm.domain.sweep.ActiveSweepRuntimeContext
import com.whatto.bcm.domain.sweep.SweepRuntimePolicyRepository
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock

fun interface SweepRuntimeGuard {
    fun requireReady(
        network: String,
        symbol: String,
    ): ActiveSweepRuntimeContext
}

@Service
class SweepRuntimePolicyGuard(
    private val policies: SweepRuntimePolicyRepository,
    private val properties: SweepProperties,
    private val hardCeiling: SweepPolicyHardCeiling,
    private val clock: Clock,
) : SweepRuntimeGuard {
    override fun requireReady(
        network: String,
        symbol: String,
    ): ActiveSweepRuntimeContext {
        val context =
            checkNotNull(policies.findActive(network, symbol, clock.instant())) {
                "active Admin sweep context is not ready: network=$network symbol=$symbol"
            }
        check(context.contractAddress.equals(properties.requiredContractAddress(network), ignoreCase = true)) {
            "active Admin sweep contract differs from deployment configuration: network=$network"
        }
        val configuredMinimum = properties.minimumAmount(network, symbol)
        val configuredAllowance = BigDecimal(properties.requiredAllowanceCap(network, symbol))
        check(
            context.policy.enabled &&
                context.policy.minimumAmount.compareTo(configuredMinimum) == 0 &&
                context.policy.allowanceCap.compareTo(configuredAllowance) == 0 &&
                context.policy.batchSize == properties.batchSize,
        ) {
            "active Admin sweep policy differs from deployment configuration: network=$network symbol=$symbol"
        }
        val ceiling = SweepPolicyCeilingEvaluator.evaluate(context.policy, hardCeiling)
        check(ceiling.passed) {
            "active Admin sweep policy exceeds current hard ceiling: ${ceiling.violations.joinToString(",")}"
        }
        return context
    }
}
