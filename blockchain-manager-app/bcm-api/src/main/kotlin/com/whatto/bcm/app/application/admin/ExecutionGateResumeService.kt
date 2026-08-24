package com.whatto.bcm.app.application.admin

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminChangeRequest
import com.whatto.bcm.domain.admin.AdminContractRepository
import com.whatto.bcm.domain.admin.AdminPolicyLifecycle
import com.whatto.bcm.domain.admin.AdminPolicyRepository
import com.whatto.bcm.domain.admin.AdminRole
import com.whatto.bcm.domain.admin.AllowanceRevocationExecutionStatus
import com.whatto.bcm.domain.admin.AllowanceRevocationRepository
import com.whatto.bcm.domain.admin.ChangeRisk
import com.whatto.bcm.domain.admin.ChangeTargetType
import com.whatto.bcm.domain.admin.EmergencyExternalControlVerificationPort
import com.whatto.bcm.domain.admin.ExecutionGateEvent
import com.whatto.bcm.domain.admin.ExecutionGateRepository
import com.whatto.bcm.domain.admin.ExecutionGateResumeCheck
import com.whatto.bcm.domain.admin.ExecutionGateResumeCheckCandidate
import com.whatto.bcm.domain.admin.ExecutionGateResumeEvaluator
import com.whatto.bcm.domain.admin.ExecutionGateResumeRepository
import com.whatto.bcm.domain.admin.ExecutionGateResumeSnapshot
import com.whatto.bcm.domain.admin.ExecutionGateStatus
import com.whatto.bcm.domain.admin.ExecutionGateType
import com.whatto.bcm.domain.admin.PolicyChangeRequest
import com.whatto.bcm.domain.event.EventIdGenerator
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class ExecutionGateResumeService(
    private val gates: ExecutionGateRepository,
    private val resumes: ExecutionGateResumeRepository,
    private val policies: AdminPolicyRepository,
    private val contracts: AdminContractRepository,
    private val revocations: AllowanceRevocationRepository,
    private val verification: EmergencyExternalControlVerificationPort,
    private val transactions: TransactionRunner,
    private val ids: EventIdGenerator,
    private val clock: Clock,
) {
    fun request(command: RequestExecutionGateResumeCommand): AdminChangeRequest =
        transactions.run {
            validateRequest(command)
            policies.findChangeRequestByIdempotency(command.actor.employeeNo, command.idempotencyKey)?.let { existing ->
                val snapshot = resumes.findSnapshot(existing.lifecycle.targetVersionId)
                if (snapshot != null && existing.sameResumeRequest(command, snapshot)) return@run existing
                throw ConflictException("executionGateResumeIdempotency", command.idempotencyKey)
            }
            val stopped = currentStopped(command.network, command.type)
            val binding =
                contracts.lockBinding("${command.network}:SWEEP")
                    ?: throw ResourceNotFoundException("contractBinding", "${command.network}:SWEEP")
            val contractVersionId =
                binding.activeVersionId
                    ?: throw ResourceNotFoundException("activeContract", "${command.network}:SWEEP")
            val evidence =
                contracts.findLatestEvidence(contractVersionId)
                    ?: throw ResourceNotFoundException("contractEvidence", contractVersionId)
            val now = now()
            check(evidence.evaluation.activationReady && now.isBefore(evidence.candidate.validUntil)) {
                "contract evidence is not resume ready"
            }
            val revocation =
                revocations.findExecution(command.revocationExecutionId)
                    ?: throw ResourceNotFoundException("allowanceRevocationExecution", command.revocationExecutionId)
            check(revocation.status == AllowanceRevocationExecutionStatus.COMPLETED) {
                "allowance revocation is not completed"
            }
            check(
                revocation.execution.network == command.network &&
                    revocation.execution.contractVersionId == contractVersionId &&
                    revocation.execution.contractBindingRevision == binding.revision,
            ) { "allowance revocation does not match current contract binding" }

            val resumeId = ids.nextId()
            val targetHash =
                snapshotHash(
                    command.network,
                    command.type,
                    stopped.eventId,
                    contractVersionId,
                    binding.revision,
                    evidence.evidenceId,
                    command.revocationExecutionId,
                    command.expectedOperatorSetHash,
                    command.causeEvidenceUri,
                    command.causeEvidenceHash,
                )
            val snapshot =
                ExecutionGateResumeSnapshot(
                    resumeId,
                    command.network,
                    command.type,
                    stopped.eventId,
                    contractVersionId,
                    binding.revision,
                    evidence.evidenceId,
                    command.revocationExecutionId,
                    command.expectedOperatorSetHash,
                    command.causeEvidenceUri,
                    command.causeEvidenceHash,
                    targetHash,
                    now,
                    command.actor,
                )
            val diff =
                """{"from":"STOPPED","to":"OPEN","gateType":"${command.type.name}","network":"${command.network}"}"""
            val impact =
                """{"contractVersionId":"$contractVersionId","revocationExecutionId":"${command.revocationExecutionId}"}"""
            val request =
                AdminChangeRequest(
                    PolicyChangeRequest(
                        ids.nextId(),
                        command.actor,
                        ChangeRisk.RESUME,
                        targetHash,
                        binding.revision,
                        resumeId,
                        now.plus(command.validFor),
                    ),
                    ChangeTargetType.EXECUTION_GATE,
                    "${command.network}:${command.type.name}",
                    null,
                    evidence.evidenceId,
                    diff,
                    sha256(diff),
                    impact,
                    sha256(impact),
                    command.reason,
                    command.workTicket,
                    command.idempotencyKey,
                    AdminRole.BCM_OPERATOR,
                    now,
                )
            resumes.insertSnapshot(snapshot)
            policies.insertChangeRequest(request)
        }

    fun resume(command: ResumeExecutionGateCommand): ExecutionGateEvent {
        check(AdminRole.BCM_OPERATOR in command.actor.roles) { "BCM_OPERATOR role is required" }
        require(command.idempotencyKey.isNotBlank()) { "execution gate resume idempotency key is required" }
        gates.findByIdempotency(command.actor.employeeNo, command.idempotencyKey)?.let { existing ->
            if (existing.status == ExecutionGateStatus.RESUMED && existing.resumeRequestId == command.requestId) return existing
            throw ConflictException("executionGateResumeIdempotency", command.idempotencyKey)
        }
        val view =
            resumes.findViewByRequest(command.requestId)
                ?: throw ResourceNotFoundException("executionGateResumeRequest", command.requestId)
        check(view.request.targetType == ChangeTargetType.EXECUTION_GATE) { "request is not an execution gate resume" }
        val version =
            contracts.findVersion(view.snapshot.contractVersionId)
                ?: throw ResourceNotFoundException("contractVersion", view.snapshot.contractVersionId)
        val check = observe(command, view.snapshot, version)
        check(check.evaluation.resumeReady) { "execution gate resume external check is not ready" }

        return transactions.run {
            gates.findByIdempotency(command.actor.employeeNo, command.idempotencyKey)?.let { existing ->
                if (existing.status == ExecutionGateStatus.RESUMED && existing.resumeRequestId == command.requestId) return@run existing
                throw ConflictException("executionGateResumeIdempotency", command.idempotencyKey)
            }
            val request =
                policies.findChangeRequest(command.requestId)
                    ?: throw ResourceNotFoundException("executionGateResumeRequest", command.requestId)
            val snapshot =
                resumes.findSnapshot(request.lifecycle.targetVersionId)
                    ?: throw ResourceNotFoundException("executionGateResume", request.lifecycle.targetVersionId)
            val current = currentStopped(snapshot.network, snapshot.type)
            val binding =
                contracts.lockBinding("${snapshot.network}:SWEEP")
                    ?: throw ResourceNotFoundException("contractBinding", "${snapshot.network}:SWEEP")
            val evidence =
                contracts.findLatestEvidence(snapshot.contractVersionId)
                    ?: throw ResourceNotFoundException("contractEvidence", snapshot.contractVersionId)
            val revocation =
                revocations.findExecution(snapshot.revocationExecutionId)
                    ?: throw ResourceNotFoundException("allowanceRevocationExecution", snapshot.revocationExecutionId)
            val now = now()
            val liveHash =
                snapshotHash(
                    snapshot.network,
                    snapshot.type,
                    current.eventId,
                    binding.activeVersionId.orEmpty(),
                    binding.revision,
                    evidence.evidenceId,
                    snapshot.revocationExecutionId,
                    snapshot.expectedOperatorSetHash,
                    snapshot.causeEvidenceUri,
                    snapshot.causeEvidenceHash,
                )
            val ready =
                current.eventId == snapshot.stoppedEventId &&
                    binding.activeVersionId == snapshot.contractVersionId &&
                    binding.revision == snapshot.contractBindingRevision &&
                    evidence.evidenceId == snapshot.contractEvidenceId &&
                    evidence.evaluation.activationReady &&
                    now.isBefore(evidence.candidate.validUntil) &&
                    revocation.status == AllowanceRevocationExecutionStatus.COMPLETED &&
                    check.evaluation.resumeReady &&
                    now.isBefore(check.candidate.validUntil)
            AdminPolicyLifecycle.validateActivation(
                request.lifecycle,
                policies.findDecisions(request.lifecycle.requestId).map { it.decision },
                binding.revision,
                liveHash,
                ready,
                now,
            )
            val expectedState = """{"gateEventId":"${current.eventId}","state":"STOPPED"}"""
            val requestHash = sha256("${request.lifecycle.requestId}|${command.idempotencyKey}|${request.lifecycle.targetSnapshotHash}")
            val correlationId = ids.nextId()
            resumes.insertResumeIntent(
                ids.nextId(),
                correlationId,
                request,
                command.idempotencyKey,
                requestHash,
                expectedState,
                sha256(expectedState),
                command.actor,
                now,
            )
            val resumed =
                gates.insert(
                    ExecutionGateEvent(
                        ids.nextId(),
                        snapshot.network,
                        snapshot.type,
                        current.sequence + 1,
                        ExecutionGateStatus.RESUMED,
                        request.reason,
                        request.workTicket,
                        command.idempotencyKey,
                        now,
                        command.actor,
                        request.lifecycle.requestId,
                    ),
                )
            val observedState = """{"gateEventId":"${resumed.eventId}","state":"RESUMED"}"""
            resumes.insertResumeSuccess(
                ids.nextId(),
                correlationId,
                request,
                command.idempotencyKey,
                requestHash,
                expectedState,
                sha256(expectedState),
                observedState,
                sha256(observedState),
                command.actor,
                now,
            )
            resumed
        }
    }

    private fun observe(
        command: ResumeExecutionGateCommand,
        snapshot: ExecutionGateResumeSnapshot,
        version: com.whatto.bcm.domain.admin.AdminContractVersion,
    ): ExecutionGateResumeCheck {
        resumes.findCheckByIdempotency(command.actor.employeeNo, command.idempotencyKey)?.let { existing ->
            if (existing.resumeId == snapshot.resumeId) return existing
            throw ConflictException("executionGateResumeCheckIdempotency", command.idempotencyKey)
        }
        val now = now()
        val collected =
            try {
                verification.collect(version, now)
            } catch (error: RuntimeException) {
                log.warn(
                    "재개 외부 상태 관찰 실패 resumeId={} exceptionType={}",
                    snapshot.resumeId,
                    error.javaClass.name,
                )
                EmergencyExternalControlObservationService.unavailableCandidate(version, now, "RESUME_SOURCE_ERROR")
            }
        val candidate =
            ExecutionGateResumeCheckCandidate(
                collected.tapSourceId,
                collected.tap,
                collected.pinnedBlockNumber,
                snapshot.expectedOperatorSetHash,
                collected.firstEndpointId,
                collected.first,
                collected.secondEndpointId,
                collected.second,
                collected.sourceErrors,
                collected.observedAt,
                collected.validUntil,
            )
        val evaluation = ExecutionGateResumeEvaluator.evaluate(candidate, now)
        val sequence = (resumes.findLatestCheck(snapshot.resumeId)?.sequence ?: 0) + 1
        val hash = sha256(candidate.canonical(evaluation))
        val check =
            ExecutionGateResumeCheck(
                ids.nextId(),
                snapshot.resumeId,
                sequence,
                hash,
                candidate,
                evaluation,
                command.idempotencyKey,
                command.actor,
            )
        return transactions.run {
            resumes.findCheckByIdempotency(command.actor.employeeNo, command.idempotencyKey)?.let { return@run it }
            resumes.appendCheck(check)
        }
    }

    private fun currentStopped(
        network: String,
        type: ExecutionGateType,
    ): ExecutionGateEvent =
        gates
            .findCurrent(network, type)
            ?.takeIf { it.status == ExecutionGateStatus.STOPPED }
            ?: throw ConflictException("executionGateNotStopped", "$network:$type")

    private fun validateRequest(command: RequestExecutionGateResumeCommand) {
        check(AdminRole.BCM_OPERATOR in command.actor.roles) { "BCM_OPERATOR role is required" }
        require(command.reason.isNotBlank()) { "execution gate resume reason is required" }
        require(command.workTicket.isNotBlank()) { "execution gate resume work ticket is required" }
        require(command.idempotencyKey.isNotBlank()) { "execution gate resume idempotency key is required" }
        require(command.causeEvidenceUri.isNotBlank()) { "cause evidence URI is required" }
        require(command.expectedOperatorSetHash.isSha256()) { "expected operator set hash must be lowercase SHA-256" }
        require(command.causeEvidenceHash.isSha256()) { "cause evidence hash must be lowercase SHA-256" }
        require(!command.validFor.isZero && !command.validFor.isNegative) { "resume request validity must be positive" }
    }

    private fun AdminChangeRequest.sameResumeRequest(
        command: RequestExecutionGateResumeCommand,
        snapshot: ExecutionGateResumeSnapshot,
    ): Boolean =
        targetType == ChangeTargetType.EXECUTION_GATE &&
            scopeId == "${command.network}:${command.type.name}" &&
            reason == command.reason &&
            workTicket == command.workTicket &&
            lifecycle.requester.branchCode == command.actor.branchCode &&
            lifecycle.expiresAt == requestedAt.plus(command.validFor).truncatedTo(ChronoUnit.SECONDS) &&
            snapshot.network == command.network &&
            snapshot.type == command.type &&
            snapshot.revocationExecutionId == command.revocationExecutionId &&
            snapshot.expectedOperatorSetHash == command.expectedOperatorSetHash &&
            snapshot.causeEvidenceUri == command.causeEvidenceUri &&
            snapshot.causeEvidenceHash == command.causeEvidenceHash

    private fun snapshotHash(
        network: String,
        type: ExecutionGateType,
        stoppedEventId: String,
        contractVersionId: String,
        bindingRevision: Long,
        evidenceId: String,
        revocationExecutionId: String,
        expectedOperatorSetHash: String,
        causeEvidenceUri: String,
        causeEvidenceHash: String,
    ) = sha256(
        listOf(
            network,
            type.name,
            stoppedEventId,
            contractVersionId,
            bindingRevision,
            evidenceId,
            revocationExecutionId,
            expectedOperatorSetHash,
            causeEvidenceUri,
            causeEvidenceHash,
        ).joinToString("|"),
    )

    private fun ExecutionGateResumeCheckCandidate.canonical(evaluation: com.whatto.bcm.domain.admin.ExecutionGateResumeCheckEvaluation) =
        listOf(
            tapSourceId,
            tap?.blocked,
            tap?.observedAt,
            pinnedBlockNumber,
            expectedOperatorSetHash,
            firstEndpointId,
            first?.blockNumber,
            first?.paused,
            first?.operatorSetHash,
            first?.observedAt,
            secondEndpointId,
            second?.blockNumber,
            second?.paused,
            second?.operatorSetHash,
            second?.observedAt,
            sourceErrors.joinToString(","),
            observedAt,
            validUntil,
            evaluation.status,
            evaluation.issues.joinToString(","),
        ).joinToString("|")

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.SECONDS)

    private fun String.isSha256() = matches(Regex("[0-9a-f]{64}"))

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private val log = LoggerFactory.getLogger(ExecutionGateResumeService::class.java)
    }
}

data class RequestExecutionGateResumeCommand(
    val network: String,
    val type: ExecutionGateType,
    val revocationExecutionId: String,
    val expectedOperatorSetHash: String,
    val causeEvidenceUri: String,
    val causeEvidenceHash: String,
    val reason: String,
    val workTicket: String,
    val idempotencyKey: String,
    val validFor: Duration,
    val actor: AdminActor,
)

data class ResumeExecutionGateCommand(
    val requestId: String,
    val idempotencyKey: String,
    val actor: AdminActor,
)
