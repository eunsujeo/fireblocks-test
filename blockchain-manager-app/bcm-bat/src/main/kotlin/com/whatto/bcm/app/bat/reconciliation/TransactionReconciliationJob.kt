package com.whatto.bcm.app.bat.reconciliation

import com.whatto.bcm.app.bat.stall.StallTerminalObservationHandler
import com.whatto.bcm.domain.job.JobStateRepository
import com.whatto.bcm.domain.tx.StallCandidate
import com.whatto.bcm.domain.tx.TxReconciliationMismatchType
import com.whatto.bcm.domain.tx.TxReconciliationPolicy
import com.whatto.bcm.domain.tx.TxReconciliationRecord
import com.whatto.bcm.domain.tx.TxReconciliationReport
import com.whatto.bcm.domain.tx.TxReconciliationReportPort
import com.whatto.bcm.domain.tx.TxReconciliationRepository
import com.whatto.bcm.domain.tx.TxReconciliationSnapshot
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.domain.vendor.PhysicalTransactionEvidence
import com.whatto.bcm.domain.vendor.VendorStatusTranslator
import com.whatto.bcm.domain.vendor.VendorTransaction
import com.whatto.bcm.domain.vendor.VendorTransactionPageRequest
import com.whatto.bcm.domain.vendor.VendorTransactionPort
import com.whatto.bcm.support.time.CoreDateTimes
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDateTime

@Component
@ConditionalOnProperty(prefix = "bcm.tx-reconciliation", name = ["enabled"], havingValue = "true")
class TransactionReconciliationJob(
    private val vendor: VendorTransactionPort,
    private val reconciliation: TxReconciliationRepository,
    private val statusTranslator: VendorStatusTranslator,
    private val terminalObservations: StallTerminalObservationHandler,
    private val reports: TxReconciliationReportPort,
    private val jobs: JobStateRepository,
    private val clock: Clock,
    private val properties: TransactionReconciliationProperties,
) {
    @Scheduled(fixedDelayString = "\${bcm.tx-reconciliation.fixed-delay-millis:600000}")
    fun run() {
        val current = LocalDateTime.now(clock)
        val to = CoreDateTimes.format(current)
        val from =
            jobs.find(JOB_NAME)?.lastSucceededAt
                ?: CoreDateTimes.format(current.minusSeconds(properties.initialLookbackSeconds))
        jobs.markStarted(JOB_NAME, to)

        val windowRecords = reconciliation.findDetectedBetween(from, to)
        val selected = selectRootObservations(allTransactions(from, to))
        reconciliation
            .findPendingChangedAtOrBefore(
                CoreDateTimes.format(current.minusSeconds(properties.pendingStaleSeconds)),
                properties.pendingBatchSize,
            ).filter { it.record.vendorTxId !in selected }
            .forEach { pending ->
                vendor
                    .transaction(pending.record.activeVendorTxId)
                    ?.let { transaction ->
                        transaction.terminalStatusForReconciliation()?.let { status ->
                            selected[pending.record.vendorTxId] = RootObservation(pending, transaction, status)
                        }
                    }
            }

        val managerByRoot = linkedMapOf<String, TxReconciliationRecord>()
        windowRecords.filter { it.isTerminalForReconciliation() }.forEach {
            managerByRoot[it.record.vendorTxId] = it
        }
        selected.values.mapNotNull(RootObservation::managed).forEach {
            managerByRoot[it.record.vendorTxId] = it
        }
        val vendorSnapshots =
            selected.values.map { observation ->
                TxReconciliationSnapshot(
                    observation.rootVendorTransactionId,
                    observation.status,
                )
            }
        val managerSnapshots =
            managerByRoot.values.map {
                TxReconciliationSnapshot(it.record.vendorTxId, it.record.lastPublishedStatus)
            }
        val result = TxReconciliationPolicy.compare(vendorSnapshots, managerSnapshots)
        var recoveredCount = 0
        result.mismatches
            .filter {
                it.type == TxReconciliationMismatchType.STATUS_MISMATCH &&
                    it.managerStatus in setOf(TxStatus.SUBMITTED, TxStatus.CONFIRMED)
            }.forEach { mismatch ->
                val observation = checkNotNull(selected[mismatch.rootVendorTransactionId])
                val managed = checkNotNull(observation.managed)
                terminalObservations.observe(
                    StallCandidate(managed.record, managed.submissionType, managed.sweepExecutionId),
                    observation.transaction,
                    to,
                )
                recoveredCount += 1
            }
        reports.report(TxReconciliationReport(from, to, result, recoveredCount))
        jobs.markSucceeded(JOB_NAME, to)
    }

    private fun allTransactions(
        from: String,
        to: String,
    ): List<VendorTransaction> {
        val transactions = mutableListOf<VendorTransaction>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page =
                vendor.transactions(
                    VendorTransactionPageRequest(
                        sourceVaultId = null,
                        afterEpochMillis = epochMillis(from) - CURSOR_OVERLAP_MILLIS,
                        beforeEpochMillis = epochMillis(to),
                        order = null,
                        limit = VENDOR_PAGE_LIMIT,
                        cursor = cursor,
                    ),
                )
            transactions += page.data.filter { it.terminalStatusForReconciliation() != null }
            cursor = page.next
            if (cursor != null && !seenCursors.add(cursor)) {
                throw IllegalStateException("vendor returned a repeated transaction cursor: cursor=$cursor")
            }
        } while (cursor != null)
        return transactions
    }

    private fun selectRootObservations(transactions: List<VendorTransaction>): MutableMap<String, RootObservation> {
        val selected = linkedMapOf<String, RootObservation>()
        transactions.forEach { transaction ->
            val status = checkNotNull(transaction.terminalStatusForReconciliation())
            val managed = reconciliation.findByPhysicalVendorTransactionId(transaction.transactionId)
            if (managed == null) {
                selected[transaction.transactionId] = RootObservation(null, transaction, status)
                return@forEach
            }
            if (
                transaction.transactionId != managed.record.activeVendorTxId &&
                !PhysicalTransactionEvidence.hasSucceeded(transaction.statusObservation())
            ) {
                return@forEach
            }
            val candidate = RootObservation(managed, transaction, status)
            val previous = selected[managed.record.vendorTxId]
            if (previous == null || candidate.preferredOver(previous)) {
                selected[managed.record.vendorTxId] = candidate
            }
        }
        return selected
    }

    private fun epochMillis(value: String): Long =
        CoreDateTimes
            .parse(value)
            .atZone(clock.zone)
            .toInstant()
            .toEpochMilli()

    private fun VendorTransaction.terminalStatusForReconciliation(): TxStatus? =
        statusTranslator.terminalStatusForReconciliation(statusObservation(), source.type)

    private fun TxReconciliationRecord.isTerminalForReconciliation(): Boolean =
        when (record.lastPublishedStatus) {
            TxStatus.FINALIZED, TxStatus.FAILED -> true
            TxStatus.REJECTED -> submissionType != null
            TxStatus.SUBMITTED, TxStatus.CONFIRMED -> false
        }

    private data class RootObservation(
        val managed: TxReconciliationRecord?,
        val transaction: VendorTransaction,
        val status: TxStatus,
    ) {
        val rootVendorTransactionId: String
            get() = managed?.record?.vendorTxId ?: transaction.transactionId

        fun preferredOver(previous: RootObservation): Boolean {
            val succeeds = PhysicalTransactionEvidence.hasSucceeded(transaction.statusObservation())
            val previousSucceeds = PhysicalTransactionEvidence.hasSucceeded(previous.transaction.statusObservation())
            if (succeeds != previousSucceeds) return succeeds
            return transaction.transactionId == managed?.record?.activeVendorTxId
        }
    }

    internal companion object {
        const val JOB_NAME = "tx-reconciliation"
        const val VENDOR_PAGE_LIMIT = 500
        const val CURSOR_OVERLAP_MILLIS = 1L
    }
}

