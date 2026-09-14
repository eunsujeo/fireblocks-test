package com.whatto.bcm.app.bat

import com.whatto.bcm.app.bat.stall.StallCheckProperties
import com.whatto.bcm.app.bat.stall.StallCheckSafetyConfig
import com.whatto.bcm.app.bat.submission.SubmissionRecoveryProperties
import com.whatto.bcm.app.bat.submission.SubmissionRecoverySafetyConfig
import com.whatto.bcm.domain.vendor.VendorExecutionLimits
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class VendorExecutionLimitsConfigTest {
    private val limits =
        object : VendorExecutionLimits {
            override val maximumCallMillis = 17_000L
            override val maximumSubmissionFlowMillis = 53_000L
        }

    @Test
    fun `제출 회수는 선택 벤더의 단일 호출이 끝난 뒤 실행한다`() {
        assertThatThrownBy {
            SubmissionRecoverySafetyConfig(SubmissionRecoveryProperties(retryAfterSeconds = 17), limits)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatCode {
            SubmissionRecoverySafetyConfig(SubmissionRecoveryProperties(retryAfterSeconds = 18), limits)
        }.doesNotThrowAnyException()
    }

    @Test
    fun `가속 소유권은 회수 조회와 제출 전체 흐름보다 길게 유지한다`() {
        assertThatThrownBy {
            StallCheckSafetyConfig(StallCheckProperties(boostClaimTtlSeconds = 70), limits)
        }.isInstanceOf(IllegalArgumentException::class.java)

        assertThatCode {
            StallCheckSafetyConfig(StallCheckProperties(boostClaimTtlSeconds = 71), limits)
        }.doesNotThrowAnyException()
    }
}
