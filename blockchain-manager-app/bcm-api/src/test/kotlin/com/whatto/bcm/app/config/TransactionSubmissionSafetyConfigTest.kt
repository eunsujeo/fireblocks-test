package com.whatto.bcm.app.config

import com.whatto.bcm.app.application.submission.TransactionSubmissionProperties
import com.whatto.bcm.infra.client.fireblocks.FireblocksProperties
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TransactionSubmissionSafetyConfigTest {
    @Test
    fun `claim TTL은 재시도와 후속 조회를 포함한 벤더 제출 흐름보다 길어야 한다`() {
        val fireblocksProperties = FireblocksProperties()

        assertThatThrownBy {
            TransactionSubmissionSafetyConfig(
                TransactionSubmissionProperties(claimTtlSeconds = 98),
                fireblocksProperties,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatCode {
            TransactionSubmissionSafetyConfig(
                TransactionSubmissionProperties(claimTtlSeconds = 99),
                fireblocksProperties,
            )
        }.doesNotThrowAnyException()
    }
}
