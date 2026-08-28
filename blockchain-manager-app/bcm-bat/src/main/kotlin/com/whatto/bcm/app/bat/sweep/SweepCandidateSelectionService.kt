package com.whatto.bcm.app.bat.sweep

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountRepository
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.asset.VendorAssetMappingRepository
import com.whatto.bcm.domain.sweep.ActiveSweepRuntimeContext
import com.whatto.bcm.domain.sweep.SweepChainStatus
import com.whatto.bcm.domain.sweep.SweepEventPublisher
import com.whatto.bcm.domain.sweep.SweepExecutionAlert
import com.whatto.bcm.domain.sweep.SweepExecutionAlertPort
import com.whatto.bcm.domain.sweep.SweepExecutionStage
import com.whatto.bcm.domain.sweep.SweepItemOutcome
import com.whatto.bcm.domain.sweep.SweepItemOutcomeEvent
import com.whatto.bcm.domain.sweep.SweepTarget
import com.whatto.bcm.domain.sweep.SweepTargetRepository
import com.whatto.bcm.domain.sweep.SweepTransactionStatusRepository
import com.whatto.bcm.domain.vendor.WalletVendorPort
import com.whatto.bcm.support.time.CoreDateTimes
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock

data class SweepCandidate(
    val target: SweepTarget,
    val sourceVaultId: String,
    val omnibusAccountId: String,
    val omnibusVaultId: String,
    val vendorAssetId: String,
    val amount: String,
)

interface SweepCandidateSelector {
    fun selectCandidates(): List<SweepCandidate>
}

