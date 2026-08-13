package com.whatto.bcm.app.bat.sweep

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.account.AccountRepository
import com.whatto.bcm.domain.account.DepositAddressRepository
import com.whatto.bcm.domain.asset.VendorAssetMappingRepository
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.RelayRejectedException
import com.whatto.bcm.domain.exception.SubmissionInProgressException
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.sweep.Erc20ContractPort
import com.whatto.bcm.domain.sweep.SweepAllowanceDecision
import com.whatto.bcm.domain.sweep.SweepAllowanceObservation
import com.whatto.bcm.domain.sweep.SweepAllowancePolicy
import com.whatto.bcm.domain.sweep.SweepAuthorization
import com.whatto.bcm.domain.sweep.SweepAuthorizationKey
import com.whatto.bcm.domain.sweep.SweepAuthorizationRepository
import com.whatto.bcm.domain.sweep.SweepAuthorizationStatus
import com.whatto.bcm.domain.sweep.SweepTargetKey
import com.whatto.bcm.domain.sweep.SweepTargetRepository
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.stereotype.Service
import java.time.Clock

sealed interface SweepAllowancePreparationResult {
    data object Ready : SweepAllowancePreparationResult

    data object Revoked : SweepAllowancePreparationResult

    data class Pending(
        val externalTransactionId: String,
        val vendorTransactionId: String?,
    ) : SweepAllowancePreparationResult

    data class BlockedByActiveExecution(
        val executionId: String,
        val itemSequence: Int,
    ) : SweepAllowancePreparationResult
}

fun interface SweepAllowancePreparer {
    fun prepare(candidate: SweepCandidate): SweepAllowancePreparationResult
}

