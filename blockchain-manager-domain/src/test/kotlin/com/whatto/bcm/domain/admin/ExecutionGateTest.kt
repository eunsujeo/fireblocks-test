package com.whatto.bcm.domain.admin

import com.whatto.bcm.domain.admin.fixture.ExecutionGateFixture.event
import com.whatto.bcm.domain.exception.ConflictException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ExecutionGateTest {
    @Test
    fun `중지 event가 없으면 신규 실행을 허용한다`() {
        assertThatCode { ExecutionGatePolicy.requireOpen(null) }.doesNotThrowAnyException()
    }

    @Test
    fun `중지된 네트워크와 실행 유형은 신규 실행을 차단한다`() {
        assertThatThrownBy { ExecutionGatePolicy.requireOpen(event()) }
            .isInstanceOf(ConflictException::class.java)
            .satisfies({ exception ->
                exception as ConflictException
                assertThat(exception.resource)
                    .isEqualTo("executionGate")
                assertThat(exception.key)
                    .isEqualTo("BASE:WITHDRAWAL")
            })
    }

    @Test
    fun `원장이 없는 범위는 신규 실행과 기존 실행 복구를 허용한다`() {
        val availability = ExecutionGatePolicy.availability(ExecutionGateType.SWEEP, null)

        assertThat(availability.state).isEqualTo(ExecutionGateState.OPEN)
        assertThat(availability.newExecutionAllowed).isTrue()
        assertThat(availability.existingExecutionRecoveryAllowed).isTrue()
        assertThat(availability.disabledReasons).isEmpty()
    }

    @Test
    fun `approve 중지는 신규 확대만 막고 기존 복구와 비상 회수를 허용한다`() {
        val availability =
            ExecutionGatePolicy.availability(
                ExecutionGateType.APPROVE,
                event(type = ExecutionGateType.APPROVE),
            )

        assertThat(availability.state).isEqualTo(ExecutionGateState.STOPPED)
        assertThat(availability.newExecutionAllowed).isFalse()
        assertThat(availability.existingExecutionRecoveryAllowed).isTrue()
        assertThat(availability.emergencyRevocationAllowed).isTrue()
        assertThat(availability.disabledReasons).containsExactly("EXECUTION_GATE_STOPPED")
    }

    @Test
    fun `원장이 열려 있어도 release 준비가 닫히면 신규 실행을 차단한다`() {
        val availability =
            ExecutionGatePolicy.availability(
                type = ExecutionGateType.SWEEP,
                current = null,
                releaseReady = false,
            )

        assertThat(availability.state).isEqualTo(ExecutionGateState.OPEN)
        assertThat(availability.newExecutionAllowed).isFalse()
        assertThat(availability.existingExecutionRecoveryAllowed).isTrue()
        assertThat(availability.disabledReasons).containsExactly("RELEASE_GATE_NOT_READY")
    }
}
