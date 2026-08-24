package com.whatto.bcm.app.bat.sweep

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminContractRepository
import com.whatto.bcm.domain.admin.AdminDecisionRecord
import com.whatto.bcm.domain.admin.AdminPolicyLifecycle
import com.whatto.bcm.domain.admin.AdminPolicyRepository
import com.whatto.bcm.domain.admin.AdminRole
import com.whatto.bcm.domain.admin.AllowanceRevocationEvent
import com.whatto.bcm.domain.admin.AllowanceRevocationEventStatus
import com.whatto.bcm.domain.admin.AllowanceRevocationRepository
import com.whatto.bcm.domain.admin.AllowanceRevocationTarget
import com.whatto.bcm.domain.admin.AllowanceRevocationView
import com.whatto.bcm.domain.admin.ChangeTargetType
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.RelayRejectedException
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import com.whatto.bcm.domain.exception.SubmissionInProgressException
import com.whatto.bcm.domain.submission.SubmissionRecordRepository
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.sweep.Erc20ContractPort
import com.whatto.bcm.domain.sweep.SweepAllowanceObservation
import com.whatto.bcm.domain.sweep.SweepAuthorizationRepository
import com.whatto.bcm.domain.sweep.SweepAuthorizationStatus
import com.whatto.bcm.support.submission.SubmissionRequestHashes
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

data class ReserveAllowanceRevocationCommand(
    val requestId: String,
    val idempotencyKey: String,
    val actor: AdminActor,
)

data class ProcessAllowanceRevocationItemCommand(
    val executionId: String,
    val itemSequence: Int,
    val actor: AdminActor,
)

