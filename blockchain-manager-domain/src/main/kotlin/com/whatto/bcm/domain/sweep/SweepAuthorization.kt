package com.whatto.bcm.domain.sweep

import java.math.BigDecimal

data class SweepAuthorizationKey(
    val accountId: String,
    val network: String,
    val symbol: String,
    val sweepContractAddress: String,
)

/** 온체인 allowance가 정본이며 이 모델은 마지막 관찰과 승인 진행 상태만 보관한다. */
data class SweepAuthorization(
    val key: SweepAuthorizationKey,
    val allowanceCap: String,
    val observedAllowance: String,
    val status: SweepAuthorizationStatus,
    val approvalExternalTransactionId: String?,
    val approvalVendorTransactionId: String?,
    val lastCheckedAt: String,
)

enum class SweepAuthorizationStatus {
    UNAPPROVED,
    APPROVING,
    ACTIVE,
    REVOKING,
    REVOKED,
    FAILED,
}

interface SweepAuthorizationRepository {
    fun insert(authorization: SweepAuthorization): SweepAuthorization

    fun findByKey(key: SweepAuthorizationKey): SweepAuthorization?

    fun findByKeyForUpdate(key: SweepAuthorizationKey): SweepAuthorization?

    fun update(authorization: SweepAuthorization): SweepAuthorization
}

data class SweepAllowanceObservation(
    val amount: String,
    val decimals: Int,
)

sealed interface SweepAllowanceDecision {
    data object Ready : SweepAllowanceDecision

    data object WaitingForChain : SweepAllowanceDecision

    data class Approve(
        val amount: String,
    ) : SweepAllowanceDecision

    data object RevokeFirst : SweepAllowanceDecision
}

/** DB 상태가 아니라 매 회차 온체인 관찰을 근거로 allowance 전이를 판정한다. */
object SweepAllowancePolicy {
    fun observe(
        current: SweepAuthorization?,
        key: SweepAuthorizationKey,
        allowanceCap: String,
        observation: SweepAllowanceObservation,
        observedAt: String,
    ): SweepAuthorization {
        val configuredCap = positive(allowanceCap, "allowanceCap")
        val currentCap = current?.let { positive(it.allowanceCap, "currentAllowanceCap") }
        val actionPending =
            current?.status == SweepAuthorizationStatus.APPROVING ||
                current?.status == SweepAuthorizationStatus.REVOKING
        val effectiveCap = if (actionPending) requireNotNull(currentCap) else configuredCap
        val capChanged = currentCap != null && currentCap.compareTo(configuredCap) != 0
        val observed = nonNegative(observation.amount, "observedAllowance")
        require(observation.decimals >= 0) { "token decimals must not be negative" }
        val status =
            when (current?.status) {
                SweepAuthorizationStatus.REVOKING ->
                    if (observed.signum() == 0) SweepAuthorizationStatus.REVOKED else SweepAuthorizationStatus.REVOKING

                SweepAuthorizationStatus.APPROVING ->
                    when {
                        observed.signum() == 0 -> SweepAuthorizationStatus.APPROVING
                        observed > effectiveCap -> SweepAuthorizationStatus.FAILED
                        else -> SweepAuthorizationStatus.ACTIVE
                    }

                else ->
                    when {
                        observed.signum() == 0 && current?.status == SweepAuthorizationStatus.REVOKED ->
                            SweepAuthorizationStatus.REVOKED

                        observed.signum() == 0 -> SweepAuthorizationStatus.UNAPPROVED
                        capChanged -> SweepAuthorizationStatus.UNAPPROVED
                        observed > effectiveCap -> SweepAuthorizationStatus.FAILED
                        else -> SweepAuthorizationStatus.ACTIVE
                    }
            }
        return SweepAuthorization(
            key = key,
            allowanceCap = effectiveCap.normalized(),
            observedAllowance = observed.normalized(),
            status = status,
            approvalExternalTransactionId = current?.approvalExternalTransactionId,
            approvalVendorTransactionId = current?.approvalVendorTransactionId,
            lastCheckedAt = observedAt,
        )
    }

    fun prepare(
        authorization: SweepAuthorization,
        requiredAmount: String,
    ): SweepAllowanceDecision {
        val required = positive(requiredAmount, "requiredAmount")
        val observed = nonNegative(authorization.observedAllowance, "observedAllowance")
        val cap = positive(authorization.allowanceCap, "allowanceCap")
        require(required <= cap) { "required sweep amount must not exceed allowance cap" }
        if (authorization.status == SweepAuthorizationStatus.APPROVING ||
            authorization.status == SweepAuthorizationStatus.REVOKING
        ) {
            return SweepAllowanceDecision.WaitingForChain
        }
        if (authorization.status == SweepAuthorizationStatus.ACTIVE && observed >= required) {
            return SweepAllowanceDecision.Ready
        }
        return if (observed.signum() == 0) {
            SweepAllowanceDecision.Approve(cap.normalized())
        } else {
            SweepAllowanceDecision.RevokeFirst
        }
    }

    fun emergencyRevocation(authorization: SweepAuthorization): SweepAllowanceDecision =
        if (nonNegative(authorization.observedAllowance, "observedAllowance").signum() == 0) {
            SweepAllowanceDecision.Ready
        } else if (authorization.status == SweepAuthorizationStatus.REVOKING) {
            SweepAllowanceDecision.WaitingForChain
        } else {
            SweepAllowanceDecision.RevokeFirst
        }

    private fun positive(
        value: String,
        field: String,
    ): BigDecimal = BigDecimal(value).also { require(it.signum() > 0) { "$field must be positive" } }

    private fun nonNegative(
        value: String,
        field: String,
    ): BigDecimal = BigDecimal(value).also { require(it.signum() >= 0) { "$field must not be negative" } }

    private fun BigDecimal.normalized(): String = stripTrailingZeros().toPlainString()
}
