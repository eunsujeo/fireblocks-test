package com.whatto.bcm.domain.tx

import com.whatto.bcm.domain.vendor.VendorFeeLevel

data class BoostAttempt(
    val rootVendorTransactionId: String,
    val trySequence: Int,
    val externalTransactionId: String,
    val status: BoostStatus,
    val claimId: String?,
    val claimExpiresAt: String?,
    val replacementVendorTransactionId: String,
    val replacementTransactionHash: String,
    val feeLevel: VendorFeeLevel,
    val useGasless: Boolean,
    val newVendorTransactionId: String?,
    val requestedAt: String,
    val respondedAt: String?,
)

enum class BoostStatus {
    REQUESTED,
    SUBMITTED,
    FAILED,
}

object BoostPolicy {
    val rootStatuses: Set<TxStatus> = setOf(TxStatus.SUBMITTED, TxStatus.CONFIRMED)
}

data class BoostIntentRequest(
    val externalTransactionId: String,
    val claimId: String,
    val claimExpiresAt: String,
    val replacementVendorTransactionId: String,
    val replacementTransactionHash: String,
    val feeLevel: VendorFeeLevel,
    val useGasless: Boolean,
    val requestedAt: String,
)

sealed interface BoostAttemptAcquisition {
    data class Acquired(
        val attempt: BoostAttempt,
        val newIntent: Boolean,
    ) : BoostAttemptAcquisition

    data object InProgress : BoostAttemptAcquisition

    data object StaleCandidate : BoostAttemptAcquisition

    data object MaximumAttemptsReached : BoostAttemptAcquisition
}

interface BoostAttemptRepository {
    /** 반드시 짧은 DB 트랜잭션 안에서 호출한다. root를 잠그고 만료 intent 회수 또는 다음 순번 선기록을 수행한다. */
    fun acquire(
        candidate: TxRecord,
        request: BoostIntentRequest,
        maximumAttempts: Int,
        now: String,
    ): BoostAttemptAcquisition

    /** boost 결과와 조건부 root active 전환을 같은 DB 트랜잭션에 기록한다. 물리 승자가 바뀌어도 boost 결과는 남긴다. */
    fun markSubmittedByClaim(
        rootVendorTransactionId: String,
        trySequence: Int,
        claimId: String,
        newVendorTransactionId: String,
        respondedAt: String,
    ): BoostAttempt

    /** boost 응답보다 서명 검증된 웹훅이 먼저 온 경우 new tx와 root active를 함께 확정한다. */
    fun markSubmittedByObservation(
        externalTransactionId: String,
        newVendorTransactionId: String,
        respondedAt: String,
    ): BoostAttempt

    fun markFailedByClaim(
        rootVendorTransactionId: String,
        trySequence: Int,
        claimId: String,
        respondedAt: String,
    ): BoostAttempt

    fun findByExternalTransactionId(externalTransactionId: String): BoostAttempt?

    fun findByNewVendorTransactionId(newVendorTransactionId: String): BoostAttempt?

    fun findLatestViableByRoot(rootVendorTransactionId: String): BoostAttempt?

    fun findViableByRoot(rootVendorTransactionId: String): List<BoostAttempt>

    fun findByRootAndSequence(
        rootVendorTransactionId: String,
        trySequence: Int,
    ): BoostAttempt?
}
