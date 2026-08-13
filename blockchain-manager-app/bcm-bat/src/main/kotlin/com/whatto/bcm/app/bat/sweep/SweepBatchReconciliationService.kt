package com.whatto.bcm.app.bat.sweep

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountRepository
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.asset.VendorAssetMappingRepository
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.sweep.Erc20ContractPort
import com.whatto.bcm.domain.sweep.SweepBatchReceiptPort
import com.whatto.bcm.domain.sweep.SweepExecution
import com.whatto.bcm.domain.sweep.SweepExecutionAlert
import com.whatto.bcm.domain.sweep.SweepExecutionAlertPort
import com.whatto.bcm.domain.sweep.SweepExecutionRepository
import com.whatto.bcm.domain.sweep.SweepExecutionStage
import com.whatto.bcm.domain.sweep.SweepExecutionStatus
import com.whatto.bcm.domain.sweep.SweepItem
import com.whatto.bcm.domain.sweep.SweepItemReconciliation
import com.whatto.bcm.domain.sweep.SweepItemStatus
import com.whatto.bcm.domain.sweep.SweepLegObservation
import com.whatto.bcm.domain.sweep.SweepTargetKey
import com.whatto.bcm.domain.sweep.SweepTargetRepository
import com.whatto.bcm.domain.sweep.SweepTransactionStatusRepository
import com.whatto.bcm.domain.vendor.VendorNetworkRecord
import com.whatto.bcm.domain.vendor.VendorTransaction
import com.whatto.bcm.domain.vendor.VendorTransactionPort
import com.whatto.bcm.domain.vendor.WalletVendorPort
import com.whatto.bcm.support.time.CoreDateTimes
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.time.Clock

data class SweepReconciliationCycleResult(
    val completed: Int,
    val pending: Int,
    val failed: Int,
)

