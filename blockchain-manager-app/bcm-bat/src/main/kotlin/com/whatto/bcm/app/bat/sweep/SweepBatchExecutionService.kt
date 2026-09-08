package com.whatto.bcm.app.bat.sweep

import com.whatto.bcm.app.application.account.AccountQueryService
import com.whatto.bcm.app.application.account.DepositAddressQueryService
import com.whatto.bcm.app.application.asset.VendorAssetMappingQueryService
import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.SubmissionInProgressException
import com.whatto.bcm.domain.job.JobStateRepository
import com.whatto.bcm.domain.job.OperationalJobNames
import com.whatto.bcm.domain.job.RuntimeAttestation
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.sweep.ActiveSweepRuntimeContext
import com.whatto.bcm.domain.sweep.SweepBatchCallItem
import com.whatto.bcm.domain.sweep.SweepBatchContractPort
import com.whatto.bcm.domain.sweep.SweepExecution
import com.whatto.bcm.domain.sweep.SweepExecutionAlert
import com.whatto.bcm.domain.sweep.SweepExecutionAlertPort
import com.whatto.bcm.domain.sweep.SweepExecutionGatePort
import com.whatto.bcm.domain.sweep.SweepExecutionGateSnapshot
import com.whatto.bcm.domain.sweep.SweepExecutionRepository
import com.whatto.bcm.domain.sweep.SweepExecutionStage
import com.whatto.bcm.domain.sweep.SweepExecutionStatus
import com.whatto.bcm.domain.sweep.SweepItem
import com.whatto.bcm.domain.sweep.SweepItemStatus
import com.whatto.bcm.domain.sweep.SweepRuntimeAttestationRepository
import com.whatto.bcm.domain.sweep.attestationEntry
import com.whatto.bcm.support.submission.SweepBatchHashItem
import com.whatto.bcm.support.submission.SweepBatchRequestFingerprint
import com.whatto.bcm.support.submission.SweepBatchRequestHashes
import com.whatto.bcm.support.time.CoreDateTimes
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock

sealed interface SweepBatchExecutionResult {
    data object NoCandidates : SweepBatchExecutionResult

    data class Stopped(
        val network: String,
    ) : SweepBatchExecutionResult

    data class WaitingForAllowance(
        val candidateCount: Int,
    ) : SweepBatchExecutionResult

    data class SubmissionPending(
        val executionId: String,
        val externalTransactionId: String,
    ) : SweepBatchExecutionResult

    data class Prepared(
        val executionId: String,
        val externalTransactionId: String,
    ) : SweepBatchExecutionResult

    data class Submitted(
        val executionId: String,
        val externalTransactionId: String,
        val vendorTransactionId: String,
    ) : SweepBatchExecutionResult
}

fun interface SweepBatchExecutionCommand {
    fun execute(): SweepBatchExecutionResult
}

fun interface SweepBatchPreparationCommand {
    fun prepare(): SweepBatchExecutionResult
}

