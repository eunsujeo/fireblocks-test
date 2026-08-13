package com.whatto.bcm.domain.sweep

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SweepAllowancePolicyTest {
    @Test
    fun `온체인 allowance가 필요 금액 이상이면 ACTIVE로 배치 편입한다`() {
        val observed = observe(amount = "80")

        assertThat(observed.status).isEqualTo(SweepAuthorizationStatus.ACTIVE)
        assertThat(SweepAllowancePolicy.prepare(observed, "50")).isEqualTo(SweepAllowanceDecision.Ready)
    }

    @Test
    fun `allowance가 0이면 cap 승인을 준비하고 제출만으로 ACTIVE가 되지 않는다`() {
        val unapproved = observe(amount = "0")

        assertThat(SweepAllowancePolicy.prepare(unapproved, "50"))
            .isEqualTo(SweepAllowanceDecision.Approve("100"))

        val approving =
            unapproved.copy(
                status = SweepAuthorizationStatus.APPROVING,
                approvalExternalTransactionId = "swa-1",
            )
        assertThat(observe(current = approving, amount = "0").status)
            .isEqualTo(SweepAuthorizationStatus.APPROVING)
    }

    @Test
    fun `부족한 0 아닌 allowance는 바로 덮어쓰지 않고 approve 0 회수를 먼저 요구한다`() {
        val insufficient = observe(amount = "20")

        assertThat(SweepAllowancePolicy.prepare(insufficient, "50"))
            .isEqualTo(SweepAllowanceDecision.RevokeFirst)
    }

    @Test
    fun `REVOKING은 온체인 0을 관찰한 뒤에만 REVOKED가 된다`() {
        val revoking =
            authorization(
                status = SweepAuthorizationStatus.REVOKING,
                observedAllowance = "20",
                approvalExternalTransactionId = "swa-revoke",
            )

        assertThat(observe(current = revoking, amount = "20").status)
            .isEqualTo(SweepAuthorizationStatus.REVOKING)
        assertThat(observe(current = revoking, amount = "0").status)
            .isEqualTo(SweepAuthorizationStatus.REVOKED)
    }

    @Test
    fun `관찰 allowance가 운영 cap을 넘으면 fail closed한다`() {
        val observed = observe(amount = "101")

        assertThat(observed.status).isEqualTo(SweepAuthorizationStatus.FAILED)
        assertThat(SweepAllowancePolicy.prepare(observed, "50"))
            .isEqualTo(SweepAllowanceDecision.RevokeFirst)
    }

    @Test
    fun `cap 변경은 기존 승인이 진행 중이면 그 cap을 보존하고 완료 뒤 회수 대상으로 바꾼다`() {
        val activeOldCap = authorization(SweepAuthorizationStatus.ACTIVE, "50").copy(allowanceCap = "50")
        val approvingOldCap =
            activeOldCap.copy(
                status = SweepAuthorizationStatus.APPROVING,
                approvalExternalTransactionId = "swa-old",
            )

        val changed = observe(current = activeOldCap, amount = "50")
        val stillApproving = observe(current = approvingOldCap, amount = "0")
        val oldApprovalCompleted = observe(current = approvingOldCap, amount = "50")

        assertThat(changed.allowanceCap).isEqualTo("100")
        assertThat(changed.status).isEqualTo(SweepAuthorizationStatus.UNAPPROVED)
        assertThat(stillApproving.allowanceCap).isEqualTo("50")
        assertThat(stillApproving.status).isEqualTo(SweepAuthorizationStatus.APPROVING)
        assertThat(oldApprovalCompleted.allowanceCap).isEqualTo("50")
        assertThat(oldApprovalCompleted.status).isEqualTo(SweepAuthorizationStatus.ACTIVE)
    }

    @Test
    fun `필요 금액이 운영 cap보다 크면 approve를 만들지 않는다`() {
        assertThatThrownBy { SweepAllowancePolicy.prepare(observe(amount = "0"), "101") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("cap")
    }

    private fun observe(
        current: SweepAuthorization? = null,
        amount: String,
    ) = SweepAllowancePolicy.observe(
        current = current,
        key = KEY,
        allowanceCap = "100",
        observation = SweepAllowanceObservation(amount, 6),
        observedAt = "20260812160000",
    )

    private fun authorization(
        status: SweepAuthorizationStatus,
        observedAllowance: String,
        approvalExternalTransactionId: String? = null,
    ) = SweepAuthorization(
        key = KEY,
        allowanceCap = "100",
        observedAllowance = observedAllowance,
        status = status,
        approvalExternalTransactionId = approvalExternalTransactionId,
        approvalVendorTransactionId = null,
        lastCheckedAt = "20260812150000",
    )

    private companion object {
        val KEY = SweepAuthorizationKey("customer-1", "ETHEREUM", "USDC", "0xsweeper")
    }
}
