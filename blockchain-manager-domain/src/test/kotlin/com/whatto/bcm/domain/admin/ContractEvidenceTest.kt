package com.whatto.bcm.domain.admin

import com.whatto.bcm.domain.admin.fixture.AdminPolicyFixture.evidence
import com.whatto.bcm.domain.admin.fixture.AdminPolicyFixture.observation
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class ContractEvidenceTest {
    private val now = Instant.parse("2026-08-17T12:30:00Z")

    @Test
    fun `독립 두 RPC와 code hash 불변값 외부 통제가 모두 일치하면 valid다`() {
        assertThat(ContractEvidenceEvaluator.evaluate(evidence(), now).status)
            .isEqualTo(ContractEvidenceStatus.VALID)
    }

    @Test
    fun `RPC 한쪽 실패는 error이고 chainId code hash 불변값 불일치는 invalid다`() {
        assertThat(ContractEvidenceEvaluator.evaluate(evidence(second = null), now).status)
            .isEqualTo(ContractEvidenceStatus.ERROR)
        assertThat(
            ContractEvidenceEvaluator
                .evaluate(
                    evidence(second = observation("rpc-b", codeHash = "d".repeat(64))),
                    now,
                ).status,
        ).isEqualTo(ContractEvidenceStatus.INVALID)
    }

    @Test
    fun `같은 RPC 두 번 조회하거나 pinned block이 다르면 invalid다`() {
        assertThat(
            ContractEvidenceEvaluator
                .evaluate(
                    evidence(second = observation("rpc-a")),
                    now,
                ).status,
        ).isEqualTo(ContractEvidenceStatus.INVALID)
        assertThat(
            ContractEvidenceEvaluator
                .evaluate(
                    evidence(second = observation("rpc-b", blockNumber = java.math.BigInteger.valueOf(1235))),
                    now,
                ).status,
        ).isEqualTo(ContractEvidenceStatus.INVALID)
    }

    @Test
    fun `검증 만료와 외부 통제 미통과는 활성화 증거가 아니다`() {
        val expired = evidence(validUntil = Instant.parse("2026-08-17T12:20:00Z"))
        val drift = evidence(controls = ExternalControlEvidence.allPassed().copy(callbackMatches = false))

        assertThat(ContractEvidenceEvaluator.evaluate(expired, now).status)
            .isEqualTo(ContractEvidenceStatus.STALE)
        assertThat(ContractEvidenceEvaluator.evaluate(drift, now).status)
            .isEqualTo(ContractEvidenceStatus.INVALID)
    }
}