@Service
class SweepBatchExecutionService(
    private val candidates: SweepCandidateSelector,
    private val allowancePreparation: SweepAllowancePreparer,
    private val executions: SweepExecutionRepository,
    private val accounts: AccountQueryService,
    private val addresses: DepositAddressQueryService,
    private val mappings: VendorAssetMappingQueryService,
    private val batchContract: SweepBatchContractPort,
    private val contractCalls: SweepContractCallSubmitter,
    private val executionIds: SweepExecutionIdGenerator,
    private val externalIds: SweepExternalTransactionIdGenerator,
    private val alerts: SweepExecutionAlertPort,
    private val clock: Clock,
    private val properties: SweepProperties,
    private val executionGates: SweepExecutionGatePort,
    private val runtimeGuard: SweepRuntimeGuard,
) : SweepBatchExecutionCommand,
    SweepBatchPreparationCommand {
    override fun execute(): SweepBatchExecutionResult = runOnce()

    override fun prepare(): SweepBatchExecutionResult = prepareOnce()

    fun runOnce(): SweepBatchExecutionResult {
        val preparation = prepareOnce()
        if (preparation !is SweepBatchExecutionResult.Prepared) return preparation
        val operator = requiredOperator()
        val execution =
            checkNotNull(executions.findPendingSubmission(operator.accountId)) {
                "prepared sweep execution not found: executionId=${preparation.executionId}"
            }
        check(execution.executionId == preparation.executionId) { "prepared sweep execution changed before submission" }
        return submit(execution, operator)
    }

    fun prepareOnce(): SweepBatchExecutionResult {
        properties.security.requireBatchSubmissionEnabled()
        val operator = requiredOperator()
        executions.findPendingSubmission(operator.accountId)?.let {
            properties.security.requireBatchSubmissionReady(it.network)
            validateRuntimeSnapshot(it, runtimeGuard.requireReady(it.network, it.symbol))
            return SweepBatchExecutionResult.Prepared(it.executionId, it.externalTransactionId)
        }
        val selected = candidates.selectCandidates()
        if (selected.isEmpty()) return SweepBatchExecutionResult.NoCandidates
        val groupKey = selected.first().target.network to selected.first().target.symbol
        properties.security.requireBatchSubmissionReady(groupKey.first)
        val runtime = runtimeGuard.requireReady(groupKey.first, groupKey.second)
        val sameAsset = applyRuntimePolicy(selected.filter { it.target.network to it.target.symbol == groupKey }, runtime)
        if (!executionGates.findCurrent(groupKey.first).open) {
            return SweepBatchExecutionResult.Stopped(groupKey.first)
        }
        val ready = sameAsset.filter(::prepareAllowance)
        if (ready.isEmpty()) return SweepBatchExecutionResult.WaitingForAllowance(sameAsset.size)
        val prepared = prepareExecution(ready, operator, runtime)
        try {
            executions.createAndClaim(prepared.execution, prepared.items)
        } catch (conflict: ConflictException) {
            val pending = executions.findPendingSubmission(operator.accountId) ?: throw conflict
            return SweepBatchExecutionResult.Prepared(pending.executionId, pending.externalTransactionId)
        }
        return SweepBatchExecutionResult.Prepared(prepared.execution.executionId, prepared.execution.externalTransactionId)
    }

    private fun prepareAllowance(candidate: SweepCandidate): Boolean =
        try {
            allowancePreparation.prepare(candidate) == SweepAllowancePreparationResult.Ready
        } catch (exception: RuntimeException) {
            alerts.alert(SweepExecutionAlert(SweepExecutionStage.SUBMISSION, candidate.target.key, exception))
            false
        }

    private fun applyRuntimePolicy(
        candidates: List<SweepCandidate>,
        runtime: ActiveSweepRuntimeContext,
    ): List<SweepCandidate> {
        var total = BigDecimal.ZERO
        return buildList {
            for (candidate in candidates) {
                if (size >= runtime.policy.batchSize) break
                val amount = BigDecimal(candidate.amount)
                if (amount < runtime.policy.minimumAmount || amount > runtime.policy.itemAmountCap) continue
                if (total + amount > runtime.policy.batchAmountCap) continue
                add(candidate)
                total += amount
            }
        }
    }

    private fun prepareExecution(
        ready: List<SweepCandidate>,
        operator: Account,
        runtime: ActiveSweepRuntimeContext,
    ): PreparedSweepExecution {
        val first = ready.first().target
        val mapping = checkNotNull(mappings.find(first.network, first.symbol)) { "sweep asset mapping not found" }
        val tokenContract = checkNotNull(mapping.contractAddress) { "native asset does not support batch sweep" }
        val sweepContract = runtime.contractAddress
        val executionId = executionIds.nextId()
        val candidatesByAddress =
            ready.associateBy { candidate ->
                requiredAddress(candidate).lowercase()
            }
        check(candidatesByAddress.size == ready.size) { "batch source addresses must be unique" }
        val fingerprint =
            SweepBatchRequestHashes.batchV1(
                first.network,
                first.symbol,
                tokenContract,
                sweepContract,
                executionId,
                candidatesByAddress.map { (address, candidate) -> SweepBatchHashItem(address, candidate.amount) },
            )
        val now = CoreDateTimes.now(clock)
        val execution =
            SweepExecution(
                executionId = executionId,
                externalTransactionId = externalIds.nextId(),
                requestHash = fingerprint.requestHash,
                network = first.network,
                symbol = first.symbol,
                operatorAccountId = operator.accountId,
                sweepContractAddress = sweepContract,
                status = SweepExecutionStatus.READY,
                itemCount = fingerprint.items.size,
                requestedTotalAmount = fingerprint.totalAmount,
                actualTotalAmount = null,
                gasless = true,
                vendorTransactionId = null,
                transactionHash = null,
                requestedAt = now,
                finishedAt = null,
                policyVersionId = runtime.policyVersionId,
                policySnapshotHash = runtime.policySnapshotHash,
                contractVersionId = runtime.contractVersionId,
                contractEvidenceId = runtime.contractEvidenceId,
            )
        return PreparedSweepExecution(
            execution,
            fingerprint.items.mapIndexed { index, item ->
                SweepItem(
                    executionId = executionId,
                    sequence = index + 1,
                    sweepRequestId =
                        requireNotNull(
                            requireNotNull(candidatesByAddress[item.sourceAddress]).target.pendingSweepRequestId,
                        ) {
                            "sweep candidate must reference a pending DAW request"
                        },
                    sweepRequestItemId =
                        requireNotNull(
                            requireNotNull(candidatesByAddress[item.sourceAddress]).target.pendingSweepRequestItemId,
                        ) {
                            "sweep candidate must reference a pending DAW request item"
                        },
                    accountId = requireNotNull(candidatesByAddress[item.sourceAddress]).target.accountId,
                    sourceAddress = item.sourceAddress,
                    requestedAmount = item.amount,
                    actualAmount = null,
                    status = SweepItemStatus.READY,
                    failureCode = null,
                    logIndex = null,
                )
            },
        )
    }

    private fun submit(
        execution: SweepExecution,
        operator: Account,
    ): SweepBatchExecutionResult {
        val items = executions.findItems(execution.executionId)
        val mapping = checkNotNull(mappings.find(execution.network, execution.symbol)) { "sweep asset mapping not found" }
        val tokenContract = checkNotNull(mapping.contractAddress) { "native asset does not support batch sweep" }
        val fingerprint = fingerprint(execution, tokenContract, items)
        check(fingerprint.requestHash == execution.requestHash) { "stored sweep execution hash does not match items" }
        check(fingerprint.totalAmount == execution.requestedTotalAmount) { "stored sweep execution total does not match items" }
        validateRuntimeSnapshot(execution, runtimeGuard.requireReady(execution.network, execution.symbol))
        val callData =
            batchContract.batchSweepCallData(
                execution.network,
                execution.executionId,
                tokenContract,
                fingerprint.items.map { SweepBatchCallItem(it.sourceAddress, it.amount) },
            )
        executions.markSubmitting(execution.executionId)
        val command =
            SweepContractCallCommand(
                externalTransactionId = execution.externalTransactionId,
                transactionType = SubmissionTransactionType.SWEEP_BATCH,
                senderAccountId = operator.accountId,
                sourceVaultId = operator.vendorVaultId,
                network = execution.network,
                symbol = execution.symbol,
                contractAddress = execution.sweepContractAddress,
                semanticAmount = execution.requestedTotalAmount,
                callData = callData,
                sweepExecutionId = execution.executionId,
            )
        return try {
            val result =
                contractCalls.submit(
                    command,
                    recordIntent = {},
                    recordFailedRetry = {
                        executions.recordSubmissionRetry(execution.executionId, CoreDateTimes.now(clock))
                    },
                )
            executions.markSubmitted(execution.executionId, result.vendorTransactionId)
            SweepBatchExecutionResult.Submitted(
                execution.executionId,
                execution.externalTransactionId,
                result.vendorTransactionId,
            )
        } catch (inProgress: SubmissionInProgressException) {
            SweepBatchExecutionResult.SubmissionPending(execution.executionId, execution.externalTransactionId)
        }
    }

    private fun fingerprint(
        execution: SweepExecution,
        tokenContract: String,
        items: List<SweepItem>,
    ): SweepBatchRequestFingerprint =
        SweepBatchRequestHashes.batchV1(
            execution.network,
            execution.symbol,
            tokenContract,
            execution.sweepContractAddress,
            execution.executionId,
            items.map { SweepBatchHashItem(it.sourceAddress, it.requestedAmount) },
        )

    private fun validateRuntimeSnapshot(
        execution: SweepExecution,
        runtime: ActiveSweepRuntimeContext,
    ) {
        check(execution.sweepContractAddress.equals(runtime.contractAddress, ignoreCase = true)) {
            "stored sweep execution contract is not active"
        }
        check(
            execution.policyVersionId == runtime.policyVersionId &&
                execution.policySnapshotHash == runtime.policySnapshotHash &&
                execution.contractVersionId == runtime.contractVersionId &&
                execution.contractEvidenceId == runtime.contractEvidenceId,
        ) { "stored sweep execution Admin snapshot is stale" }
    }

    private fun requiredAddress(candidate: SweepCandidate): String =
        checkNotNull(
            addresses.find(
                candidate.target.accountId,
                candidate.target.network,
                candidate.target.symbol,
            ),
        ) { "sweep source address not found: accountId=${candidate.target.accountId}" }.address

    private fun requiredOperator(): Account {
        check(properties.operatorAccountId.isNotBlank()) { "sweep operatorAccountId must be configured" }
        val operator =
            checkNotNull(accounts.findByAccountId(properties.operatorAccountId)) {
                "sweep operator account not found: accountId=${properties.operatorAccountId}"
            }
        check(operator.accountType == AccountType.SYSTEM) { "sweep operator account must be SYSTEM" }
        check(operator.accountId != properties.omnibusAccountId) { "sweep operator and omnibus account must differ" }
        return operator
    }

    private data class PreparedSweepExecution(
        val execution: SweepExecution,
        val items: List<SweepItem>,
    )
}

