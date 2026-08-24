package com.whatto.bcm.app.application.admin

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.account.AccountRepository
import com.whatto.bcm.domain.account.DepositAddressRepository
import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminChangeRequest
import com.whatto.bcm.domain.admin.AdminContractRepository
import com.whatto.bcm.domain.admin.AdminRole
import com.whatto.bcm.domain.admin.AllowanceRevocationExecution
import com.whatto.bcm.domain.admin.AllowanceRevocationRepository
import com.whatto.bcm.domain.admin.AllowanceRevocationTarget
import com.whatto.bcm.domain.admin.ChangeRisk
import com.whatto.bcm.domain.admin.ChangeTargetType
import com.whatto.bcm.domain.admin.PolicyChangeRequest
import com.whatto.bcm.domain.asset.VendorAssetMappingRepository
import com.whatto.bcm.domain.event.EventIdGenerator
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import com.whatto.bcm.domain.sweep.Erc20ContractPort
import com.whatto.bcm.domain.sweep.SweepAllowanceObservation
import com.whatto.bcm.domain.sweep.SweepAllowancePolicy
import com.whatto.bcm.domain.sweep.SweepAuthorization
import com.whatto.bcm.domain.sweep.SweepAuthorizationRepository
import com.whatto.bcm.support.submission.SubmissionRequestHashes
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

data class AllowanceRevocationRequest(
    val execution: AllowanceRevocationExecution,
    val changeRequest: AdminChangeRequest,
)

data class RequestAllowanceRevocationCommand(
    val network: String,
    val reason: String,
    val workTicket: String,
    val idempotencyKey: String,
    val validFor: Duration,
    val actor: AdminActor,
)