@Service
class AllowanceRevocationExecutionService(
    private val revocations: AllowanceRevocationRepository,
    private val policies: AdminPolicyRepository,
    private val contracts: AdminContractRepository,
    private val authorizations: SweepAuthorizationRepository,
    private val erc20: Erc20ContractPort,
    private val submissionRecords: SubmissionRecordRepository,
    private val contractCalls: SweepContractCallSubmitter,
    private val transactions: TransactionRunner,
    private val ids: AllowanceRevocationIdGenerator,
    private val clock: Clock,
) {
    fun reserve(command: ReserveAllowanceRevocationCommand): AllowanceRevocationView =
        try {
            transactions.run {
                requireOperator(command.actor)
                val request =
                    policies.findChangeRequest(command.requestId)
                        ?: throw ResourceNotFoundException("allowanceRevocationRequest", command.requestId)
                check(request.targetType == ChangeTargetType.ALLOWANCE_REVOKE) {
                    "request ${command.requestId} is not an allowance revocation"
                }
                val view =
                    revocations.findExecution(request.lifecycle.targetVersionId)
                        ?: throw ResourceNotFoundException("allowanceRevocationExecution", request.lifecycle.targetVersionId)
                if (view.events.isNotEmpty()) {
                    check(view.events.count { it.status == AllowanceRevocationEventStatus.RESERVED } == view.targets.size) {
                        "allowance revocation reservation is incomplete"
                    }
                    return@run view
                }
                val binding =
                    contracts.lockBinding(request.scopeId)
                        ?: throw ResourceNotFoundException("contractBinding", request.scopeId)
                val now = now()
                AdminPolicyLifecycle.validateActivation(
                    request.lifecycle,
                    policies.findDecisions(request.lifecycle.requestId).map(AdminDecisionRecord::decision),
                    binding.revision,
                    view.execution.targetSnapshotHash,
                    binding.activeVersionId == view.execution.contractVersionId,
                    now,
                )
                val requestHash = sha256("${request.lifecycle.requestId}|${command.idempotencyKey}|${view.execution.targetSnapshotHash}")
                val expectedState =
                    """{"contractVersionId":"${view.execution.contractVersionId}","targetSnapshotHash":"${view.execution.targetSnapshotHash}"}"""
                revocations.insertExecutionIntent(
                    ids.nextId(),
                    ids.nextId(),
                    request,
                    command.idempotencyKey,
                    requestHash,
                    expectedState,
                    sha256(expectedState),
                    command.actor,
                    now,
                )
                view.targets.forEach { target ->
                    revocations.appendEvent(event(target, 1, AllowanceRevocationEventStatus.RESERVED, command.actor, RESERVED_OBSERVATION))
                }
                requireNotNull(revocations.findExecution(view.execution.executionId))
            }
        } catch (conflict: ConflictException) {
            val request = policies.findChangeRequest(command.requestId) ?: throw conflict
            revocations.findExecution(request.lifecycle.targetVersionId)?.takeIf { it.events.isNotEmpty() } ?: throw conflict
        }

    fun processItem(command: ProcessAllowanceRevocationItemCommand): AllowanceRevocationView {
        requireOperatorOrSystem(command.actor)
        val view =
            revocations.findExecution(command.executionId)
                ?: throw ResourceNotFoundException("allowanceRevocationExecution", command.executionId)
        val target =
            view.targets.singleOrNull { it.itemSequence == command.itemSequence }
                ?: throw ResourceNotFoundException("allowanceRevocationItem", "${command.executionId}:${command.itemSequence}")
        val latest =
            view.events.filter { it.itemSequence == command.itemSequence }.maxByOrNull { it.eventSequence }
                ?: throw IllegalStateException("allowance revocation item must be reserved before processing")
        if (latest.status == AllowanceRevocationEventStatus.ZERO_CONFIRMED) {
            return view
        }
        val observation = observe(target)
        if (BigDecimal(observation.amount).signum() == 0) {
            return confirmZero(target, observation, command.actor)
        }
        if (latest.status == AllowanceRevocationEventStatus.SUBMITTED) {
            return recordPendingObservation(target, observation)
        }
        val callData = erc20.approvalCallData(target.sweepContractAddress, "0", observation.decimals)
        val fingerprint =
            SubmissionRequestHashes.contractCallV1(
                target.accountId,
                target.tokenContractAddress,
                target.network,
                target.symbol,
                "0",
                callData,
            )
        check(fingerprint.requestHash == target.requestHash) { "allowance revocation request drifted from approved snapshot" }
        val submitCommand =
            SweepContractCallCommand(
                target.externalTransactionId,
                SubmissionTransactionType.SWEEP_APPROVE,
                target.accountId,
                target.sourceVaultId,
                target.network,
                target.symbol,
                target.tokenContractAddress,
                "0",
                callData,
            )
        val existingSubmission = submissionRecords.findByExternalTransactionId(target.externalTransactionId)
        val result =
            try {
                if (existingSubmission == null) {
                    contractCalls.submit(submitCommand) {
                        recordSubmitIntent(target, observation, command.actor)
                    }
                } else {
                    transactions.run { recordSubmitIntent(target, observation, command.actor) }
                    contractCalls.submit(submitCommand)
                }
            } catch (inProgress: SubmissionInProgressException) {
                return requireNotNull(revocations.findExecution(command.executionId))
            } catch (rejected: RelayRejectedException) {
                recordFailure(target, command.actor, "VENDOR_REJECTED")
                throw rejected
            }
        return transactions.run {
            val current = lockAuthorization(target)
            authorizations.update(
                current.copy(
                    status = SweepAuthorizationStatus.REVOKING,
                    approvalExternalTransactionId = target.externalTransactionId,
                    approvalVendorTransactionId = result.vendorTransactionId,
                    lastCheckedAt = coreNow(),
                ),
            )
            appendNext(
                target,
                AllowanceRevocationEventStatus.SUBMITTED,
                command.actor,
                SUBMITTED_OBSERVATION,
                externalTransactionId = target.externalTransactionId,
                vendorTransactionId = result.vendorTransactionId,
            )
            requireNotNull(revocations.findExecution(command.executionId))
        }
    }

    private fun recordSubmitIntent(
        target: AllowanceRevocationTarget,
        observation: SweepAllowanceObservation,
        actor: AdminActor,
    ) {
        val current = lockAuthorization(target)
        authorizations.update(
            current.copy(
                observedAllowance = BigDecimal(observation.amount).stripTrailingZeros().toPlainString(),
                status = SweepAuthorizationStatus.REVOKING,
                approvalExternalTransactionId = target.externalTransactionId,
                approvalVendorTransactionId = null,
                lastCheckedAt = coreNow(),
            ),
        )
        appendNext(
            target,
            AllowanceRevocationEventStatus.SUBMIT_INTENT,
            actor,
            SUBMIT_INTENT_OBSERVATION,
            externalTransactionId = target.externalTransactionId,
            observedAllowance = BigDecimal(observation.amount),
        )
    }

    private fun confirmZero(
        target: AllowanceRevocationTarget,
        observation: SweepAllowanceObservation,
        actor: AdminActor,
    ): AllowanceRevocationView =
        transactions.run {
            val current = lockAuthorization(target)
            authorizations.update(
                current.copy(
                    observedAllowance = "0",
                    status = SweepAuthorizationStatus.REVOKED,
                    lastCheckedAt = coreNow(),
                ),
            )
            appendNext(
                target,
                AllowanceRevocationEventStatus.ZERO_CONFIRMED,
                actor,
                ZERO_OBSERVATION,
                observedAllowance = BigDecimal(observation.amount),
            )
            requireNotNull(revocations.findExecution(target.executionId))
        }

    private fun recordPendingObservation(
        target: AllowanceRevocationTarget,
        observation: SweepAllowanceObservation,
    ): AllowanceRevocationView =
        transactions.run {
            val current = lockAuthorization(target)
            authorizations.update(
                current.copy(
                    observedAllowance = BigDecimal(observation.amount).stripTrailingZeros().toPlainString(),
                    status = SweepAuthorizationStatus.REVOKING,
                    lastCheckedAt = coreNow(),
                ),
            )
            requireNotNull(revocations.findExecution(target.executionId))
        }

    private fun recordFailure(
        target: AllowanceRevocationTarget,
        actor: AdminActor,
        errorCode: String,
    ) {
        transactions.run {
            val current = lockAuthorization(target)
            authorizations.update(current.copy(status = SweepAuthorizationStatus.FAILED, lastCheckedAt = coreNow()))
            appendNext(target, AllowanceRevocationEventStatus.FAILED, actor, FAILED_OBSERVATION, errorCode = errorCode)
        }
    }

    private fun appendNext(
        target: AllowanceRevocationTarget,
        status: AllowanceRevocationEventStatus,
        actor: AdminActor,
        observationPayload: String,
        externalTransactionId: String? = null,
        vendorTransactionId: String? = null,
        observedAllowance: BigDecimal? = null,
        errorCode: String? = null,
    ) {
        val current =
            revocations.findExecution(target.executionId)
                ?: throw ResourceNotFoundException("allowanceRevocationExecution", target.executionId)
        val nextSequence = current.events.filter { it.itemSequence == target.itemSequence }.maxOf { it.eventSequence } + 1
        revocations.appendEvent(
            event(
                target,
                nextSequence,
                status,
                actor,
                observationPayload,
                externalTransactionId,
                vendorTransactionId,
                observedAllowance,
                errorCode,
            ),
        )
    }

    private fun event(
        target: AllowanceRevocationTarget,
        eventSequence: Int,
        status: AllowanceRevocationEventStatus,
        actor: AdminActor,
        observationPayload: String,
        externalTransactionId: String? = null,
        vendorTransactionId: String? = null,
        observedAllowance: BigDecimal? = null,
        errorCode: String? = null,
    ) = AllowanceRevocationEvent(
        target.executionId,
        target.itemSequence,
        eventSequence,
        status,
        externalTransactionId,
        vendorTransactionId,
        observedAllowance,
        observationPayload,
        sha256(observationPayload),
        errorCode,
        now(),
        actor,
    )

    private fun observe(target: AllowanceRevocationTarget): SweepAllowanceObservation =
        erc20.allowance(target.network, target.tokenContractAddress, target.ownerAddress, target.sweepContractAddress)

    private fun lockAuthorization(target: AllowanceRevocationTarget) =
        authorizations.findByKeyForUpdate(
            com.whatto.bcm.domain.sweep.SweepAuthorizationKey(
                target.accountId,
                target.network,
                target.symbol,
                target.sweepContractAddress,
            ),
        ) ?: throw ResourceNotFoundException("sweepAuthorization", "${target.accountId}:${target.network}:${target.symbol}")

    private fun requireOperator(actor: AdminActor) {
        check(AdminRole.BCM_OPERATOR in actor.roles) { "BCM_OPERATOR role is required" }
    }

    private fun requireOperatorOrSystem(actor: AdminActor) {
        check(AdminRole.BCM_OPERATOR in actor.roles || actor.employeeNo == "SYSTEM") {
            "BCM_OPERATOR role or SYSTEM actor is required"
        }
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.SECONDS)

    private fun coreNow(): String = CoreDateTimes.format(LocalDateTime.ofInstant(now(), ZoneOffset.UTC))

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val RESERVED_OBSERVATION = "{\"event\":\"RESERVED\"}"
        const val SUBMIT_INTENT_OBSERVATION = "{\"event\":\"SUBMIT_INTENT\"}"
        const val SUBMITTED_OBSERVATION = "{\"event\":\"SUBMITTED\"}"
        const val ZERO_OBSERVATION = "{\"event\":\"ZERO_CONFIRMED\"}"
        const val FAILED_OBSERVATION = "{\"event\":\"FAILED\"}"
    }
}
