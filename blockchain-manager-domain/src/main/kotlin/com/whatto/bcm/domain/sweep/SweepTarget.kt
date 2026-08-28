package com.whatto.bcm.domain.sweep

data class SweepTargetKey(
    val accountId: String,
    val network: String,
    val symbol: String,
)

data class SweepNoSweepRequiredCompletion(
    val sweepRequestId: String,
    val sweepRequestItemId: String,
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
    /** BAT 후보 조회에서만 채워지는 가장 오래된 미완료 DAW sweep 요청 항목. */
    val pendingSweepRequestItemId: String? = null,
    val pendingSweepRequestId: String? = null,
    val pendingSweepRequestedAt: String? = null,
) {
    val key: SweepTargetKey
        get() = SweepTargetKey(accountId, network, symbol)
}