/** 시스템 통합 테스트와 수동 점검이 스케줄 경쟁 없이 sweep 제출을 정확히 한 번 실행할 때 사용한다. */
@Component
@ConditionalOnProperty(prefix = "bcm", name = ["job"], havingValue = "sweep-execution-once")
class SweepBatchExecutionOnceRunner(
    private val command: SweepBatchExecutionCommand,
    private val context: ConfigurableApplicationContext,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        logger.info("one-shot batch sweep execution completed result={}", command.execute())
        context.close()
    }

    private companion object {
        val logger = LoggerFactory.getLogger(SweepBatchExecutionOnceRunner::class.java)
    }
}

/** 후보·allowance·claim까지만 한 번 실행해 제출 전 원장을 점검하거나 별도 제출 회차로 넘길 때 사용한다. */
@Component
@ConditionalOnProperty(prefix = "bcm", name = ["job"], havingValue = "sweep-preparation-once")
class SweepBatchPreparationOnceRunner(
    private val command: SweepBatchPreparationCommand,
    private val context: ConfigurableApplicationContext,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        logger.info("one-shot batch sweep preparation completed result={}", command.prepare())
        context.close()
    }

    private companion object {
        val logger = LoggerFactory.getLogger(SweepBatchPreparationOnceRunner::class.java)
    }
}

