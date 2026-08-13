package com.whatto.bcm.domain.sweep

data class SweepTargetKey(
    val accountId: String,
    val network: String,
    val symbol: String,
)

/** 입금 확정 뒤 batch sweep 실행의 항목으로 claim되기를 기다리는 고객 vault 자산 작업. */
data class SweepTarget(
    val accountId: String,
    val network: String,
    val symbol: String,
    val registeredAt: String,
    val activeSweepExecutionId: String?,
    val activeItemSequence: Int?,
    val attemptCount: Int,
    val lastAttemptedAt: String?,
) {
    val key: SweepTargetKey
        get() = SweepTargetKey(accountId, network, symbol)
}
