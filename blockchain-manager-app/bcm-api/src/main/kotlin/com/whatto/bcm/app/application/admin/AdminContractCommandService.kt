package com.whatto.bcm.app.application.admin

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminChangeRequest
import com.whatto.bcm.domain.admin.AdminContractEvidence
import com.whatto.bcm.domain.admin.AdminContractRepository
import com.whatto.bcm.domain.admin.AdminContractVersion
import com.whatto.bcm.domain.admin.AdminDecisionRecord
import com.whatto.bcm.domain.admin.AdminPolicyLifecycle
import com.whatto.bcm.domain.admin.AdminPolicyRepository
import com.whatto.bcm.domain.admin.AdminRole
import com.whatto.bcm.domain.admin.ChangeRisk
import com.whatto.bcm.domain.admin.ChangeTargetType
import com.whatto.bcm.domain.admin.ContractEvidenceCandidate
import com.whatto.bcm.domain.admin.ContractEvidenceEvaluator
import com.whatto.bcm.domain.admin.ContractExpectedState
import com.whatto.bcm.domain.admin.ContractVerificationPort
import com.whatto.bcm.domain.admin.ContractVerificationResult
import com.whatto.bcm.domain.admin.ExternalControlEvidence
import com.whatto.bcm.domain.admin.PolicyChangeRequest
import com.whatto.bcm.domain.event.EventIdGenerator
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class AdminContractCommandService(
    private val contracts: AdminContractRepository,
    private val policies: AdminPolicyRepository,
    private val verification: ContractVerificationPort,
    private val transactions: TransactionRunner,
    private val ids: EventIdGenerator,
    private val clock: Clock,
) {
    fun register(command: RegisterContractCommand): AdminContractVersion =
        transactions.run {
            requireOperator(command.actor)
            check(command.scopeId == "${command.network}:${command.use}") { "contract scope must match network and use" }
            val now = now()
            val version =
                AdminContractVersion(
                    versionId = ids.nextId(),
                    scopeId = command.scopeId,
                    network = command.network,
                    use = command.use,
                    version = command.version,
                    address = command.address,
                    releaseCommit = command.releaseCommit,
                    artifactHash = command.artifactHash,
                    abiHash = command.abiHash,
                    runtimeCodeHash = command.runtimeCodeHash,
                    deploymentTransactionHash = command.deploymentTransactionHash,
                    deploymentBlockNumber = command.deploymentBlockNumber,
                    immutableValues = command.immutableValues,
                    immutableHash = sha256(command.immutableValues),
                    ceilingSnapshot = command.ceilingSnapshot,
                    ceilingHash = sha256(command.ceilingSnapshot),
                    releaseUri = command.releaseUri,
                    registeredAt = now,
                    registeredBy = command.actor,
                )
            contracts.insertVersion(version).also {
                contracts.initializeBinding(version, command.actor, now)
            }
        }

    fun verify(command: VerifyContractCommand): AdminContractEvidence =
        transactions.run {
            requireOperator(command.actor)
            val version =
                contracts.findVersion(command.versionId)
                    ?: throw ResourceNotFoundException("contractVersion", command.versionId)
            val now = now()
            val collected = verification.collect(version, now)
            check(collected.firstEndpointId != collected.secondEndpointId) {
                "contract verification endpoints must be independent"
            }
            val evaluation = ContractEvidenceEvaluator.evaluate(collected.candidate, now)
            val documentHash = sha256(collected.documentEvidence)
            val snapshot = evidenceSnapshot(version, collected, documentHash)
            contracts.insertEvidence(
                AdminContractEvidence(
                    evidenceId = ids.nextId(),
                    contractVersionId = version.versionId,
                    snapshotHash = sha256(snapshot),
                    candidate = collected.candidate,
                    evaluation = evaluation,
                    documentEvidence = collected.documentEvidence,
                    documentEvidenceHash = documentHash,
                    firstEndpointId = collected.firstEndpointId,
                    secondEndpointId = collected.secondEndpointId,
                    registeredBy = command.actor,
                ),
            )
        }

    fun requestActivation(command: RequestContractActivationCommand): AdminChangeRequest =
        transactions.run {
            requireOperator(command.actor)
            val version =
                contracts.findVersion(command.versionId)
                    ?: throw ResourceNotFoundException("contractVersion", command.versionId)
            val evidence =
                contracts.findLatestEvidence(version.versionId)
                    ?: throw ResourceNotFoundException("contractEvidence", version.versionId)
            check(evidence.evaluation.activationReady && now().isBefore(evidence.candidate.validUntil)) {
                "contract evidence is not activation ready"
            }
            val binding =
                contracts.lockBinding(version.scopeId)
                    ?: throw ResourceNotFoundException("contractBinding", version.scopeId)
            val before = binding.activeVersionId?.let(contracts::findVersion)
            val diff = contractDiff(before, version)
            val impact = command.impact.canonicalJson()
            val diffHash = sha256(diff)
            val impactHash = sha256(impact)
            val snapshotHash =
                sha256(
                    listOf(
                        version.scopeId,
                        binding.revision,
                        binding.activeVersionId.orEmpty(),
                        version.versionId,
                        evidence.evidenceId,
                        evidence.snapshotHash,
                        diffHash,
                        impactHash,
                    ).joinToString("|"),
                )
            val requestedAt = now()
            val candidate =
                AdminChangeRequest(
                    lifecycle =
                        PolicyChangeRequest(
                            requestId = ids.nextId(),
                            requester = command.actor,
                            risk = ChangeRisk.SECURITY,
                            targetSnapshotHash = snapshotHash,
                            baseBindingRevision = binding.revision,
                            targetVersionId = version.versionId,
                            expiresAt = requestedAt.plus(command.validFor),
                        ),
                    targetType = ChangeTargetType.CONTRACT,
                    scopeId = version.scopeId,
                    beforeVersionId = binding.activeVersionId,
                    evidenceId = evidence.evidenceId,
                    diffPayload = diff,
                    diffHash = diffHash,
                    impactPayload = impact,
                    impactHash = impactHash,
                    reason = command.reason,
                    workTicket = command.workTicket,
                    idempotencyKey = command.idempotencyKey,
                    requestedRole = AdminRole.BCM_OPERATOR,
                    requestedAt = requestedAt,
                )
            policies.findChangeRequestByIdempotency(command.actor.employeeNo, command.idempotencyKey)?.let { existing ->
                if (existing.sameContractRequestAs(candidate)) return@run existing
                throw ConflictException("contractChangeIdempotency", command.idempotencyKey)
            }
            policies.insertChangeRequest(candidate)
        }

    fun activate(command: ActivateContractChangeCommand): com.whatto.bcm.domain.admin.AdminActivationRecord =
        transactions.run {
            policies.findSuccessfulActivation(command.requestId, command.idempotencyKey)?.let { return@run it }
            val request =
                policies.findChangeRequest(command.requestId)
                    ?: throw ResourceNotFoundException("contractChangeRequest", command.requestId)
            check(request.targetType == ChangeTargetType.CONTRACT) { "request ${command.requestId} is not a contract change" }
            val version =
                contracts.findVersion(request.lifecycle.targetVersionId)
                    ?: throw ResourceNotFoundException("contractVersion", request.lifecycle.targetVersionId)
            val approvedEvidence =
                request.evidenceId?.let(contracts::findEvidence)
                    ?: throw ResourceNotFoundException("contractEvidence", request.evidenceId.orEmpty())
            val binding =
                contracts.lockBinding(request.scopeId)
                    ?: throw ResourceNotFoundException("contractBinding", request.scopeId)
            val now = now()
            val rechecked = verification.collect(version, now)
            val evaluation = ContractEvidenceEvaluator.evaluate(rechecked.candidate, now)
            val targetReady =
                evaluation.activationReady &&
                    now.isBefore(approvedEvidence.candidate.validUntil) &&
                    approvedEvidence.matches(rechecked)
            AdminPolicyLifecycle.validateActivation(
                request.lifecycle,
                policies.findDecisions(request.lifecycle.requestId).map(AdminDecisionRecord::decision),
                binding.revision,
                request.lifecycle.targetSnapshotHash,
                targetReady,
                now,
            )
            val recheckEvidence = toEvidence(version, rechecked, evaluation, command.actor)
            if (recheckEvidence.snapshotHash != approvedEvidence.snapshotHash) {
                contracts.insertEvidence(recheckEvidence)
            }
            val expectedState = binding.canonicalJson()
            val requestHash = sha256("${request.lifecycle.requestId}|${command.idempotencyKey}|${request.lifecycle.targetSnapshotHash}")
            val correlationId = ids.nextId()
            policies.insertActivationIntent(
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
            val activated = contracts.activateBinding(request, command.actor, now)
            val observedState = activated.canonicalJson()
            policies.insertActivationSuccess(
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
        }

    private fun evidenceSnapshot(
        version: AdminContractVersion,
        result: ContractVerificationResult,
        documentHash: String,
    ): String {
        val candidate = result.candidate
        return listOf(
            version.versionId,
            candidate.expected.chainId,
            candidate.expected.codeHash,
            candidate.expected.immutableHash,
            candidate.expected.pinnedBlockNumber,
            result.firstEndpointId,
            candidate.first?.chainId,
            candidate.first?.codeHash,
            candidate.first?.immutableHash,
            result.secondEndpointId,
            candidate.second?.chainId,
            candidate.second?.codeHash,
            candidate.second?.immutableHash,
            candidate.controls,
            candidate.observedAt,
            candidate.validUntil,
            documentHash,
        ).joinToString("|")
    }

    private fun toEvidence(
        version: AdminContractVersion,
        result: ContractVerificationResult,
        evaluation: com.whatto.bcm.domain.admin.ContractEvidenceEvaluation,
        actor: AdminActor,
    ): AdminContractEvidence {
        val documentHash = sha256(result.documentEvidence)
        return AdminContractEvidence(
            evidenceId = ids.nextId(),
            contractVersionId = version.versionId,
            snapshotHash = sha256(evidenceSnapshot(version, result, documentHash)),
            candidate = result.candidate,
            evaluation = evaluation,
            documentEvidence = result.documentEvidence,
            documentEvidenceHash = documentHash,
            firstEndpointId = result.firstEndpointId,
            secondEndpointId = result.secondEndpointId,
            registeredBy = actor,
        )
    }

    private fun AdminContractEvidence.matches(result: ContractVerificationResult): Boolean =
        firstEndpointId == result.firstEndpointId &&
            secondEndpointId == result.secondEndpointId &&
            candidate.expected == result.candidate.expected &&
            candidate.first?.copy(observedAt = Instant.EPOCH) == result.candidate.first?.copy(observedAt = Instant.EPOCH) &&
            candidate.second?.copy(observedAt = Instant.EPOCH) == result.candidate.second?.copy(observedAt = Instant.EPOCH) &&
            result.candidate.controls.allPassed()

    private fun contractDiff(
        before: AdminContractVersion?,
        after: AdminContractVersion,
    ): String =
        """{"after":{"address":"${after.address}","runtimeCodeHash":"${after.runtimeCodeHash}","versionId":"${after.versionId}"},"before":${before?.let {
            "{\"address\":\"${it.address}\",\"runtimeCodeHash\":\"${it.runtimeCodeHash}\",\"versionId\":\"${it.versionId}\"}"
        } ?: "null"}}"""

    private fun ContractActivationImpact.canonicalJson(): String =
        """{"activeAllowances":$activeAllowances,"affectedAccounts":$affectedAccounts,"openExecutions":$openExecutions}"""

    private fun AdminChangeRequest.sameContractRequestAs(other: AdminChangeRequest): Boolean =
        targetType == other.targetType &&
            scopeId == other.scopeId &&
            lifecycle.targetVersionId == other.lifecycle.targetVersionId &&
            lifecycle.baseBindingRevision == other.lifecycle.baseBindingRevision &&
            evidenceId == other.evidenceId &&
            diffHash == other.diffHash &&
            impactHash == other.impactHash &&
            reason == other.reason &&
            workTicket == other.workTicket

    private fun com.whatto.bcm.domain.admin.AdminContractBinding.canonicalJson(): String =
        """{"activeVersionId":${activeVersionId?.let {
            "\"$it\""
        } ?: "null"},"evidenceId":${evidenceId?.let {
            "\"$it\""
        } ?: "null"},"revision":$revision,"scopeId":"$scopeId","snapshotHash":"$snapshotHash"}"""

    private fun requireOperator(actor: AdminActor) {
        check(AdminRole.BCM_OPERATOR in actor.roles) { "BCM_OPERATOR role is required" }
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.SECONDS)

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

data class RegisterContractCommand(
    val scopeId: String,
    val network: String,
    val use: String,
    val version: String,
    val address: String,
    val releaseCommit: String,
    val artifactHash: String,
    val abiHash: String,
    val runtimeCodeHash: String,
    val deploymentTransactionHash: String,
    val deploymentBlockNumber: BigInteger,
    val immutableValues: String,
    val ceilingSnapshot: String,
    val releaseUri: String,
    val actor: AdminActor,
)

data class VerifyContractCommand(
    val versionId: String,
    val actor: AdminActor,
)

data class ContractActivationImpact(
    val affectedAccounts: Int,
    val openExecutions: Int,
    val activeAllowances: Int,
)

data class RequestContractActivationCommand(
    val versionId: String,
    val impact: ContractActivationImpact,
    val reason: String,
    val workTicket: String,
    val idempotencyKey: String,
    val actor: AdminActor,
    val validFor: Duration = Duration.ofHours(3),
)

data class ActivateContractChangeCommand(
    val requestId: String,
    val idempotencyKey: String,
    val actor: AdminActor,
)

@Configuration
class ContractVerificationFallbackConfig {
    @Bean
    @ConditionalOnMissingBean(ContractVerificationPort::class)
    fun unavailableContractVerification(): ContractVerificationPort =
        ContractVerificationPort { version, now ->
            ContractVerificationResult(
                candidate =
                    ContractEvidenceCandidate(
                        expected =
                            ContractExpectedState(
                                chainId = 0,
                                codeHash = version.runtimeCodeHash,
                                immutableHash = version.immutableHash,
                                pinnedBlockNumber = version.deploymentBlockNumber,
                            ),
                        first = null,
                        second = null,
                        controls = ExternalControlEvidence(false, false, false, false, false, false),
                        observedAt = now,
                        validUntil = now.plus(Duration.ofMinutes(5)),
                    ),
                firstEndpointId = "UNCONFIGURED_RPC_1",
                secondEndpointId = "UNCONFIGURED_RPC_2",
                documentEvidence = "{\"source\":\"UNCONFIGURED\"}",
            )
        }
}
