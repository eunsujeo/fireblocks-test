package com.whatto.bcm.domain.admin

import com.whatto.bcm.domain.admin.fixture.AdminPolicyFixture.actor
import com.whatto.bcm.domain.admin.fixture.AdminPolicyFixture.decision
import com.whatto.bcm.domain.admin.fixture.AdminPolicyFixture.request
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Instant

class AdminPolicyLifecycleTest {
    private val now = Instant.parse("2026-08-17T12:30:00Z")

    @Test
    fun `요청자는 자신의 변경 요청을 승인할 수 없다`() {
        val target = request()

        assertThatThrownBy {
            AdminPolicyLifecycle.decide(
                target,
                emptyList(),
                target.requester,
                ChangeDecision.APPROVE,
                target.targetSnapshotHash,
                now,
            )
        }.isInstanceOf(SelfApprovalNotAllowed::class.java)
    }

    @Test
    fun `같은 승인자의 동일 판단 재시도는 기존 판단을 반환하고 상반된 판단은 거절한다`() {
        val target = request()
        val existing = decision()

        val retried =
            AdminPolicyLifecycle.decide(
                target,
                listOf(existing),
                existing.actor,
                ChangeDecision.APPROVE,
                target.targetSnapshotHash,
                now,
            )

        assertThat(retried).isSameAs(existing)
        assertThatThrownBy {
            AdminPolicyLifecycle.decide(
                target,
                listOf(existing),
                existing.actor,
                ChangeDecision.REJECT,
                target.targetSnapshotHash,
                now,
            )
        }.isInstanceOf(ConflictingDecision::class.java)
    }

    @Test
    fun `일반 변경은 독립 승인자 한 명이면 활성화 정족수를 충족한다`() {
        val target = request(risk = ChangeRisk.GENERAL)

        val permission =
            AdminPolicyLifecycle.validateActivation(
                target,
                listOf(decision()),
                currentBindingRevision = 0,
                currentSnapshotHash = target.targetSnapshotHash,
                targetReady = true,
                now = now,
            )

        assertThat(permission.requiredApprovals).isEqualTo(1)
    }

    @Test
    fun `보안 변경은 서로 다른 두 승인자와 보안 승인자 한 명이 모두 필요하다`() {
        val target = request(risk = ChangeRisk.SECURITY)
        val ordinaryOnly = listOf(decision("222222"), decision("333333"))

        assertThatThrownBy {
            AdminPolicyLifecycle.validateActivation(
                target,
                ordinaryOnly,
                0,
                target.targetSnapshotHash,
                true,
                now,
            )
        }.isInstanceOf(SecurityApproverRequired::class.java)

        val permission =
            AdminPolicyLifecycle.validateActivation(
                target,
                listOf(
                    decision("222222"),
                    decision("333333", AdminRole.BCM_SECURITY_APPROVER),
                ),
                0,
                target.targetSnapshotHash,
                true,
                now,
            )

        assertThat(permission.requiredApprovals).isEqualTo(2)
    }

    @Test
    fun `화면을 연 뒤 snapshot이나 binding revision이 달라지면 활성화하지 않는다`() {
        val target = request(baseBindingRevision = 3)

        assertThatThrownBy {
            AdminPolicyLifecycle.validateActivation(
                target,
                listOf(decision()),
                currentBindingRevision = 4,
                currentSnapshotHash = "d".repeat(64),
                targetReady = true,
                now = now,
            )
        }.isInstanceOf(StaleChangeSnapshot::class.java)
    }

    @Test
    fun `거절 또는 만료된 요청과 외부 검증 미완료 대상은 fail closed한다`() {
        val target = request(expiresAt = Instant.parse("2026-08-17T12:20:00Z"))

        assertThatThrownBy {
            AdminPolicyLifecycle.validateActivation(
                target,
                listOf(decision(decision = ChangeDecision.REJECT)),
                0,
                target.targetSnapshotHash,
                false,
                now,
            )
        }.isInstanceOf(ChangeRequestRejected::class.java)
    }
}
