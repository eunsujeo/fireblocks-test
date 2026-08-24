package com.whatto.bcm.app.application.admin

import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminContractSummary
import com.whatto.bcm.domain.admin.AdminExecutionGateScope
import com.whatto.bcm.domain.admin.AdminExternalControlEvidenceSummary
import com.whatto.bcm.domain.admin.AdminGovernanceQueryRepository
import com.whatto.bcm.domain.admin.AdminPolicySummary
import com.whatto.bcm.domain.admin.AdminRole
import com.whatto.bcm.domain.admin.AdminWebhookRuntimeObservation
import com.whatto.bcm.domain.admin.EmergencyExternalControlStatus
import com.whatto.bcm.domain.admin.ExecutionGateType
import com.whatto.bcm.domain.admin.WebhookRecoveryCallType
import com.whatto.bcm.domain.admin.WebhookRecoveryEvent
import com.whatto.bcm.domain.admin.WebhookRecoveryEventStatus
import com.whatto.bcm.domain.admin.WebhookRecoveryRequest
import com.whatto.bcm.domain.admin.WebhookRecoveryScope
import com.whatto.bcm.domain.admin.WebhookRecoveryState
import com.whatto.bcm.domain.admin.WebhookRecoveryView
import com.whatto.bcm.domain.job.JobState
import com.whatto.bcm.domain.job.JobStateRepository
import com.whatto.bcm.domain.job.OperationalJobNames
import com.whatto.bcm.domain.job.RuntimeAttestation
import com.whatto.bcm.domain.job.RuntimeAttestationEntry
import com.whatto.bcm.domain.sweep.ActiveSweepRuntimeContext
import com.whatto.bcm.domain.sweep.attestationEntry
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AdminGovernanceQueryServiceTest {
    private val governance = mockk<AdminGovernanceQueryRepository>()
    private val jobs = mockk<JobStateRepository>()
    private val service = AdminGovernanceQueryService(governance, Clock.fixed(NOW, ZoneOffset.UTC), jobs)

    @Test
    fun `조회 시각에 만료된 확인 증적은 stale로 내려 완료 처리하지 않는다`() {
        every { governance.findExecutionGateScopes(any(), any(), any()) } returns emptyList()
        every { governance.findLatestExternalControls(any()) } returns listOf(confirmedEvidence(validUntil = NOW))
        every { governance.findAllowanceRevocations(any()) } returns emptyList()
        every { governance.findWebhookRecoveries(any()) } returns emptyList()
        every { governance.findExecutionGateResumes(any(), any()) } returns emptyList()

        val evidence = service.executionGates().externalControls.single()

        assertThat(evidence.status).isEqualTo(EmergencyExternalControlStatus.STALE)
        assertThat(evidence.completionReady).isFalse()
        assertThat(evidence.issues).contains("EVIDENCE_EXPIRED")
    }

    @Test
    fun `결과 없는 웹훅 외부 호출은 제한 시각 뒤 ambiguous로 계산한다`() {
        every { governance.findExecutionGateScopes(any(), any(), any()) } returns emptyList()
        every { governance.findLatestExternalControls(any()) } returns emptyList()
        every { governance.findAllowanceRevocations(any()) } returns emptyList()
        every { governance.findExecutionGateResumes(any(), any()) } returns emptyList()
        every { governance.findWebhookRecoveries(any()) } returns
            listOf(
                WebhookRecoveryView(
                    WebhookRecoveryRequest(
                        requestId = "recovery-1",
                        webhookId = "webhook-1",
                        scope = WebhookRecoveryScope.FAILED_LAST_24H,
                        requiredEvents = setOf("transaction.created"),
                        requiredEventsPayload = "[\"transaction.created\"]",
                        requiredEventsHash = "a".repeat(64),
                        idempotencyKey = "idem-1",
                        reason = "웹훅 공백 복구",
                        workTicket = "INC-100",
                        requestedAt = NOW.minusSeconds(120),
                        requestedBy = ACTOR,
                        approvedAt = NOW.minusSeconds(119),
                        approvedBy = APPROVER,
                    ),
                    listOf(
                        WebhookRecoveryEvent(
                            requestId = "recovery-1",
                            sequence = 1,
                            status = WebhookRecoveryEventStatus.STATUS_INTENT,
                            callType = WebhookRecoveryCallType.STATUS_QUERY,
                            calledAt = NOW.minusSeconds(61),
                            resultAt = null,
                            webhookStatus = null,
                            observedEvents = emptySet(),
                            observedEventsPayload = null,
                            observedEventsHash = null,
                            scopeFrom = null,
                            scopeTo = null,
                            scheduledNotificationCount = null,
                            responsePayload = null,
                            responseHash = null,
                            errorCode = null,
                            occurredAt = NOW.minusSeconds(61),
                            actor = ACTOR,
                        ),
                    ),
                ),
            )

        val recovery = service.executionGates().webhookRecoveries.single()

        assertThat(recovery.state).isEqualTo(WebhookRecoveryState.AMBIGUOUS)
        assertThat(recovery.latestEvent).isEqualTo(WebhookRecoveryEventStatus.STATUS_INTENT)
        assertThat(recovery.requestedByEmployeeNo).isEqualTo("810001")
        assertThat(recovery.approvedByEmployeeNo).isEqualTo("810002")
    }

    @Test
    fun `실행 원장이 열려 있어도 활성 release 문맥이 없으면 신규 sweep을 차단해 표시한다`() {
        every { governance.findExecutionGateScopes(any(), any(), any()) } returns
            listOf(AdminExecutionGateScope("BASE", ExecutionGateType.SWEEP, null, releaseContextReady = false))
        every { governance.findLatestExternalControls(any()) } returns emptyList()
        every { governance.findAllowanceRevocations(any()) } returns emptyList()
        every { governance.findWebhookRecoveries(any()) } returns emptyList()
        every { governance.findExecutionGateResumes(any(), any()) } returns emptyList()
        val releaseEnabled =
            AdminSweepReleaseProperties(
                batchSubmissionEnabled = true,
                tapBatchPolicyVerified = true,
                callbackVerified = true,
                universalGaslessVerified = true,
                sweepContractVerified = true,
                batchSubmissionEnabledNetworks = setOf("BASE"),
            )
        val service = AdminGovernanceQueryService(governance, Clock.fixed(NOW, ZoneOffset.UTC), jobs, releaseEnabled)

        val gate = service.executionGates().gates.single()

        assertThat(gate.state.name).isEqualTo("OPEN")
        assertThat(gate.newExecutionAllowed).isFalse()
        assertThat(gate.disabledReasons).contains("RELEASE_GATE_NOT_READY")
    }

    @Test
    fun `sweep 준비 상태는 BAT heartbeat와 같은 contract에 묶인 ceiling 통과 policy를 모두 확인한다`() {
        val releaseEnabled =
            AdminSweepReleaseProperties(
                batchSubmissionEnabled = true,
                tapBatchPolicyVerified = true,
                callbackVerified = true,
                universalGaslessVerified = true,
                sweepContractVerified = true,
                batchSubmissionEnabledNetworks = setOf("BASE"),
            )
        val ceilingEnabled =
            AdminPolicyHardCeilingProperties(
                executionEnabled = true,
                maximumBatchSize = 10,
                maximumAllowance = java.math.BigDecimal.TEN,
                maximumItemAmount = java.math.BigDecimal.TEN,
                maximumBatchAmount = java.math.BigDecimal.TEN,
                maximumBoostAttempts = 1,
            )
        every { governance.findContracts(NOW) } returns
            listOf(
                AdminContractSummary(
                    versionId = "contract-v1",
                    scopeId = "BASE:SWEEP",
                    network = "BASE",
                    use = "SWEEP",
                    version = "v1",
                    address = "0xabc",
                    state = "ACTIVE",
                    runtimeCodeHash = "a".repeat(64),
                    evidenceStatus = "VALID",
                    evidenceValidUntil = NOW.plusSeconds(300),
                    active = true,
                ),
            )
        every { governance.findPolicies(NOW) } returns
            listOf(
                AdminPolicySummary(
                    versionId = "policy-v1",
                    scopeId = "POLICY:BASE:USDC",
                    contractVersionId = "contract-v1",
                    versionNumber = 1,
                    schemaVersion = "v1",
                    state = "ACTIVE",
                    policyHash = "b".repeat(64),
                    ceilingPassed = true,
                    active = true,
                    registeredAt = NOW.minusSeconds(300),
                ),
            )
        every { governance.findExecutionGateScopes(NOW, any(), any()) } returns
            listOf(
                AdminExecutionGateScope("BASE", ExecutionGateType.SWEEP, null, releaseContextReady = true),
            )
        every { governance.findWebhookRuntimeObservation() } returns
            AdminWebhookRuntimeObservation(null, 0, 0, 0, 0)
        every { jobs.find(OperationalJobNames.SWEEP_BATCH_EXECUTION) } returns
            JobState(OperationalJobNames.SWEEP_BATCH_EXECUTION, "20260818005930", "20260818005930")
        val context =
            ActiveSweepRuntimeContext(
                network = "BASE",
                symbol = "USDC",
                policyVersionId = "policy-v1",
                policySnapshotHash = "c".repeat(64),
                policy =
                    com.whatto.bcm.domain.admin.SweepExecutionPolicy(
                        enabled = true,
                        minimumAmount = java.math.BigDecimal.ONE,
                        batchSize = 10,
                        allowanceCap = java.math.BigDecimal.TEN,
                        itemAmountCap = java.math.BigDecimal.TEN,
                        batchAmountCap = java.math.BigDecimal.TEN,
                        boostAttempts = 1,
                    ),
                contractVersionId = "contract-v1",
                contractEvidenceId = "evidence-v1",
                contractAddress = "0xabc",
            )
        val entries =
            listOf(
                context.attestationEntry(),
                RuntimeAttestationEntry.gate("SWEEP|BASE", 0, "OPEN"),
            )
        every { governance.findSweepRuntimeAttestationEntries(NOW) } returns entries
        val attestationJobName = RuntimeAttestation.jobName("sweep-attestation:", entries)
        every { jobs.find(attestationJobName) } returns
            JobState(attestationJobName, "20260818005930", "20260818005930")
        val service =
            AdminGovernanceQueryService(
                governance,
                Clock.fixed(NOW, ZoneOffset.UTC),
                jobs,
                releaseEnabled,
                ceilingEnabled,
            )

        val readiness = service.runtimeReadiness().sweep

        assertThat(readiness.enabled).isTrue()
        assertThat(readiness.state).isEqualTo("READY")
        assertThat(readiness.disabledReasons).isEmpty()
        assertThat(readiness.executorLastSucceededAt).isEqualTo(NOW.minusSeconds(30))

        val changedGateEntries =
            listOf(
                context.attestationEntry(),
                RuntimeAttestationEntry.gate("SWEEP|BASE", 1, "STOPPED"),
            )
        every { governance.findSweepRuntimeAttestationEntries(NOW) } returns changedGateEntries
        every {
            jobs.find(RuntimeAttestation.jobName("sweep-attestation:", changedGateEntries))
        } returns null

        val stoppedReadiness = service.runtimeReadiness().sweep

        assertThat(stoppedReadiness.enabled).isFalse()
        assertThat(stoppedReadiness.disabledReasons).contains("SWEEP_RUNTIME_NOT_ATTESTED")
    }

    private fun confirmedEvidence(validUntil: Instant) =
        AdminExternalControlEvidenceSummary(
            evidenceId = "external-evidence-1",
            network = "BASE",
            contractVersionId = "contract-v1",
            status = EmergencyExternalControlStatus.CONFIRMED,
            completionReady = true,
            snapshotHash = "a".repeat(64),
            tapSourceId = "tap-policy-api",
            tapBlocked = true,
            pinnedBlockNumber = BigInteger.valueOf(1234),
            expectedOperatorSetHash = "b".repeat(64),
            firstEndpointId = "rpc-a",
            firstPaused = true,
            firstOperatorSetHash = "b".repeat(64),
            secondEndpointId = "rpc-b",
            secondPaused = true,
            secondOperatorSetHash = "b".repeat(64),
            observedAt = NOW.minusSeconds(300),
            validUntil = validUntil,
            reason = "비상 외부 통제 확인",
            workTicket = "SEC-1062",
            actorEmployeeNo = "810001",
            issues = emptyList(),
        )

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-18T01:00:00Z")
        val ACTOR = AdminActor("810001", "0001", setOf(AdminRole.BCM_OPERATOR))
        val APPROVER = AdminActor("810002", "0001", setOf(AdminRole.BCM_APPROVER))
    }
}