@Service
class SweepBatchReconciliationService(
    private val executions: SweepExecutionRepository,
    private val vendorTransactions: VendorTransactionPort,
    private val receipts: SweepBatchReceiptPort,
    private val erc20: Erc20ContractPort,
    private val mappings: VendorAssetMappingRepository,
    private val accounts: AccountRepository,
    private val wallet: WalletVendorPort,
    private val targets: SweepTargetRepository,
    private val transactionStatuses: SweepTransactionStatusRepository,
    private val transactionRunner: TransactionRunner,
    private val alerts: SweepExecutionAlertPort,
    private val clock: Clock,
    private val properties: SweepProperties,
) {
    fun runOnce(): SweepReconciliationCycleResult {
        var completed = 0
        var pending = 0
        var failed = 0
        executions.findReconciling(properties.reconciliationBatchSize).forEach { execution ->
            try {
                when (reconcile(execution)) {
                    ReconciliationResult.COMPLETED -> completed += 1
                    ReconciliationResult.PENDING -> pending += 1
                }
            } catch (exception: RuntimeException) {
                failed += 1
                alerts.alert(
                    SweepExecutionAlert(
                        SweepExecutionStage.RECONCILIATION,
                        SweepTargetKey(execution.operatorAccountId, execution.network, execution.symbol),
                        exception,
                    ),
                )
            }
        }
        return SweepReconciliationCycleResult(completed, pending, failed)
    }

    private fun reconcile(execution: SweepExecution): ReconciliationResult {
        val vendorTransactionId = checkNotNull(execution.vendorTransactionId) { "reconciling sweep has no vendor transaction id" }
        val transaction = vendorTransactions.transaction(vendorTransactionId) ?: return ReconciliationResult.PENDING
        validateTransactionIdentity(execution, transaction)
        if (transaction.rawStatus in FAILED_VENDOR_STATUSES) {
            executions.markFailedAndRelease(execution.executionId, CoreDateTimes.now(clock))
            return ReconciliationResult.COMPLETED
        }
        if (transaction.rawStatus != "COMPLETED") return ReconciliationResult.PENDING
        if (transaction.networkRecords.isEmpty()) return ReconciliationResult.PENDING
        val transactionHash = resolvedTransactionHash(execution, transaction) ?: return ReconciliationResult.PENDING
        val mapping = checkNotNull(mappings.find(execution.network, execution.symbol)) { "sweep asset mapping not found" }
        val tokenContract = checkNotNull(mapping.contractAddress) { "native asset does not support batch sweep" }
        val tokenDecimals = erc20.decimals(execution.network, tokenContract)
        val receipt =
            receipts.receipt(
                execution.network,
                transactionHash,
                execution.sweepContractAddress,
                tokenDecimals,
            ) ?: return ReconciliationResult.PENDING
        if (!receipt.successful) {
            executions.markFailedAndRelease(execution.executionId, CoreDateTimes.now(clock))
            return ReconciliationResult.COMPLETED
        }
        val items = executions.findItems(execution.executionId)
        val reconciled = reconcileLegs(execution, items, receipt.legs)
        val itemAccounts = items.associateWith { item -> requiredAccount(item.accountId) }
        validateNetworkRecords(execution, transaction, transactionHash, mapping, items, itemAccounts, reconciled)
        finish(execution, items, itemAccounts, reconciled, mapping)
        return ReconciliationResult.COMPLETED
    }

    private fun validateTransactionIdentity(
        execution: SweepExecution,
        transaction: VendorTransaction,
    ) {
        check(transaction.transactionId == execution.vendorTransactionId) { "sweep vendor transaction id mismatch" }
        check(transaction.externalTransactionId == execution.externalTransactionId) { "sweep external transaction id mismatch" }
    }

    private fun resolvedTransactionHash(
        execution: SweepExecution,
        transaction: VendorTransaction,
    ): String? {
        val stored = execution.transactionHash
        val observed = transaction.transactionHash
        check(stored == null || observed == null || stored.equals(observed, ignoreCase = true)) {
            "sweep transaction hash mismatch"
        }
        return stored ?: observed
    }

    private fun reconcileLegs(
        execution: SweepExecution,
        items: List<SweepItem>,
        legs: List<SweepLegObservation>,
    ): List<SweepItemReconciliation> {
        check(items.size == execution.itemCount) { "stored sweep item count mismatch" }
        check(legs.size == items.size) { "SweepLeg count mismatch" }
        check(legs.map { it.itemSequence }.distinct().size == legs.size) { "duplicate SweepLeg itemSequence" }
        check(legs.map { it.logIndex }.distinct().size == legs.size) { "duplicate SweepLeg logIndex" }
        val legsBySequence = legs.associateBy { it.itemSequence }
        return items.sortedBy { it.sequence }.map { item ->
            val leg = checkNotNull(legsBySequence[item.sequence]) { "missing SweepLeg: sequence=${item.sequence}" }
            check(leg.executionId == execution.executionId) { "SweepLeg executionId mismatch" }
            check(leg.ownerAddress.equals(item.sourceAddress, ignoreCase = true)) { "SweepLeg owner mismatch" }
            check(amount(leg.requestedAmount).compareTo(amount(item.requestedAmount)) == 0) { "SweepLeg requested amount mismatch" }
            val actual = amount(leg.actualAmount)
            check(actual.signum() >= 0 && actual <= amount(item.requestedAmount)) { "invalid SweepLeg actual amount" }
            if (leg.successful) {
                check(actual.signum() > 0) { "successful SweepLeg actual amount must be positive" }
                check(leg.failureCode == ZERO_FAILURE_CODE) { "successful SweepLeg has failure code" }
            } else {
                check(actual.signum() == 0) { "failed SweepLeg actual amount must be zero" }
                check(leg.failureCode != ZERO_FAILURE_CODE) { "failed SweepLeg has no failure code" }
            }
            SweepItemReconciliation(
                sequence = item.sequence,
                actualAmount = actual.normalized(),
                status = if (leg.successful) SweepItemStatus.SUCCEEDED else SweepItemStatus.FAILED,
                failureCode = leg.failureCode.takeUnless { leg.successful },
                logIndex = leg.logIndex,
            )
        }
    }

    private fun validateNetworkRecords(
        execution: SweepExecution,
        transaction: VendorTransaction,
        transactionHash: String,
        mapping: VendorAssetMapping,
        items: List<SweepItem>,
        itemAccounts: Map<SweepItem, Account>,
        reconciled: List<SweepItemReconciliation>,
    ) {
        val itemByVault = itemAccounts.entries.associate { (item, account) -> account.vendorVaultId to item }
        check(itemByVault.size == items.size) { "sweep source vaults must be unique" }
        val relevant =
            transaction.networkRecords.filter { record ->
                record.source.type == "VAULT_ACCOUNT" &&
                    record.source.id in itemByVault &&
                    record.vendorAssetId == mapping.vendorAssetId &&
                    amount(record.netAmount).signum() > 0
            }
        check(relevant.none(VendorNetworkRecord::dropped)) { "dropped sweep network record" }
        relevant.forEach { record ->
            check(record.transactionHash?.equals(transactionHash, ignoreCase = true) == true) {
                "sweep network record transaction hash mismatch"
            }
        }
        val actualBySequence = reconciled.associateBy { it.sequence }
        itemByVault.forEach { (vaultId, item) ->
            val moved =
                relevant
                    .filter { it.source.id == vaultId }
                    .map { amount(it.netAmount) }
                    .fold(BigDecimal.ZERO, BigDecimal::add)
            val result = requireNotNull(actualBySequence[item.sequence])
            val expected = if (result.status == SweepItemStatus.SUCCEEDED) amount(result.actualAmount) else BigDecimal.ZERO
            check(moved.compareTo(expected) == 0) { "sweep network record amount mismatch: sequence=${item.sequence}" }
        }
    }

    private fun finish(
        execution: SweepExecution,
        items: List<SweepItem>,
        itemAccounts: Map<SweepItem, Account>,
        reconciled: List<SweepItemReconciliation>,
        mapping: VendorAssetMapping,
    ) {
        val resultsBySequence = reconciled.associateBy { it.sequence }
        val successfulItems = items.filter { resultsBySequence[it.sequence]?.status == SweepItemStatus.SUCCEEDED }
        val finalizedBefore = successfulItems.associateWith { transactionStatuses.finalizedDepositIds(key(execution, it)) }
        val belowMinimum =
            successfulItems.associateWith { item ->
                val available = amount(wallet.balanceOf(requireNotNull(itemAccounts[item]).vendorVaultId, mapping.vendorAssetId).available)
                val minimum =
                    checkNotNull(properties.minimumAmount(execution.network, execution.symbol)) {
                        "sweep minimum amount is not configured"
                    }
                available < minimum
            }
        val actualTotal = reconciled.map { amount(it.actualAmount) }.fold(BigDecimal.ZERO, BigDecimal::add).normalized()
        val finalStatus =
            if (reconciled.all { it.status == SweepItemStatus.SUCCEEDED }) {
                SweepExecutionStatus.COMPLETED
            } else {
                SweepExecutionStatus.PARTIAL
            }
        transactionRunner.run {
            val current = executions.findByIdForUpdate(execution.executionId)
            if (current?.status != SweepExecutionStatus.RECONCILING) {
                throw ConflictException("sweepExecution", execution.executionId)
            }
            items.sortedBy { it.accountId }.forEach { item ->
                val target = targets.findByKeyForUpdate(key(execution, item))
                if (target?.activeSweepExecutionId != execution.executionId || target.activeItemSequence != item.sequence) {
                    throw ConflictException("sweepTarget", item.accountId)
                }
            }
            val deletable =
                successfulItems.associateWith { item ->
                    belowMinimum[item] == true &&
                        transactionStatuses.finalizedDepositIds(key(execution, item)) == finalizedBefore[item]
                }
            executions.completeReconciliation(
                execution.executionId,
                reconciled,
                finalStatus,
                actualTotal,
                CoreDateTimes.now(clock),
            )
            items.forEach { item ->
                val key = key(execution, item)
                val changed =
                    if (deletable[item] == true) {
                        targets.deleteClaim(key, execution.executionId, item.sequence)
                    } else {
                        targets.releaseClaim(key, execution.executionId, item.sequence)
                    }
                if (!changed) throw ConflictException("sweepTarget", item.accountId)
            }
        }
    }

    private fun requiredAccount(accountId: String): Account =
        checkNotNull(accounts.findByAccountId(accountId)) { "sweep source account not found: accountId=$accountId" }

    private fun key(
        execution: SweepExecution,
        item: SweepItem,
    ): SweepTargetKey = SweepTargetKey(item.accountId, execution.network, execution.symbol)

    private fun amount(value: String): BigDecimal =
        BigDecimal(value).also { require(it.signum() >= 0) { "sweep amount must not be negative" } }

    private fun BigDecimal.normalized(): String = stripTrailingZeros().toPlainString()

    private enum class ReconciliationResult {
        COMPLETED,
        PENDING,
    }

    private companion object {
        val FAILED_VENDOR_STATUSES = setOf("FAILED", "REJECTED", "BLOCKED")
        val ZERO_FAILURE_CODE = "0".repeat(64)
    }
}

@Component
@ConditionalOnProperty(prefix = "bcm.sweep", name = ["enabled"], havingValue = "true")
class SweepBatchReconciliationJob(
    private val service: SweepBatchReconciliationService,
) {
    @Scheduled(fixedDelayString = "\${bcm.sweep.fixed-delay-millis:60000}")
    fun run() {
        val result = service.runOnce()
        logger.info("batch sweep reconciliation cycle completed result={}", result)
    }

    private companion object {
        val logger = LoggerFactory.getLogger(SweepBatchReconciliationJob::class.java)
    }
}