@Service
class SweepCandidateSelectionService(
    private val sweepTargets: SweepTargetRepository,
    private val accounts: AccountRepository,
    private val assetMappings: VendorAssetMappingRepository,
    private val wallet: WalletVendorPort,
    private val transactionRunner: TransactionRunner,
    private val sweepTransactionStatuses: SweepTransactionStatusRepository,
    private val eventPublisher: SweepEventPublisher,
    private val executionAlerts: SweepExecutionAlertPort,
    private val clock: Clock,
    private val properties: SweepProperties,
    private val runtimeGuard: SweepRuntimeGuard,
) : SweepCandidateSelector {
    override fun selectCandidates(): List<SweepCandidate> {
        val omnibus = requiredOmnibusAccount()
        val candidatesWithRuntime =
            sweepTargets
                .findPending(properties.security.batchSubmissionEnabledNetworks, properties.scanLimit)
                .mapNotNull { target ->
                    try {
                        val runtime = runtimeGuard.requireReady(target.network, target.symbol)
                        selectIfEligible(target, omnibus, runtime)?.let { RuntimeCandidate(it, runtime) }
                    } catch (exception: RuntimeException) {
                        executionAlerts.alert(
                            SweepExecutionAlert(SweepExecutionStage.SELECTION, target.key, exception),
                        )
                        null
                    }
                }
        val sorted = candidatesWithRuntime.sortedWith(candidateOrder(candidatesWithRuntime))
        val first = sorted.firstOrNull() ?: return emptyList()
        val group = first.candidate.target.network to first.candidate.target.symbol
        return selectWithinBatchAmountCap(
            sorted.filter { it.candidate.target.network to it.candidate.target.symbol == group },
            first.runtime,
        )
    }

    private fun selectIfEligible(
        target: SweepTarget,
        omnibus: Account,
        runtime: ActiveSweepRuntimeContext,
    ): SweepCandidate? {
        val minimumAmount = runtime.policy.minimumAmount
        val source =
            checkNotNull(accounts.findByAccountId(target.accountId)) {
                "sweep source account not found: accountId=${target.accountId}"
            }
        check(source.accountType == AccountType.CUSTOMER) {
            "sweep source account must be CUSTOMER: accountId=${target.accountId}"
        }
        check(source.vendorVaultId != omnibus.vendorVaultId) {
            "sweep source and omnibus vault must differ: accountId=${target.accountId}"
        }
        val mapping =
            checkNotNull(assetMappings.find(target.network, target.symbol)) {
                "sweep asset mapping not found: network=${target.network} symbol=${target.symbol}"
            }
        val finalizedBeforeBalance = sweepTransactionStatuses.finalizedDepositIds(target.key)
        val available = BigDecimal(wallet.balanceOf(source.vendorVaultId, mapping.vendorAssetId).available)
        check(available.signum() >= 0) {
            "vendor available balance must not be negative: accountId=${target.accountId}"
        }
        check(available <= runtime.policy.itemAmountCap) {
            "sweep amount exceeds active policy item cap: accountId=${target.accountId}"
        }
        if (available.signum() == 0 && target.pendingSweepRequestItemId != null) {
            completeWithoutExecutionIfStillZero(target, finalizedBeforeBalance)
            return null
        }
        if (available < minimumAmount) {
            deleteIfStillBelow(target, finalizedBeforeBalance)
            return null
        }
        return candidate(target, source, omnibus, mapping.vendorAssetId, available.stripTrailingZeros().toPlainString())
    }

    private fun selectWithinBatchAmountCap(
        candidates: List<RuntimeCandidate>,
        runtime: ActiveSweepRuntimeContext,
    ): List<SweepCandidate> {
        var total = BigDecimal.ZERO
        return buildList {
            for (candidate in candidates) {
                if (size == runtime.policy.batchSize) break
                val amount = BigDecimal(candidate.candidate.amount)
                if (total + amount > runtime.policy.batchAmountCap) continue
                add(candidate.candidate)
                total += amount
            }
        }
    }

    private fun candidateOrder(candidates: List<RuntimeCandidate>): Comparator<RuntimeCandidate> {
        val requestMetadataAvailable =
            candidates.all {
                it.candidate.target.pendingSweepRequestId != null &&
                    it.candidate.target.pendingSweepRequestedAt != null
            }
        val amountOrder = compareByDescending<RuntimeCandidate> { BigDecimal(it.candidate.amount) }
        if (!requestMetadataAvailable) {
            return amountOrder
                .thenBy { it.candidate.target.registeredAt }
                .thenBy { it.candidate.target.accountId }
                .thenBy { it.candidate.target.network }
                .thenBy { it.candidate.target.symbol }
        }
        return compareBy<RuntimeCandidate> { requireNotNull(it.candidate.target.pendingSweepRequestedAt) }
            .thenBy { requireNotNull(it.candidate.target.pendingSweepRequestId) }
            .thenByDescending { BigDecimal(it.candidate.amount) }
            .thenBy { it.candidate.target.accountId }
            .thenBy { it.candidate.target.network }
            .thenBy { it.candidate.target.symbol }
    }

    private fun candidate(
        target: SweepTarget,
        source: Account,
        omnibus: Account,
        vendorAssetId: String,
        amount: String,
    ): SweepCandidate =
        SweepCandidate(
            target = target,
            sourceVaultId = source.vendorVaultId,
            omnibusAccountId = omnibus.accountId,
            omnibusVaultId = omnibus.vendorVaultId,
            vendorAssetId = vendorAssetId,
            amount = amount,
        )

    private fun deleteIfStillBelow(
        target: SweepTarget,
        finalizedBeforeBalance: Set<String>,
    ) {
        transactionRunner.run {
            sweepTargets.findPendingForUpdate(target.key) ?: return@run
            val finalizedAfterBalance = sweepTransactionStatuses.finalizedDepositIds(target.key)
            if (finalizedAfterBalance.any { it !in finalizedBeforeBalance }) return@run
            if (sweepTargets.hasUnfinishedRequest(target.key)) return@run
            sweepTargets.deletePending(target.key)
        }
    }

    private fun completeWithoutExecutionIfStillZero(
        target: SweepTarget,
        finalizedBeforeBalance: Set<String>,
    ) {
        transactionRunner.run {
            sweepTargets.findPendingForUpdate(target.key) ?: return@run
            val finalizedAfterBalance = sweepTransactionStatuses.finalizedDepositIds(target.key)
            if (finalizedAfterBalance.any { it !in finalizedBeforeBalance }) return@run
            val completion =
                sweepTargets.completeOldestPendingWithoutExecution(
                    target.key,
                    CoreDateTimes.now(clock),
                ) ?: return@run
            eventPublisher.publish(
                listOf(
                    SweepItemOutcomeEvent(
                        sweepRequestId = completion.sweepRequestId,
                        sweepItemId = completion.sweepRequestItemId,
                        executionId = null,
                        txId = null,
                        vendorTxId = null,
                        txHash = null,
                        accountId = target.accountId,
                        network = target.network,
                        symbol = target.symbol,
                        requestedAmount = "0",
                        actualAmount = "0",
                        chainStatus = SweepChainStatus.NOT_SUBMITTED,
                        itemOutcome = SweepItemOutcome.NO_SWEEP_REQUIRED,
                        failureCode = null,
                    ),
                ),
            )
            if (!sweepTargets.hasUnfinishedRequest(target.key)) {
                sweepTargets.deletePending(target.key)
            }
        }
    }

    private fun requiredOmnibusAccount(): Account {
        check(properties.omnibusAccountId.isNotBlank()) { "sweep omnibusAccountId must be configured" }
        val account =
            checkNotNull(accounts.findByAccountId(properties.omnibusAccountId)) {
                "sweep omnibus account not found: accountId=${properties.omnibusAccountId}"
            }
        check(account.accountType == AccountType.SYSTEM) {
            "sweep omnibus account must be SYSTEM: accountId=${properties.omnibusAccountId}"
        }
        return account
    }

    private companion object {
        val logger = LoggerFactory.getLogger(SweepCandidateSelectionService::class.java)
    }

    private data class RuntimeCandidate(
        val candidate: SweepCandidate,
        val runtime: ActiveSweepRuntimeContext,
    )
}
