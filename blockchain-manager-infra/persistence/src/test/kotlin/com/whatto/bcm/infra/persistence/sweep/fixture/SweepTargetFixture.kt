package com.whatto.bcm.infra.persistence.sweep.fixture

import com.whatto.bcm.domain.sweep.SweepTarget

object SweepTargetFixture {
    fun fixture(
        accountId: String = "acct-customer-1",
        network: String = "ETHEREUM",
        symbol: String = "USDC",
        registeredAt: String = "20260810120000",
        activeSweepExecutionId: String? = null,
        activeItemSequence: Int? = null,
        attemptCount: Int = 0,
        lastAttemptedAt: String? = null,
    ): SweepTarget =
        SweepTarget(
            accountId = accountId,
            network = network,
            symbol = symbol,
            registeredAt = registeredAt,
            activeSweepExecutionId = activeSweepExecutionId,
            activeItemSequence = activeItemSequence,
            attemptCount = attemptCount,
            lastAttemptedAt = lastAttemptedAt,
        )
}
