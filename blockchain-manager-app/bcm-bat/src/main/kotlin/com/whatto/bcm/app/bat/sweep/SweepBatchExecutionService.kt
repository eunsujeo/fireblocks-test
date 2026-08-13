package com.whatto.bcm.app.bat.sweep

import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountRepository
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.account.DepositAddressRepository
import com.whatto.bcm.domain.asset.VendorAssetMappingRepository
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.SubmissionInProgressException
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.sweep.SweepBatchCallItem
import com.whatto.bcm.domain.sweep.SweepBatchContractPort
import com.whatto.bcm.domain.sweep.SweepExecution
import com.whatto.bcm.domain.sweep.SweepExecutionAlert
import com.whatto.bcm.domain.sweep.SweepExecutionAlertPort
import com.whatto.bcm.domain.sweep.SweepExecutionRepository
import com.whatto.bcm.domain.sweep.SweepExecutionStage
import com.whatto.bcm.domain.sweep.SweepExecutionStatus
import com.whatto.bcm.domain.sweep.SweepItem
import com.whatto.bcm.domain.sweep.SweepItemStatus
import com.whatto.bcm.support.submission.SweepBatchHashItem
import com.whatto.bcm.support.submission.SweepBatchRequestFingerprint
import com.whatto.bcm.support.submission.SweepBatchRequestHashes
import com.whatto.bcm.support.time.CoreDateTimes
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Clock

sealed interface SweepBatchExecutionResult {
    data object NoCandidates : SweepBatchExecutionResult

    data class WaitingForAllowance(
        val candidateCount: Int,
    ) : SweepBatchExecutionResult

    data class SubmissionPending(
        val executionId: String,
        val externalTransactionId: String,
    ) : SweepBatchExecutionResult

    data class Submitted(
        val executionId: String,
        val externalTransactionId: String,
        val vendorTransactionId: String,
    ) : SweepBatchExecutionResult
}

@Service
class SweepBatchExecutionService(
    private val candidates: SweepCandidateSelector,
    private val allowancePreparation: SweepAllowancePreparer,
    private val executions: SweepExecutionRepository,
    private val accounts: AccountRepository,
    private val addresses: DepositAddressRepository,
    private val mappings: VendorAssetMappingRepository,
    private val batchContract: SweepBatchContractPort,
    private val contractCalls: SweepContractCallSubmitter,
    private val executionIds: SweepExecutionIdGenerator,
    private val externalIds: SweepExternalTransactionIdGenerator,
    private val alerts: SweepExecutionAlertPort,
    private val clock: Clock,
    private val properties: SweepProperties,
) {
    fun runOnce(): SweepBatchExecutionResult {
        properties.security.requireBatchSubmissionReady()
        val operator = requiredOperator()
        executions.findPendingSubmission(operator.accountId)?.let { return submit(it, operator) }
        val selected = candidates.selectCandidates()
        if (selected.isEmpty()) return SweepBatchExecutionResult.NoCandidates
        val groupKey = selected.first().target.network to selected.first().target.symbol
        val sameAsset = selected.filter { it.target.network to it.target.symbol == groupKey }
        val ready = sameAsset.filter(::prepareAllowance)
        if (ready.isEmpty()) return SweepBatchExecutionResult.WaitingForAllowance(sameAsset.size)
        val prepared = prepareExecution(ready, operator)
        try {
            executions.createAndClaim(prepared.execution, prepared.items)
        } catch (conflict: ConflictException) {
            val pending = executions.findPendingSubmission(operator.accountId) ?: throw conflict
            return submit(pending, operator)
        }
        return submit(prepared.execution, operator)
    }

    private fun prepareAllowance(candidate: SweepCandidate): Boolean =
        try {
            allowancePreparation.prepare(candidate) == SweepAllowancePreparationResult.Ready
        } catch (exception: RuntimeException) {
            alerts.alert(SweepExecutionAlert(SweepExecutionStage.SUBMISSION, candidate.target.key, exception))
            false
        }

    private fun prepareExecution(
        ready: List<SweepCandidate>,
        operator: Account,
    ): PreparedSweepExecution {
        val first = ready.first().target
        val mapping = checkNotNull(mappings.find(first.network, first.symbol)) { "sweep asset mapping not found" }
        val tokenContract = checkNotNull(mapping.contractAddress) { "native asset does not support batch sweep" }
        val sweepContract = properties.requiredContractAddress(first.network)
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
            )
        return PreparedSweepExecution(
            execution,
            fingerprint.items.mapIndexed { index, item ->
                SweepItem(
                    executionId = executionId,
                    sequence = index + 1,
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
        check(execution.sweepContractAddress.equals(properties.requiredContractAddress(execution.network), ignoreCase = true)) {
            "stored sweep execution contract is not active"
        }
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
            val result = contractCalls.submit(command)
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

@Component
@ConditionalOnProperty(prefix = "bcm.sweep", name = ["enabled"], havingValue = "true")
class SweepBatchJob(
    private val service: SweepBatchExecutionService,
) {
    @Scheduled(fixedDelayString = "\${bcm.sweep.fixed-delay-millis:60000}")
    fun run() {
        val result = service.runOnce()
        logger.info("batch sweep cycle completed result={}", result)
    }

    private companion object {
        val logger = LoggerFactory.getLogger(SweepBatchJob::class.java)
    }
}
