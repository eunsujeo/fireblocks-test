package com.whatto.bcm.domain.admin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.time.Instant

class ExecutionGateResumeTest {
    @Test
    fun `TAP과 독립 RPC 두 곳이 승인 snapshot의 정상 상태와 같을 때만 재개 준비다`() {
        val evaluation = ExecutionGateResumeEvaluator.evaluate(candidate(), NOW)

        assertThat(evaluation.status).isEqualTo(ExecutionGateResumeCheckStatus.READY)
        assertThat(evaluation.issues).isEmpty()
        assertThat(evaluation.resumeReady).isTrue()
    }

    @Test
    fun `pause 또는 운영자 집합이 승인 snapshot과 다르면 drift다`() {
        val candidate =
            candidate().copy(
                second = candidate().second?.copy(paused = true, operatorSetHash = "c".repeat(64)),
            )

        val evaluation = ExecutionGateResumeEvaluator.evaluate(candidate, NOW)

        assertThat(evaluation.status).isEqualTo(ExecutionGateResumeCheckStatus.DRIFT)
        assertThat(evaluation.issues).containsExactly("RPC_2_PAUSED", "RPC_2_OPERATOR_DRIFT")
    }

    @Test
    fun `외부 응답 누락과 source 오류와 만료는 각각 fail closed다`() {
        assertThat(
            ExecutionGateResumeEvaluator.evaluate(candidate().copy(first = null), NOW).status,
        ).isEqualTo(ExecutionGateResumeCheckStatus.UNCONFIRMED)
        assertThat(
            ExecutionGateResumeEvaluator.evaluate(candidate().copy(sourceErrors = listOf("RPC_SOURCE_ERROR")), NOW).status,
        ).isEqualTo(ExecutionGateResumeCheckStatus.ERROR)
        assertThat(
            ExecutionGateResumeEvaluator.evaluate(candidate(), NOW.plusSeconds(301)).status,
        ).isEqualTo(ExecutionGateResumeCheckStatus.STALE)
    }

    @Test
    fun `최신 event가 재개이면 실행 게이트는 다시 열린다`() {
        val resumed =
            ExecutionGateEvent(
                "event-2",
                "ETH_TEST",
                ExecutionGateType.SWEEP,
                2,
                ExecutionGateStatus.RESUMED,
                "원인 해소",
                "INC-1",
                "resume-1",
                NOW,
                ACTOR,
                "request-1",
            )

        assertThat(ExecutionGatePolicy.availability(ExecutionGateType.SWEEP, resumed).state)
            .isEqualTo(ExecutionGateState.OPEN)
        assertThat(ExecutionGatePolicy.availability(ExecutionGateType.SWEEP, resumed).newExecutionAllowed).isTrue()
    }

    private fun candidate() =
        ExecutionGateResumeCheckCandidate(
            tapSourceId = "tap-policy",
            tap = TapBatchObservation(false, NOW),
            pinnedBlockNumber = BigInteger.valueOf(1234),
            expectedOperatorSetHash = "b".repeat(64),
            firstEndpointId = "rpc-a",
            first = contractObservation("b".repeat(64)),
            secondEndpointId = "rpc-b",
            second = contractObservation("b".repeat(64)),
            sourceErrors = emptyList(),
            observedAt = NOW,
            validUntil = NOW.plusSeconds(300),
        )

    private fun contractObservation(operatorSetHash: String) =
        EmergencyContractObservation(
            blockNumber = BigInteger.valueOf(1234),
            paused = false,
            operatorSetHash = operatorSetHash,
            observedAt = NOW,
        )

    companion object {
        private val NOW = Instant.parse("2026-08-19T01:00:00Z")
        private val ACTOR = AdminActor("830001", "0001", setOf(AdminRole.BCM_OPERATOR))
    }
}