@Service
class AllowanceRevocationCommandService(
    private val revocations: AllowanceRevocationRepository,
    private val policies: com.whatto.bcm.domain.admin.AdminPolicyRepository,
    private val contracts: AdminContractRepository,
    private val authorizations: SweepAuthorizationRepository,
    private val accounts: AccountRepository,
    private val addresses: DepositAddressRepository,
    private val mappings: VendorAssetMappingRepository,
    private val erc20: Erc20ContractPort,
    private val transactions: TransactionRunner,
    private val ids: EventIdGenerator,
    private val clock: Clock,
) {
    fun request(command: RequestAllowanceRevocationCommand): AllowanceRevocationRequest {
        validate(command)
        existing(command)?.let { return it }
        val scopeId = "${command.network}:SWEEP"
        val approvedContract =
            transactions.run {
                val binding =
                    contracts.lockBinding(scopeId)
                        ?: throw ResourceNotFoundException("contractBinding", scopeId)
                val versionId = binding.activeVersionId ?: throw ResourceNotFoundException("activeContractVersion", scopeId)
                val version =
                    contracts.findVersion(versionId)
                        ?: throw ResourceNotFoundException("contractVersion", versionId)
                check(version.use == "SWEEP" && version.network == command.network) {
                    "allowance revocation requires the active sweep contract"
                }
                ApprovedContract(version.versionId, version.address, binding.revision)
            }
        val observations = observeTargets(command.network, approvedContract.address)
        val observed = observations.filter { BigDecimal(it.observation.amount).signum() > 0 }
        check(observed.isNotEmpty()) { "allowance revocation requires at least one non-zero allowance" }
        return try {
            transactions.run {
                val binding =
                    contracts.lockBinding(scopeId)
                        ?: throw ResourceNotFoundException("contractBinding", scopeId)
                check(
                    binding.activeVersionId == approvedContract.versionId &&
                        binding.revision == approvedContract.bindingRevision,
                ) { "allowance revocation contract binding changed while collecting targets" }
                val now = now()
                val executionId = ids.nextId()
                val targets = observed.toTargets(executionId)
                val snapshotHash = snapshotHash(approvedContract, targets)
                val execution =
                    AllowanceRevocationExecution(
                        executionId,
                        approvedContract.versionId,
                        approvedContract.bindingRevision,
                        command.network,
                        approvedContract.address,
                        snapshotHash,
                        targets.size,
                        command.idempotencyKey,
                        now,
                        command.actor,
                    )
                observations.forEach { observation -> storeObservation(observation, now) }
                revocations.insertExecution(execution, targets)
                val diff =
                    """{"contractVersionId":"${approvedContract.versionId}","targetSnapshotHash":"$snapshotHash"}"""
                val impact = """{"itemCount":${targets.size},"network":"${command.network}"}"""
                val request =
                    AdminChangeRequest(
                        PolicyChangeRequest(
                            ids.nextId(),
                            command.actor,
                            ChangeRisk.FUND,
                            snapshotHash,
                            approvedContract.bindingRevision,
                            executionId,
                            now.plus(command.validFor),
                        ),
                        ChangeTargetType.ALLOWANCE_REVOKE,
                        scopeId,
                        null,
                        null,
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
                AllowanceRevocationRequest(execution, policies.insertChangeRequest(request))
            }
        } catch (conflict: ConflictException) {
            existing(command) ?: throw conflict
        }
    }

    private fun existing(command: RequestAllowanceRevocationCommand): AllowanceRevocationRequest? {
        val request = policies.findChangeRequestByIdempotency(command.actor.employeeNo, command.idempotencyKey) ?: return null
        if (request.targetType != ChangeTargetType.ALLOWANCE_REVOKE || request.scopeId != "${command.network}:SWEEP") {
            throw ConflictException("allowanceRevocationIdempotency", command.idempotencyKey)
        }
        val execution =
            revocations.findExecution(request.lifecycle.targetVersionId)?.execution
                ?: throw ResourceNotFoundException("allowanceRevocationExecution", request.lifecycle.targetVersionId)
        return AllowanceRevocationRequest(execution, request)
    }

    private fun observeTargets(
        network: String,
        sweepContractAddress: String,
    ): List<ObservedTarget> =
        authorizations
            .findByNetworkAndContract(network, sweepContractAddress)
            .map { authorization ->
                val key = authorization.key
                val account =
                    accounts.findByAccountId(key.accountId)
                        ?: throw ResourceNotFoundException("account", key.accountId)
                val address =
                    addresses.find(key.accountId, key.network, key.symbol)
                        ?: throw ResourceNotFoundException("depositAddress", "${key.accountId}:${key.network}:${key.symbol}")
                val mapping =
                    mappings.find(key.network, key.symbol)
                        ?: throw ResourceNotFoundException("assetMapping", "${key.network}:${key.symbol}")
                val tokenContractAddress =
                    mapping.contractAddress
                        ?: throw IllegalStateException("native asset does not support ERC-20 allowance")
                val observation =
                    erc20.allowance(
                        key.network,
                        tokenContractAddress,
                        address.address,
                        sweepContractAddress,
                    )
                val callData = erc20.approvalCallData(sweepContractAddress, "0", observation.decimals)
                val requestHash =
                    SubmissionRequestHashes
                        .contractCallV1(
                            key.accountId,
                            tokenContractAddress,
                            key.network,
                            key.symbol,
                            "0",
                            callData,
                        ).requestHash
                ObservedTarget(
                    authorization,
                    account.vendorVaultId,
                    address.address,
                    tokenContractAddress,
                    observation,
                    requestHash,
                )
            }.sortedWith(compareBy({ it.authorization.key.accountId }, { it.authorization.key.network }, { it.authorization.key.symbol }))

    private fun List<ObservedTarget>.toTargets(executionId: String): List<AllowanceRevocationTarget> =
        mapIndexed { index, observed ->
            val key = observed.authorization.key
            AllowanceRevocationTarget(
                executionId,
                index + 1,
                key.accountId,
                key.network,
                key.symbol,
                key.sweepContractAddress,
                observed.sourceVaultId,
                observed.ownerAddress,
                observed.tokenContractAddress,
                BigDecimal(observed.observation.amount),
                "arv-${ids.nextId()}",
                observed.requestHash,
            )
        }

    private fun storeObservation(
        observed: ObservedTarget,
        now: Instant,
    ) {
        val current =
            authorizations.findByKeyForUpdate(observed.authorization.key)
                ?: throw ResourceNotFoundException("sweepAuthorization", observed.authorization.key.toString())
        val updated =
            SweepAllowancePolicy.observe(
                current,
                current.key,
                current.allowanceCap,
                observed.observation,
                com.whatto.bcm.support.time.CoreDateTimes.format(
                    java.time.LocalDateTime.ofInstant(now, java.time.ZoneOffset.UTC),
                ),
            )
        authorizations.update(updated)
    }

    private fun snapshotHash(
        contract: ApprovedContract,
        targets: List<AllowanceRevocationTarget>,
    ): String =
        sha256(
            listOf(
                contract.versionId,
                contract.bindingRevision,
                contract.address.lowercase(),
                targets.joinToString("|") { target ->
                    listOf(
                        target.itemSequence,
                        target.accountId,
                        target.network,
                        target.symbol,
                        target.sweepContractAddress.lowercase(),
                        target.sourceVaultId,
                        target.ownerAddress.lowercase(),
                        target.tokenContractAddress.lowercase(),
                        target.beforeObservedAllowance.stripTrailingZeros().toPlainString(),
                        target.externalTransactionId,
                        target.requestHash,
                    ).joinToString(":")
                },
            ).joinToString("|"),
        )

    private fun validate(command: RequestAllowanceRevocationCommand) {
        check(AdminRole.BCM_OPERATOR in command.actor.roles) { "BCM_OPERATOR role is required" }
        require(command.network.isNotBlank()) { "network must not be blank" }
        require(command.reason.isNotBlank()) { "reason must not be blank" }
        require(command.workTicket.isNotBlank()) { "workTicket must not be blank" }
        require(command.idempotencyKey.isNotBlank()) { "idempotencyKey must not be blank" }
        require(!command.validFor.isZero && !command.validFor.isNegative) { "validFor must be positive" }
    }

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.SECONDS)

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private data class ApprovedContract(
        val versionId: String,
        val address: String,
        val bindingRevision: Long,
    )

    private data class ObservedTarget(
        val authorization: SweepAuthorization,
        val sourceVaultId: String,
        val ownerAddress: String,
        val tokenContractAddress: String,
        val observation: SweepAllowanceObservation,
        val requestHash: String,
    )
}
