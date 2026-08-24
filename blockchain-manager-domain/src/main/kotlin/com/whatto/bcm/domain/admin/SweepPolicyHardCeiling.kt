package com.whatto.bcm.domain.admin

import java.math.BigDecimal

data class SweepExecutionPolicy(
    val enabled: Boolean,
    val minimumAmount: BigDecimal,
    val batchSize: Int,
    val allowanceCap: BigDecimal,
    val itemAmountCap: BigDecimal,
    val batchAmountCap: BigDecimal,
    val boostAttempts: Int,
)

data class SweepPolicyHardCeiling(
    val executionEnabled: Boolean,
    val maximumBatchSize: Int,
    val maximumAllowance: BigDecimal,
    val maximumItemAmount: BigDecimal,
    val maximumBatchAmount: BigDecimal,
    val maximumBoostAttempts: Int,
)

data class SweepPolicyCeilingEvaluation(
    val passed: Boolean,
    val violations: List<String>,
)

object SweepPolicyCeilingEvaluator {
    fun evaluate(
        policy: SweepExecutionPolicy,
        ceiling: SweepPolicyHardCeiling,
    ): SweepPolicyCeilingEvaluation {
        val violations =
            buildList {
                if (policy.enabled && !ceiling.executionEnabled) add("EXECUTION_DISABLED")
                if (policy.batchSize > ceiling.maximumBatchSize) add("BATCH_SIZE")
                if (policy.allowanceCap > ceiling.maximumAllowance) add("ALLOWANCE_CAP")
                if (policy.itemAmountCap > ceiling.maximumItemAmount) add("ITEM_AMOUNT_CAP")
                if (policy.batchAmountCap > ceiling.maximumBatchAmount) add("BATCH_AMOUNT_CAP")
                if (policy.boostAttempts > ceiling.maximumBoostAttempts) add("BOOST_ATTEMPTS")
                if (
                    policy.minimumAmount.signum() < 0 ||
                    policy.batchSize <= 0 ||
                    policy.allowanceCap.signum() < 0 ||
                    policy.itemAmountCap.signum() < 0 ||
                    policy.batchAmountCap.signum() < 0 ||
                    policy.boostAttempts < 0
                ) {
                    add("INVALID_NEGATIVE_VALUE")
                }
                if (policy.minimumAmount > policy.itemAmountCap) add("MINIMUM_ABOVE_ITEM_CAP")
                if (policy.itemAmountCap > policy.batchAmountCap) add("ITEM_ABOVE_BATCH_CAP")
            }.distinct()
        return SweepPolicyCeilingEvaluation(violations.isEmpty(), violations)
    }
}