@Service
class SweepAllowancePreparationService(
    private val authorizations: SweepAuthorizationRepository,
    private val targets: SweepTargetRepository,
    private val accounts: AccountRepository,
    private val addresses: DepositAddressRepository,
    private val mappings: VendorAssetMappingRepository,
    private val erc20: Erc20ContractPort,
    private val contractCalls: SweepContractCallSubmitter,
    private val transactionRunner: TransactionRunner,
    private val externalIds: SweepApprovalExternalTransactionIdGenerator,
    private val clock: Clock,
    private val properties: SweepProperties,
) : SweepAllowancePreparer {
    override fun prepare(candidate: SweepCandidate): SweepAllowancePreparationResult {
        properties.security.requireNormalApprovalReady()
        val context = context(candidate.target.key)
        val observation = observe(context)
        val authorization = storeObservation(context, observation)
        return executeDecision(context, observation, authorization, candidate.amount, emergency = false)
    }

    fun revoke(key: SweepAuthorizationKey): SweepAllowancePreparationResult {
        properties.security.requireEmergencyRevocationReady()
        val context = context(SweepTargetKey(key.accountId, key.network, key.symbol), key.sweepContractAddress)
        val observation = observe(context)
        val authorization = storeObservation(context, observation)
        return executeDecision(context, observation, authorization, requiredAmount = null, emergency = true)
    }

    private fun executeDecision(
        context: AllowanceContext,
        observation: SweepAllowanceObservation,
        authorization: SweepAuthorization,
        requiredAmount: String?,
        emergency: Boolean,
    ): SweepAllowancePreparationResult {
        val decision =
            if (emergency) {
                SweepAllowancePolicy.emergencyRevocation(authorization)
            } else if (
                authorization.status == SweepAuthorizationStatus.APPROVING ||
                authorization.status == SweepAuthorizationStatus.REVOKING
            ) {
                SweepAllowanceDecision.WaitingForChain
            } else if (authorization.allowanceCap != context.allowanceCap) {
                if (authorization.observedAllowance == "0") {
                    SweepAllowanceDecision.Approve(context.allowanceCap)
                } else {
                    SweepAllowanceDecision.RevokeFirst
                }
            } else {
                SweepAllowancePolicy.prepare(authorization, requireNotNull(requiredAmount))
            }
        return when (decision) {
            SweepAllowanceDecision.Ready ->
                if (emergency) markRevoked(authorization) else SweepAllowancePreparationResult.Ready

            SweepAllowanceDecision.WaitingForChain -> resumePending(context, observation, authorization)
            is SweepAllowanceDecision.Approve -> startAction(context, observation, authorization, decision.amount, revoke = false)
            SweepAllowanceDecision.RevokeFirst -> startAction(context, observation, authorization, "0", revoke = true)
        }
    }

    private fun startAction(
        context: AllowanceContext,
        observation: SweepAllowanceObservation,
        observed: SweepAuthorization,
        amount: String,
        revoke: Boolean,
    ): SweepAllowancePreparationResult {
        val prepared =
            transactionRunner.run {
                val current =
                    authorizations.findByKeyForUpdate(observed.key)
                        ?: throw ConflictException(
                            "sweepAuthorization",
                            observed.key.toString(),
                        )
                if (current != observed) {
                    throw ConflictException("sweepAuthorization", observed.key.toString())
                }
                val target = targets.findByKeyForUpdate(context.targetKey)
                val activeExecutionId = target?.activeSweepExecutionId
                if (activeExecutionId != null) {
                    return@run PreparedAllowanceAction.Blocked(
                        activeExecutionId,
                        requireNotNull(target.activeItemSequence),
                    )
                }
                val externalTransactionId = externalIds.nextId()
                val status = if (revoke) SweepAuthorizationStatus.REVOKING else SweepAuthorizationStatus.APPROVING
                val updated =
                    authorizations.update(
                        current.copy(
                            allowanceCap = context.allowanceCap,
                            status = status,
                            approvalExternalTransactionId = externalTransactionId,
                            approvalVendorTransactionId = null,
                        ),
                    )
                PreparedAllowanceAction.Submit(command(context, observation, updated, amount))
            }
        return when (prepared) {
            is PreparedAllowanceAction.Blocked ->
                SweepAllowancePreparationResult.BlockedByActiveExecution(prepared.executionId, prepared.itemSequence)

            is PreparedAllowanceAction.Submit -> submit(prepared.command, observed.key)
        }
    }

    private fun resumePending(
        context: AllowanceContext,
        observation: SweepAllowanceObservation,
        authorization: SweepAuthorization,
    ): SweepAllowancePreparationResult {
        if (authorization.approvalVendorTransactionId != null) {
            return SweepAllowancePreparationResult.Pending(
                requireNotNull(authorization.approvalExternalTransactionId),
                authorization.approvalVendorTransactionId,
            )
        }
        val amount = if (authorization.status == SweepAuthorizationStatus.REVOKING) "0" else authorization.allowanceCap
        return submit(command(context, observation, authorization, amount), authorization.key)
    }

    private fun submit(
        command: SweepContractCallCommand,
        key: SweepAuthorizationKey,
    ): SweepAllowancePreparationResult =
        try {
            val result = contractCalls.submit(command)
            val updated =
                transactionRunner.run {
                    val current = authorizations.findByKeyForUpdate(key) ?: throw ConflictException("sweepAuthorization", key.toString())
                    check(current.approvalExternalTransactionId == command.externalTransactionId) {
                        "sweep authorization action changed while submitting"
                    }
                    authorizations.update(current.copy(approvalVendorTransactionId = result.vendorTransactionId))
                }
            SweepAllowancePreparationResult.Pending(command.externalTransactionId, updated.approvalVendorTransactionId)
        } catch (inProgress: SubmissionInProgressException) {
            SweepAllowancePreparationResult.Pending(command.externalTransactionId, null)
        } catch (rejected: RelayRejectedException) {
            transactionRunner.run {
                val current = authorizations.findByKeyForUpdate(key)
                if (current?.approvalExternalTransactionId == command.externalTransactionId) {
                    authorizations.update(current.copy(status = SweepAuthorizationStatus.FAILED))
                }
            }
            throw rejected
        }

    private fun command(
        context: AllowanceContext,
        observation: SweepAllowanceObservation,
        authorization: SweepAuthorization,
        amount: String,
    ) = SweepContractCallCommand(
        externalTransactionId = requireNotNull(authorization.approvalExternalTransactionId),
        transactionType = SubmissionTransactionType.SWEEP_APPROVE,
        senderAccountId = context.targetKey.accountId,
        sourceVaultId = context.sourceVaultId,
        network = context.targetKey.network,
        symbol = context.targetKey.symbol,
        contractAddress = context.tokenContractAddress,
        semanticAmount = amount,
        callData = erc20.approvalCallData(context.sweepContractAddress, amount, observation.decimals),
    )

    private fun observe(context: AllowanceContext): SweepAllowanceObservation =
        erc20.allowance(
            context.targetKey.network,
            context.tokenContractAddress,
            context.ownerAddress,
            context.sweepContractAddress,
        )

    private fun storeObservation(
        context: AllowanceContext,
        observation: SweepAllowanceObservation,
    ): SweepAuthorization {
        val observedAt = CoreDateTimes.now(clock)
        val key =
            SweepAuthorizationKey(
                context.targetKey.accountId,
                context.targetKey.network,
                context.targetKey.symbol,
                context.sweepContractAddress,
            )
        return try {
            transactionRunner.run {
                val current = authorizations.findByKeyForUpdate(key)
                val observed = SweepAllowancePolicy.observe(current, key, context.allowanceCap, observation, observedAt)
                if (current == null) authorizations.insert(observed) else authorizations.update(observed)
            }
        } catch (conflict: ConflictException) {
            transactionRunner.run {
                val current = authorizations.findByKeyForUpdate(key) ?: throw conflict
                authorizations.update(SweepAllowancePolicy.observe(current, key, context.allowanceCap, observation, observedAt))
            }
        }
    }

    private fun markRevoked(authorization: SweepAuthorization): SweepAllowancePreparationResult {
        transactionRunner.run {
            val current = authorizations.findByKeyForUpdate(authorization.key) ?: return@run
            authorizations.update(current.copy(status = SweepAuthorizationStatus.REVOKED))
        }
        return SweepAllowancePreparationResult.Revoked
    }

    private fun context(
        key: SweepTargetKey,
        expectedSweepContractAddress: String? = null,
    ): AllowanceContext {
        val account = checkNotNull(accounts.findByAccountId(key.accountId)) { "sweep source account not found: accountId=${key.accountId}" }
        val address =
            checkNotNull(addresses.find(key.accountId, key.network, key.symbol)) {
                "sweep source address not found: accountId=${key.accountId} network=${key.network} symbol=${key.symbol}"
            }
        val mapping = checkNotNull(mappings.find(key.network, key.symbol)) { "sweep asset mapping not found" }
        val tokenContract = checkNotNull(mapping.contractAddress) { "native asset does not support ERC-20 allowance" }
        val sweepContract = expectedSweepContractAddress ?: properties.requiredContractAddress(key.network)
        return AllowanceContext(
            key,
            account.vendorVaultId,
            address.address,
            tokenContract,
            sweepContract,
            properties.requiredAllowanceCap(key.network, key.symbol),
        )
    }

    private data class AllowanceContext(
        val targetKey: SweepTargetKey,
        val sourceVaultId: String,
        val ownerAddress: String,
        val tokenContractAddress: String,
        val sweepContractAddress: String,
        val allowanceCap: String,
    )

    private sealed interface PreparedAllowanceAction {
        data class Submit(
            val command: SweepContractCallCommand,
        ) : PreparedAllowanceAction

        data class Blocked(
            val executionId: String,
            val itemSequence: Int,
        ) : PreparedAllowanceAction
    }
}
