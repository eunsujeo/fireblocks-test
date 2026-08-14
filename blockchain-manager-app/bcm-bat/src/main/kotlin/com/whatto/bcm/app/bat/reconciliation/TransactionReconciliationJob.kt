package com.whatto.bcm.app.bat.reconciliation

import com.whatto.bcm.app.bat.stall.StallTerminalObservationHandler
import com.whatto.bcm.domain.job.JobStateRepository
import com.whatto.bcm.domain.tx.StallCandidate
import com.whatto.bcm.domain.tx.TxReconciliationObservationEvidence
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
        val current = CoreDateTimes.current(clock)
        val runAt = CoreDateTimes.format(current)
        val stabilized = current.minusSeconds(properties.stabilizationDelaySeconds)
        val to = CoreDateTimes.format(stabilized)
        val from =
            jobs.find(JOB_NAME)?.lastSucceededAt
                ?: CoreDateTimes.format(stabilized.minusSeconds(properties.initialLookbackSeconds))
        jobs.markStarted(JOB_NAME, runAt)

        val windowRecords = reconciliation.findCreatedBetween(from, to)
        val selected = selectRootObservations(allTransactions(from, to))
        val stoppedTrackingCount =
            reconciliation.markExpiredPendingStopped(
                CoreDateTimes.format(current.minusSeconds(properties.pendingMaxAgeSeconds)),
                runAt,
            )
        reconciliation
            .claimPendingForReconciliation(
                CoreDateTimes.format(current.minusSeconds(properties.pendingStaleSeconds)),
                runAt,
                properties.pendingMaxLookupsPerRun,
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
        windowRecords.filter { TxReconciliationPolicy.managerSnapshot(it) != null }.forEach {
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
        val managerSnapshots = managerByRoot.values.map { TxReconciliationPolicy.snapshot(it.record) }
        val result = TxReconciliationPolicy.compare(vendorSnapshots, managerSnapshots)
        var recoveredCount = 0
        result.mismatches
            .filter(TxReconciliationPolicy::shouldRecover)
            .forEach { mismatch ->
                val observation = checkNotNull(selected[mismatch.rootVendorTransactionId])
                val managed = checkNotNull(observation.managed)
                terminalObservations.observe(
                    StallCandidate(managed.record, managed.submissionType, managed.sweepExecutionId),
                    observation.transaction,
                    to,
                )
                recoveredCount += 1
            }
        reports.report(TxReconciliationReport(from, to, result, recoveredCount, stoppedTrackingCount))
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
                        beforeEpochMillis = epochMillis(to) + UPPER_SECOND_MILLIS,
                        order = null,
                        limit = VENDOR_PAGE_LIMIT,
                        cursor = cursor,
                    ),
                )
            val fromEpochMillis = epochMillis(from)
            val toEpochMillis = epochMillis(to) + UPPER_SECOND_MILLIS
            transactions +=
                page.data.filter {
                    it.createdAtEpochMillis in fromEpochMillis..toEpochMillis &&
                        it.terminalStatusForReconciliation() != null
                }
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
            if (
                previous == null ||
                TxReconciliationPolicy.isPreferredObservation(candidate.evidence(), previous.evidence())
            ) {
                selected[managed.record.vendorTxId] = candidate
            }
        }
        return selected
    }

    private fun epochMillis(value: String): Long = CoreDateTimes.toEpochMillis(value)

    private fun VendorTransaction.terminalStatusForReconciliation(): TxStatus? =
        statusTranslator.terminalStatusForReconciliation(statusObservation(), source.type)

    private data class RootObservation(
        val managed: TxReconciliationRecord?,
        val transaction: VendorTransaction,
        val status: TxStatus,
    ) {
        val rootVendorTransactionId: String
            get() = managed?.record?.vendorTxId ?: transaction.transactionId

        fun evidence() =
            TxReconciliationObservationEvidence(
                physicalVendorTransactionId = transaction.transactionId,
                activeVendorTransactionId = managed?.record?.activeVendorTxId,
                succeeded = PhysicalTransactionEvidence.hasSucceeded(transaction.statusObservation()),
            )
    }

    internal companion object {
        const val JOB_NAME = "tx-reconciliation"
        const val VENDOR_PAGE_LIMIT = 500
        const val CURSOR_OVERLAP_MILLIS = 1L
        const val UPPER_SECOND_MILLIS = 999L
    }
}

@ConfigurationProperties("bcm.tx-reconciliation")
data class TransactionReconciliationProperties(
    val enabled: Boolean = false,
    val fixedDelayMillis: Long = 600_000,
    val initialLookbackSeconds: Long = 600,
    val stabilizationDelaySeconds: Long = 300,
    val pendingStaleSeconds: Long = 600,
    val pendingMaxLookupsPerRun: Int = 100,
    val pendingMaxAgeSeconds: Long = 604_800,
) {
    init {
        require(fixedDelayMillis > 0) { "fixedDelayMillis must be positive" }
        require(initialLookbackSeconds > 0) { "initialLookbackSeconds must be positive" }
        require(stabilizationDelaySeconds > 0) { "stabilizationDelaySeconds must be positive" }
        require(pendingStaleSeconds > 0) { "pendingStaleSeconds must be positive" }
        require(pendingMaxLookupsPerRun > 0) { "pendingMaxLookupsPerRun must be positive" }
        require(pendingMaxAgeSeconds > 0) { "pendingMaxAgeSeconds must be positive" }
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
        if (report.stoppedTrackingCount > 0) {
            logger.warn("거래 대사 자동 추적 최대 나이 도달 count={}", report.stoppedTrackingCount)
        }
        logger.info(
            "거래 대사 완료 from={} to={} matched={} mismatches={} recovered={} stoppedTracking={}",
            report.from,
            report.to,
            report.result.matchedCount,
            report.result.mismatches.size,
            report.recoveredCount,
            report.stoppedTrackingCount,
        )
    }

    private companion object {
        val logger = LoggerFactory.getLogger(LoggingTxReconciliationReportAdapter::class.java)
    }
}