@Component
@ConditionalOnProperty(prefix = "bcm.sweep", name = ["enabled"], havingValue = "true")
class SweepBatchJob(
    private val command: SweepBatchExecutionCommand,
    private val jobs: JobStateRepository,
    private val clock: Clock,
    private val attestor: SweepRuntimeAttestationCommand,
) {
    @Scheduled(fixedDelayString = "\${bcm.sweep.fixed-delay-millis:60000}")
    fun run() {
        val startedAt = CoreDateTimes.now(clock)
        jobs.markStarted(OperationalJobNames.SWEEP_BATCH_EXECUTION, startedAt)
        val attestation = attestor.observe(clock.instant())
        jobs.markValidationStarted(attestation.jobName, startedAt)
        attestor.requireReady(attestation)
        val result = command.execute()
        val succeededAt = CoreDateTimes.now(clock)
        jobs.markSucceeded(OperationalJobNames.SWEEP_BATCH_EXECUTION, succeededAt)
        if (result !is SweepBatchExecutionResult.Stopped) {
            jobs.markSucceeded(attestation.jobName, succeededAt)
        }
        logger.info("batch sweep cycle completed result={}", result)
    }

    private companion object {
        val logger = LoggerFactory.getLogger(SweepBatchJob::class.java)
    }
}

