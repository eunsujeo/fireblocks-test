package com.whatto.bcm.app.application.submission

import org.springframework.boot.context.properties.ConfigurationProperties

/** 제출 소유권(claim)의 시간 규약 (02-bcm-flow 소유권 절). */
@ConfigurationProperties("bcm.transaction-submission")
data class TransactionSubmissionProperties(
    val claimTtlSeconds: Long = 120,
) {
    init {
        require(claimTtlSeconds > 0) { "claimTtlSeconds must be positive" }
    }

    val claimTtlMillis: Long
        get() = Math.multiplyExact(claimTtlSeconds, 1_000)
}
