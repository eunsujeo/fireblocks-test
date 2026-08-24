package com.whatto.bcm.infra.persistence.admin

import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminChangeRequest
import com.whatto.bcm.domain.admin.AdminDecisionRecord
import com.whatto.bcm.domain.admin.AdminPolicyRepository
import com.whatto.bcm.domain.admin.AdminPolicyVersion
import com.whatto.bcm.domain.admin.AdminRole
import com.whatto.bcm.domain.admin.ChangeDecision
import com.whatto.bcm.domain.admin.ChangeRisk
import com.whatto.bcm.domain.admin.ChangeTargetType
import com.whatto.bcm.domain.admin.PolicyChangeRequest
import com.whatto.bcm.domain.admin.PolicyDecision
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import java.time.Instant

@DataJdbcTest
@Import(AdminPolicyJdbcAdapter::class)
class AdminPolicyPersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var policies: AdminPolicyRepository

    private val requester = AdminActor("123456", "0001", setOf(AdminRole.BCM_OPERATOR))
    private val approver = AdminActor("222222", "0002", setOf(AdminRole.BCM_APPROVER))
    private val now = Instant.parse("2026-08-17T12:00:00Z")

    @Test
    fun `정책 요청 승인 활성화와 성공 응답 재시도를 불변 원장으로 보관한다`() {
        val version =
            policies.insertPolicyVersion(
                AdminPolicyVersion(
                    versionId = "policy-adapter-v1",
                    scopeId = "POLICY:BASE:ADAPTER",
                    versionNumber = 1,
                    schemaVersion = "v1",
                    baseVersionId = null,
                    contractVersionId = null,
                    payload = "{\"batchSize\":10}",
                    policyHash = hash("policy-adapter-v1"),
                    ceilingSnapshot = "{\"maximumBatchSize\":20}",
                    ceilingHash = hash("ceiling-adapter-v1"),
                    ceilingPassed = true,
                    registeredAt = now,
                    registeredBy = requester,
                ),
            )
        val initial = policies.initializePolicyBinding(version.scopeId, requester, now)
        val request = changeRequest(version, initial.revision)

        assertThat(policies.insertChangeRequest(request)).isEqualTo(request)
        assertThat(policies.findChangeRequestByIdempotency(requester.employeeNo, "request-adapter"))
            .usingRecursiveComparison()
            .ignoringFields("diffPayload", "impactPayload")
            .isEqualTo(request)

        val decision =
            AdminDecisionRecord(
                PolicyDecision(
                    requestId = request.lifecycle.requestId,
                    actor = approver,
                    decision = ChangeDecision.APPROVE,
                    snapshotHash = request.lifecycle.targetSnapshotHash,
                    decidedAt = now.plusSeconds(60),
                ),
                "hard ceiling 이내임을 확인",
            )
        policies.insertDecision(decision)

        assertThat(policies.findDecisions(request.lifecycle.requestId)).containsExactly(decision)
        assertThat(policies.isPolicyReady(version.versionId)).isTrue()
        assertThat(policies.lockPolicyBinding(version.scopeId)).isEqualTo(initial)

        val expectedState = "{\"revision\":0}"
        policies.insertActivationIntent(
            "action-intent-adapter",
            "correlation-adapter",
            request,
            "activate-adapter",
            hash("activate-request"),
            expectedState,
            hash(expectedState),
            approver,
            now.plusSeconds(120),
        )
        val binding = policies.activatePolicyBinding(request, approver, now.plusSeconds(120))
        val observedState = "{\"revision\":1}"
        val success =
            policies.insertActivationSuccess(
                "action-success-adapter",
                "correlation-adapter",
                request,
                "activate-adapter",
                hash("activate-request"),
                expectedState,
                hash(expectedState),
                observedState,
                hash(observedState),
                approver,
                now.plusSeconds(120),
            )

        assertThat(binding.activeVersionId).isEqualTo(version.versionId)
        assertThat(binding.revision).isEqualTo(1)
        assertThat(policies.findSuccessfulActivation(request.lifecycle.requestId, "activate-adapter"))
            .isEqualTo(success)
    }

    private fun changeRequest(
        version: AdminPolicyVersion,
        revision: Long,
    ): AdminChangeRequest =
        AdminChangeRequest(
            lifecycle =
                PolicyChangeRequest(
                    requestId = "request-adapter-v1",
                    requester = requester,
                    risk = ChangeRisk.GENERAL,
                    targetSnapshotHash = "a".repeat(64),
                    baseBindingRevision = revision,
                    targetVersionId = version.versionId,
                    expiresAt = Instant.parse("2099-12-31T23:59:59Z"),
                ),
            targetType = ChangeTargetType.POLICY,
            scopeId = version.scopeId,
            beforeVersionId = null,
            evidenceId = null,
            diffPayload = "{\"batchSize\":{\"before\":null,\"after\":10}}",
            diffHash = hash("diff-adapter"),
            impactPayload = "{\"accounts\":0}",
            impactHash = hash("impact-adapter"),
            reason = "초기 sweep 정책 등록",
            workTicket = "OPS-100",
            idempotencyKey = "request-adapter",
            requestedRole = AdminRole.BCM_OPERATOR,
            requestedAt = now,
        )

    private fun hash(seed: String): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(seed.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