@Service
class SweepRuntimeAttestor(
    private val runtimeContexts: SweepRuntimeAttestationRepository,
    private val runtimeGuard: SweepRuntimeGuard,
    private val executionGates: SweepExecutionGatePort,
    private val properties: SweepProperties,
) : SweepRuntimeAttestationCommand {
    override fun observe(observedAt: java.time.Instant): SweepRuntimeAttestationObservation {
        val contexts = runtimeContexts.findAllActive(observedAt)
        val gates =
            contexts
                .map(ActiveSweepRuntimeContext::network)
                .distinct()
                .sorted()
                .map(executionGates::findCurrent)
        return SweepRuntimeAttestationObservation(
            RuntimeAttestation.jobName(
                SWEEP_ATTESTATION_PREFIX,
                contexts.map(ActiveSweepRuntimeContext::attestationEntry) + gates.map(SweepExecutionGateSnapshot::attestationEntry),
            ),
            contexts,
            gates,
        )
    }

    override fun requireReady(observation: SweepRuntimeAttestationObservation) {
        val contexts = observation.contexts
        val configuredScopes = properties.thresholds.mapTo(mutableSetOf()) { it.network to it.symbol }
        check(configuredScopes.isNotEmpty()) { "sweep runtime scope is not configured" }
        check(contexts.mapTo(mutableSetOf()) { it.network to it.symbol } == configuredScopes) {
            "active Admin sweep scopes differ from deployment configuration"
        }
        val configuredNetworks = configuredScopes.mapTo(mutableSetOf()) { it.first }
        check(observation.gates.mapTo(mutableSetOf(), SweepExecutionGateSnapshot::network) == configuredNetworks) {
            "sweep execution gate scopes differ from deployment configuration"
        }
        check(observation.gates.all(SweepExecutionGateSnapshot::open)) { "sweep execution gate is stopped" }
        properties.security.requireBatchSubmissionEnabled()
        val validatedContexts =
            configuredScopes.sortedWith(compareBy<Pair<String, String>> { it.first }.thenBy { it.second }).map { (network, symbol) ->
                properties.security.requireBatchSubmissionReady(network)
                runtimeGuard.requireReady(network, symbol)
            }
        check(validatedContexts == contexts.sortedWith(compareBy(ActiveSweepRuntimeContext::network, ActiveSweepRuntimeContext::symbol))) {
            "active Admin sweep context changed during runtime attestation"
        }
        check(observation.gates.all { executionGates.findCurrent(it.network) == it }) {
            "sweep execution gate changed during runtime attestation"
        }
    }

    private companion object {
        const val SWEEP_ATTESTATION_PREFIX = "sweep-attestation:"
    }
}

interface SweepRuntimeAttestationCommand {
    fun observe(observedAt: java.time.Instant): SweepRuntimeAttestationObservation

    fun requireReady(observation: SweepRuntimeAttestationObservation)
}

data class SweepRuntimeAttestationObservation(
    val jobName: String,
    val contexts: List<ActiveSweepRuntimeContext>,
    val gates: List<SweepExecutionGateSnapshot>,
)
