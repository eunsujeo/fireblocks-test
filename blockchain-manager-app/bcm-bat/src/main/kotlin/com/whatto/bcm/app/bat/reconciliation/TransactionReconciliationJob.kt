package com.whatto.bcm.app.bat.reconciliation

import com.whatto.bcm.app.bat.stall.StallTerminalObservationHandler
import com.whatto.bcm.app.bat.stall.TerminalObservationOutcome
import com.whatto.bcm.domain.job.JobStateRepository
import com.whatto.bcm.domain.monitoring.OperationalAlert
import com.whatto.bcm.domain.monitoring.OperationalAlertChannel
import com.whatto.bcm.domain.monitoring.OperationalAlertRoute
import com.whatto.bcm.domain.monitoring.OperationalMetricsPort
import com.whatto.bcm.domain.tx.StallCandidate
import com.whatto.bcm.domain.tx.TxObservationQuarantineSource
import com.whatto.bcm.domain.tx.TxReconciliationMissingWebhookAlert
import com.whatto.bcm.domain.tx.TxReconciliationMissingWebhookAlertPort
import com.whatto.bcm.domain.tx.TxReconciliationObservationEvidence
import com.whatto.bcm.domain.tx.TxReconciliationPolicy
import com.whatto.bcm.domain.tx.TxReconciliationRecord
import com.whatto.bcm.domain.tx.TxReconciliationReport
import com.whatto.bcm.domain.tx.TxReconciliationReportPort
import com.whatto.bcm.domain.tx.TxReconciliationRepository
import com.whatto.bcm.domain.tx.TxReconciliationSnapshot
import com.whatto.bcm.domain.tx.TxReconciliationTrackingStoppedAlert
import com.whatto.bcm.domain.tx.TxReconciliationTrackingStoppedAlertPort
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.domain.vendor.ObservedVendorTransaction
import com.whatto.bcm.domain.vendor.PhysicalTransactionEvidence
import com.whatto.bcm.domain.vendor.VendorStatusTranslator
import com.whatto.bcm.domain.vendor.VendorTransaction
import com.whatto.bcm.domain.vendor.VendorTransactionEvidencePort
import com.whatto.bcm.domain.vendor.VendorTransactionPageRequest
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
    private val vendor: VendorTransactionEvidencePort,
    private val reconciliation: TxReconciliationRepository,
    private val statusTranslator: VendorStatusTranslator,
    private val terminalObservations: StallTerminalObservationHandler,
    private val reports: TxReconciliationReportPort,
    private val missingWebhookAlerts: TxReconciliationMissingWebhookAlertPort,
    private val trackingStoppedAlerts: TxReconciliationTrackingStoppedAlertPort,
    private val metrics: OperationalMetricsPort,
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

        val stoppedTrackingCount =
            reconciliation.markExpiredPendingStopped(
                CoreDateTimes.format(current.minusSeconds(properties.pendingMaxAgeSeconds)),
                runAt,
            )
        if (stoppedTrackingCount > 0) {
            trackingStoppedAlerts.alert(TxReconciliationTrackingStoppedAlert(runAt, stoppedTrackingCount))
        }
        val windowRecords = reconciliation.findCreatedBetween(from, to)
        val selected = selectRootObservations(allTransactions(from, to))
        reconciliation
            .claimPendingForReconciliation(
                CoreDateTimes.format(current.minusSeconds(properties.pendingStaleSeconds)),
                runAt,
                properties.pendingMaxLookupsPerRun,
                selected.keys.toSet(),
            ).forEach { pending ->
                vendor
                    .observedTransaction(pending.record.activeVendorTxId)
                    ?.let { observed ->
                        observed.transaction.terminalStatusForReconciliation()?.let { status ->
                            selected[pending.record.vendorTxId] = RootObservation(pending, observed, status)
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
        val quarantined = mutableListOf<TerminalObservationOutcome.Quarantined>()
        result.mismatches
            .filter(TxReconciliationPolicy::shouldRecover)
            .forEach { mismatch ->
                val observation = checkNotNull(selected[mismatch.rootVendorTransactionId])
                val managed = checkNotNull(observation.managed)
                val outcome =
                    terminalObservations.observe(
                        StallCandidate(managed.record, managed.submissionType, managed.sweepExecutionId),
                        observation.observed,
                        to,
                        TxObservationQuarantineSource.RECONCILIATION,
                    )
                // **격리를 복구 성공으로 세지 않는다**(03 V37) — 세면 격리가 "놓친 웹훅을 복구했다"로 보고된다.
                // 격리 영속화는 처리 완료이므로 창은 전진한다. 해소 책임은 열린 격리 행이 가져간다.
                when (outcome) {
                    TerminalObservationOutcome.Applied -> recoveredCount += 1
                    is TerminalObservationOutcome.Quarantined -> quarantined += outcome
                    TerminalObservationOutcome.Deferred -> Unit
                }
            }
        if (quarantined.isNotEmpty()) {
            logger.warn(
                "대사 관찰 격리 quarantined={} fields={}",
                quarantined.size,
                quarantined.map(TerminalObservationOutcome.Quarantined::field).distinct(),
            )
        }
        metrics.recordReconciliation(recoveredCount)
        if (recoveredCount > 0) {
            missingWebhookAlerts.alert(TxReconciliationMissingWebhookAlert(from, to, recoveredCount))
        }
        reports.report(TxReconciliationReport(from, to, result, recoveredCount, stoppedTrackingCount))
        jobs.markSucceeded(JOB_NAME, to)
    }

    private fun allTransactions(
        from: String,
        to: String,
    ): List<ObservedVendorTransaction> {
        val transactions = mutableListOf<ObservedVendorTransaction>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page =
                vendor.observedTransactions(
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
                    it.transaction.createdAtEpochMillis in fromEpochMillis..toEpochMillis &&
                        it.transaction.terminalStatusForReconciliation() != null
                }
            cursor = page.next
            if (cursor != null && !seenCursors.add(cursor)) {
                throw IllegalStateException("vendor returned a repeated transaction cursor: cursor=$cursor")
            }
        } while (cursor != null)
        return transactions
    }

    private fun selectRootObservations(observations: List<ObservedVendorTransaction>): MutableMap<String, RootObservation> {
        val selected = linkedMapOf<String, RootObservation>()
        observations.forEach { observed ->
            val transaction = observed.transaction
            val status = checkNotNull(transaction.terminalStatusForReconciliation())
            val managed = reconciliation.findByPhysicalVendorTransactionId(transaction.transactionId)
            if (managed == null) {
                selected[transaction.transactionId] = RootObservation(null, observed, status)
                return@forEach
            }
            if (
                transaction.transactionId != managed.record.activeVendorTxId &&
                !PhysicalTransactionEvidence.hasSucceeded(transaction.statusObservation())
            ) {
                return@forEach
            }
            val candidate = RootObservation(managed, observed, status)
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
        val observed: ObservedVendorTransaction,
        val status: TxStatus,
    ) {
        val transaction: VendorTransaction get() = observed.transaction

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
        /** 격리 경보는 **항목 이름만** 남긴다 — 금액·주소는 보호 저장소에만 둔다(03 V37). */
        private val logger = LoggerFactory.getLogger(TransactionReconciliationJob::class.java)
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

@Component
class OperationalTxReconciliationMissingWebhookAlertAdapter(
    private val channel: OperationalAlertChannel,
) : TxReconciliationMissingWebhookAlertPort {
    override fun alert(alert: TxReconciliationMissingWebhookAlert) {
        channel.publish(
            OperationalAlert(
                route = OperationalAlertRoute.RECONCILIATION,
                type = "reconciliation.alert.missing-webhook",
                identifiers = mapOf("from" to alert.from, "to" to alert.to),
                context = mapOf("recoveredCount" to alert.recoveredCount.toString()),
            ),
        )
    }
}

@Component
class OperationalTxReconciliationTrackingStoppedAlertAdapter(
    private val channel: OperationalAlertChannel,
) : TxReconciliationTrackingStoppedAlertPort {
    override fun alert(alert: TxReconciliationTrackingStoppedAlert) {
        channel.publish(
            OperationalAlert(
                route = OperationalAlertRoute.RECONCILIATION,
                type = "reconciliation.alert.tracking-stopped",
                identifiers = mapOf("stoppedAt" to alert.stoppedAt),
                context = mapOf("count" to alert.count.toString()),
            ),
        )
    }
}
