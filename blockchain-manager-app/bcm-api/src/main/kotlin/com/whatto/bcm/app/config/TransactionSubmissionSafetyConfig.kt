package com.whatto.bcm.app.config

import com.whatto.bcm.app.application.submission.TransactionSubmissionProperties
import com.whatto.bcm.infra.client.fireblocks.FireblocksProperties
import org.springframework.context.annotation.Configuration

/** application과 infra를 함께 아는 조립 지점에서 claim TTL의 운영 안전조건을 검증한다. */
@Configuration
class TransactionSubmissionSafetyConfig(
    properties: TransactionSubmissionProperties,
    fireblocksProperties: FireblocksProperties,
) {
    init {
        require(properties.claimTtlMillis > fireblocksProperties.maximumSubmissionFlowMillis) {
            "claim TTL must be longer than the maximum Fireblocks submission flow"
        }
    }
}