@ConfigurationProperties("bcm.tx-reconciliation")
data class TransactionReconciliationProperties(
    val enabled: Boolean = false,
    val fixedDelayMillis: Long = 600_000,
    val initialLookbackSeconds: Long = 600,
    val pendingStaleSeconds: Long = 600,
    val pendingBatchSize: Int = 500,
) {
    init {
        require(fixedDelayMillis > 0) { "fixedDelayMillis must be positive" }
        require(initialLookbackSeconds > 0) { "initialLookbackSeconds must be positive" }
        require(pendingStaleSeconds > 0) { "pendingStaleSeconds must be positive" }
        require(pendingBatchSize > 0) { "pendingBatchSize must be positive" }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TransactionReconciliationProperties::class)
class TransactionReconciliationConfig

@Component
class LoggingTxReconciliationReportAdapter : TxReconciliationReportPort {
    override fun report(report: TxReconciliationReport) {
        report.result.mismatches.forEach { mismatch ->
            logger.warn(
                "거래 대사 불일치 type={} rootVendorTransactionId={} vendorStatus={} managerStatus={}",
                mismatch.type,
                mismatch.rootVendorTransactionId,
                mismatch.vendorStatus,
                mismatch.managerStatus,
            )
        }
        logger.info(
            "거래 대사 완료 from={} to={} matched={} mismatches={} recovered={}",
            report.from,
            report.to,
            report.result.matchedCount,
            report.result.mismatches.size,
            report.recoveredCount,
        )
    }

    private companion object {
        val logger = LoggerFactory.getLogger(LoggingTxReconciliationReportAdapter::class.java)
    }
}
