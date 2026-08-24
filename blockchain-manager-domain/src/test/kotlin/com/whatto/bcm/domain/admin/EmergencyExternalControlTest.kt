package com.whatto.bcm.domain.admin

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.time.Instant

class EmergencyExternalControlTest {
    @Test
    fun `TAP 차단과 독립 RPC의 pause 운영자 제거가 모두 확인돼야 완료다`() {
        val evaluation = EmergencyExternalControlEvaluator.evaluate(candidate(), NOW)

        assertThat(evaluation.status).isEqualTo(EmergencyExternalControlStatus.CONFIRMED)
        assertThat(evaluation.completionReady).isTrue()
        assertThat(evaluation.issues).isEmpty()
    }

    @Test
    fun `TAP 또는 온체인 상태가 기대값과 다르면 drift다`() {
        val tapOpen = candidate().copy(tap = TapBatchObservation(false, NOW))
        val operatorRemaining =
            candidate().copy(
                second = contractObservation().copy(operatorSetHash = OTHER_HASH),
            )

        assertThat(EmergencyExternalControlEvaluator.evaluate(tapOpen, NOW).status)
            .isEqualTo(EmergencyExternalControlStatus.DRIFT)
        assertThat(EmergencyExternalControlEvaluator.evaluate(operatorRemaining, NOW).issues)
            .contains("RPC_2_OPERATOR_DRIFT")
    }

    @Test
    fun `응답 누락과 source 오류와 만료는 각각 완료가 아니다`() {
        val missing = candidate().copy(first = null)
        val failed = candidate().copy(sourceErrors = listOf("TAP_SOURCE_ERROR"))
        val stale = candidate().copy(validUntil = NOW)

        assertThat(EmergencyExternalControlEvaluator.evaluate(missing, NOW).status)
            .isEqualTo(EmergencyExternalControlStatus.UNCONFIRMED)
        assertThat(EmergencyExternalControlEvaluator.evaluate(failed, NOW).status)
            .isEqualTo(EmergencyExternalControlStatus.ERROR)
        assertThat(EmergencyExternalControlEvaluator.evaluate(stale, NOW).status)
            .isEqualTo(EmergencyExternalControlStatus.STALE)
    }

    @Test
    fun `같은 RPC endpoint 두 번은 확인 증적이 아니다`() {
        val duplicate = candidate().copy(secondEndpointId = "rpc-a")

        val evaluation = EmergencyExternalControlEvaluator.evaluate(duplicate, NOW)

        assertThat(evaluation.status).isEqualTo(EmergencyExternalControlStatus.DRIFT)
        assertThat(evaluation.issues).contains("RPC_NOT_INDEPENDENT")
    }

    private fun candidate() =
        EmergencyExternalControlCandidate(
            tapSourceId = "tap-policy-api",
            tap = TapBatchObservation(true, NOW),
            pinnedBlockNumber = BigInteger.valueOf(1234),
            expectedOperatorSetHash = EMPTY_HASH,
            firstEndpointId = "rpc-a",
            first = contractObservation(),
            secondEndpointId = "rpc-b",
            second = contractObservation(),
            sourceErrors = emptyList(),
            observedAt = NOW,
            validUntil = NOW.plusSeconds(300),
        )

    private fun contractObservation() =
        EmergencyContractObservation(
            blockNumber = BigInteger.valueOf(1234),
            paused = true,
            operatorSetHash = EMPTY_HASH,
            observedAt = NOW,
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-18T01:00:00Z")
        const val EMPTY_HASH = "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945"
        const val OTHER_HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    }
}
