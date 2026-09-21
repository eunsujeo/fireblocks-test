package com.whatto.bcm.app.bat.reconciliation

import com.whatto.bcm.app.bat.stall.StallTerminalObservationHandler
import com.whatto.bcm.domain.job.JobState
import com.whatto.bcm.domain.job.JobStateRepository
import com.whatto.bcm.domain.monitoring.NoOpOperationalMetricsPort
import com.whatto.bcm.domain.monitoring.OperationalMetricsPort
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.tx.TxReconciliationMissingWebhookAlert
import com.whatto.bcm.domain.tx.TxReconciliationMissingWebhookAlertPort
import com.whatto.bcm.domain.tx.TxReconciliationRecord
import com.whatto.bcm.domain.tx.TxReconciliationReport
import com.whatto.bcm.domain.tx.TxReconciliationReportPort
import com.whatto.bcm.domain.tx.TxReconciliationRepository
import com.whatto.bcm.domain.tx.TxReconciliationTrackingStoppedAlert
import com.whatto.bcm.domain.tx.TxReconciliationTrackingStoppedAlertPort
import com.whatto.bcm.domain.tx.TxRecord
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.domain.vendor.VendorPage
import com.whatto.bcm.domain.vendor.VendorStatusObservation
import com.whatto.bcm.domain.vendor.VendorStatusTranslator
import com.whatto.bcm.domain.vendor.VendorTransaction
import com.whatto.bcm.domain.vendor.VendorTransactionLifecycleStage
import com.whatto.bcm.domain.vendor.VendorTransactionPageRequest
import com.whatto.bcm.domain.vendor.VendorTransactionPeer
import com.whatto.bcm.domain.vendor.VendorTransactionPort
import com.whatto.bcm.domain.vendor.VendorTransactionRequest
import com.whatto.bcm.domain.vendor.VendorTransactionSubmission
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset

class TransactionReconciliationJobTest {
    @Test
    fun `종결 목록과 창 밖 미종결 단건을 대사하고 알려진 상태 차이만 정상 경로로 회수한다`() {
        val match = record("tx-match", TxStatus.FINALIZED, "20260807115100")
        val managerOnly = record("tx-manager-only", TxStatus.FAILED, "20260807115200")
        val statusMismatch = record("tx-status", TxStatus.CONFIRMED, "20260807115300")
        val terminalMismatch = record("tx-terminal-mismatch", TxStatus.FINALIZED, "20260807115400")
        val stuck = record("tx-stuck", TxStatus.CONFIRMED, "20260806120000")
        val repository =
            RecordingReconciliationRepository(
                detected = listOf(reconciliation(match), reconciliation(managerOnly), reconciliation(terminalMismatch)),
                pending = listOf(reconciliation(stuck)),
                byPhysical =
                    mapOf(
                        "tx-match" to reconciliation(match),
                        "tx-status" to reconciliation(statusMismatch),
                        "tx-terminal-mismatch" to reconciliation(terminalMismatch),
                        "tx-stuck" to reconciliation(stuck),
                    ),
            )
        val vendor =
            RecordingVendor(
                pages =
                    mapOf(
                        null to
                            VendorPage(
                                listOf(
                                    transaction("tx-match", "COMPLETED"),
                                    transaction("tx-vendor-only", "FAILED"),
                                    transaction("tx-progress", "CONFIRMING"),
                                    transaction("tx-incoming-rejected", "REJECTED", sourceType = "UNKNOWN"),
                                    transaction("tx-too-new", "COMPLETED", createdAt = "20260807115501"),
                                ),
                                "cursor-2",
                            ),
                        "cursor-2" to
                            VendorPage(
                                listOf(
                                    transaction("tx-status", "COMPLETED"),
                                    transaction("tx-terminal-mismatch", "FAILED"),
                                ),
                                null,
                            ),
                    ),
                singles = mapOf("tx-stuck" to transaction("tx-stuck", "COMPLETED")),
            )
        val jobs = RecordingJobs(JobState(JOB_NAME, "20260807114000", "20260807115000"))
        val reports = mutableListOf<TxReconciliationReport>()
        val recovered = mutableListOf<String>()
        val metrics = RecordingOperationalMetrics()
        val missingAlerts = mutableListOf<TxReconciliationMissingWebhookAlert>()
        val job =
            TransactionReconciliationJob(
                vendor = vendor,
                reconciliation = repository,
                statusTranslator = TestStatusTranslator,
                terminalObservations = StallTerminalObservationHandler { candidate, _, _ -> recovered += candidate.record.vendorTxId },
                reports = TxReconciliationReportPort(reports::add),
                missingWebhookAlerts = TxReconciliationMissingWebhookAlertPort(missingAlerts::add),
                trackingStoppedAlerts = TxReconciliationTrackingStoppedAlertPort { },
                metrics = metrics,
                jobs = jobs,
                clock = CLOCK,
                properties = TransactionReconciliationProperties(enabled = true),
            )

        job.run()

        assertThat(vendor.pageRequests.map { it.cursor }).containsExactly(null, "cursor-2")
        assertThat(vendor.pageRequests).allSatisfy {
            assertThat(it.sourceVaultId).isNull()
            assertThat(it.order).isNull()
            assertThat(it.limit).isEqualTo(500)
        }
        assertThat(vendor.pageRequests.first().afterEpochMillis).isEqualTo(epochMillis("20260807115000") - 1)
        assertThat(vendor.pageRequests.first().beforeEpochMillis).isEqualTo(epochMillis("20260807115500") + 999)
        assertThat(vendor.singleLookups).containsExactly("tx-stuck")
        assertThat(recovered).containsExactly("tx-status", "tx-stuck")
        assertThat(reports.single().result.matchedCount).isEqualTo(1)
        assertThat(
            reports
                .single()
                .result.mismatches
                .map { it.rootVendorTransactionId },
        ).containsExactly(
            "tx-manager-only",
            "tx-status",
            "tx-stuck",
            "tx-terminal-mismatch",
            "tx-vendor-only",
        )
        assertThat(reports.single().recoveredCount).isEqualTo(2)
        assertThat(reports.single().stoppedTrackingCount).isZero()
        assertThat(metrics.recoveredCounts).containsExactly(2)
        assertThat(missingAlerts).containsExactly(
            TxReconciliationMissingWebhookAlert("20260807115000", "20260807115500", 2),
        )
        assertThat(jobs.started).containsExactly(JOB_NAME to NOW)
        assertThat(jobs.succeeded).containsExactly(JOB_NAME to "20260807115500")
        assertThat(repository.createdWindows).containsExactly("20260807115000" to "20260807115500")
        assertThat(repository.stopRequests)
            .containsExactly("20260731120000" to NOW)
        assertThat(repository.claimRequests).containsExactly(
            ReconciliationClaimRequest(
                changedAtOrBefore = "20260807115000",
                checkedAt = NOW,
                limit = 100,
                excludedVendorTransactionIds =
                    setOf("tx-match", "tx-status", "tx-terminal-mismatch", "tx-vendor-only"),
            ),
        )
    }

    @Test
    fun `벤더가 같은 페이지 커서를 반복하면 성공 커서를 전진시키지 않는다`() {
        val vendor =
            RecordingVendor(
                pages =
                    mapOf(
                        null to VendorPage(emptyList(), "repeated"),
                        "repeated" to VendorPage(emptyList(), "repeated"),
                    ),
            )
        val jobs = RecordingJobs(null)
        val job =
            TransactionReconciliationJob(
                vendor = vendor,
                reconciliation = RecordingReconciliationRepository(),
                statusTranslator = TestStatusTranslator,
                terminalObservations = StallTerminalObservationHandler { _, _, _ -> error("not expected") },
                reports = TxReconciliationReportPort { error("not expected") },
                missingWebhookAlerts = TxReconciliationMissingWebhookAlertPort { error("not expected") },
                trackingStoppedAlerts = TxReconciliationTrackingStoppedAlertPort { },
                metrics = NoOpOperationalMetricsPort,
                jobs = jobs,
                clock = CLOCK,
                properties = TransactionReconciliationProperties(enabled = true, initialLookbackSeconds = 600),
            )

        assertThatThrownBy(job::run)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("repeated")

        assertThat(vendor.pageRequests.first().afterEpochMillis).isEqualTo(epochMillis("20260807114500") - 1)
        assertThat(jobs.succeeded).isEmpty()
    }

    @Test
    fun `대사 누락이 0이면 메트릭을 0으로 갱신하고 운영 알림 없이 성공 창을 전진시킨다`() {
        val metrics = RecordingOperationalMetrics()
        val missingAlerts = mutableListOf<TxReconciliationMissingWebhookAlert>()
        val jobs = RecordingJobs(JobState(JOB_NAME, "20260807114000", "20260807115000"))
        val job =
            TransactionReconciliationJob(
                vendor = RecordingVendor(mapOf(null to VendorPage(emptyList(), null))),
                reconciliation = RecordingReconciliationRepository(),
                statusTranslator = TestStatusTranslator,
                terminalObservations = StallTerminalObservationHandler { _, _, _ -> error("not expected") },
                reports = TxReconciliationReportPort { assertThat(it.recoveredCount).isZero() },
                missingWebhookAlerts = TxReconciliationMissingWebhookAlertPort(missingAlerts::add),
                trackingStoppedAlerts = TxReconciliationTrackingStoppedAlertPort { },
                metrics = metrics,
                jobs = jobs,
                clock = CLOCK,
                properties = TransactionReconciliationProperties(enabled = true),
            )

        job.run()

        assertThat(metrics.recoveredCounts).containsExactly(0)
        assertThat(missingAlerts).isEmpty()
        assertThat(jobs.succeeded).containsExactly(JOB_NAME to "20260807115500")
    }

    @Test
    fun `창 밖 미종결 단건 조회가 실패하면 성공 창을 전진시키지 않는다`() {
        val pending = record("tx-stuck", TxStatus.CONFIRMED, "20260806120000")
        val jobs = RecordingJobs(null)
        val job =
            TransactionReconciliationJob(
                vendor =
                    RecordingVendor(
                        pages = mapOf(null to VendorPage(emptyList(), null)),
                        failingSingleId = "tx-stuck",
                    ),
                reconciliation = RecordingReconciliationRepository(pending = listOf(reconciliation(pending))),
                statusTranslator = TestStatusTranslator,
                terminalObservations = StallTerminalObservationHandler { _, _, _ -> error("not expected") },
                reports = TxReconciliationReportPort { error("not expected") },
                missingWebhookAlerts = TxReconciliationMissingWebhookAlertPort { error("not expected") },
                trackingStoppedAlerts = TxReconciliationTrackingStoppedAlertPort { },
                metrics = NoOpOperationalMetricsPort,
                jobs = jobs,
                clock = CLOCK,
                properties = TransactionReconciliationProperties(enabled = true),
            )

        assertThatThrownBy(job::run)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("single lookup")
        assertThat(jobs.succeeded).isEmpty()
    }

    @Test
    fun `대사 누락 복구 처리가 실패하면 메트릭과 알림 및 성공 창을 기록하지 않는다`() {
        val pending = record("tx-status", TxStatus.CONFIRMED, "20260807115300")
        val jobs = RecordingJobs(null)
        val metrics = RecordingOperationalMetrics()
        val missingAlerts = mutableListOf<TxReconciliationMissingWebhookAlert>()
        val job =
            TransactionReconciliationJob(
                vendor =
                    RecordingVendor(
                        mapOf(null to VendorPage(listOf(transaction("tx-status", "COMPLETED")), null)),
                    ),
                reconciliation =
                    RecordingReconciliationRepository(
                        byPhysical = mapOf("tx-status" to reconciliation(pending)),
                    ),
                statusTranslator = TestStatusTranslator,
                terminalObservations = StallTerminalObservationHandler { _, _, _ -> error("recovery failed") },
                reports = TxReconciliationReportPort { error("not expected") },
                missingWebhookAlerts = TxReconciliationMissingWebhookAlertPort(missingAlerts::add),
                trackingStoppedAlerts = TxReconciliationTrackingStoppedAlertPort { },
                metrics = metrics,
                jobs = jobs,
                clock = CLOCK,
                properties = TransactionReconciliationProperties(enabled = true),
            )

        assertThatThrownBy(job::run)
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("recovery failed")
        assertThat(metrics.recoveredCounts).isEmpty()
        assertThat(missingAlerts).isEmpty()
        assertThat(jobs.succeeded).isEmpty()
    }

    @Test
    fun `최대 나이 초과로 새로 중단한 거래는 뒤의 벤더 목록 조회가 실패해도 즉시 알린다`() {
        val stoppedAlerts = mutableListOf<TxReconciliationTrackingStoppedAlert>()
        val jobs = RecordingJobs(null)
        val job =
            TransactionReconciliationJob(
                vendor = RecordingVendor(emptyMap()),
                reconciliation = RecordingReconciliationRepository(stoppedCount = 2),
                statusTranslator = TestStatusTranslator,
                terminalObservations = StallTerminalObservationHandler { _, _, _ -> error("not expected") },
                reports = TxReconciliationReportPort { error("not expected") },
                missingWebhookAlerts = TxReconciliationMissingWebhookAlertPort { error("not expected") },
                trackingStoppedAlerts = TxReconciliationTrackingStoppedAlertPort(stoppedAlerts::add),
                metrics = NoOpOperationalMetricsPort,
                jobs = jobs,
                clock = CLOCK,
                properties = TransactionReconciliationProperties(enabled = true),
            )

        assertThatThrownBy(job::run).isInstanceOf(IllegalStateException::class.java)
        assertThat(stoppedAlerts).containsExactly(TxReconciliationTrackingStoppedAlert(NOW, 2))
        assertThat(jobs.succeeded).isEmpty()
    }

    private fun reconciliation(record: TxRecord) = TxReconciliationRecord(record, SubmissionTransactionType.WITHDRAWAL, null)

    private fun record(
        id: String,
        status: TxStatus,
        detectedAt: String,
    ) = TxRecord(
        vendorTxId = id,
        externalTxId = "wd-$id",
        accountId = "account-1",
        network = "ETHEREUM",
        symbol = "USDC",
        transactionHash = "0x$id",
        lastPublishedStatus = status,
        confirmationCount = if (status == TxStatus.FINALIZED) 1 else 0,
        // 관찰을 이미 통과한 행이다 — 첫 관찰 전 행만 vendorCreatedAt 을 비운다(03 V32).
        vendorCreatedAt = detectedAt,
        firstDetectedAt = detectedAt,
        lastChangedAt = detectedAt,
    )

    private fun transaction(
        id: String,
        status: String,
        sourceType: String = "VAULT_ACCOUNT",
        createdAt: String = "20260807115500",
    ) = VendorTransaction(
        transactionId = id,
        externalTransactionId = "wd-$id",
        vendorAssetId = "USDC_ETH",
        rawStatus = status,
        subStatus = null,
        transactionHash = "0x$id",
        source = VendorTransactionPeer(sourceType, if (sourceType == "VAULT_ACCOUNT") "71" else null),
        destination = VendorTransactionPeer("ONE_TIME_ADDRESS", null),
        sourceAddress = "0xfrom",
        destinationAddress = "0xto",
        amount = "1",
        confirmationCount = if (status == "COMPLETED") 1 else 0,
        createdAtEpochMillis = epochMillis(createdAt),
        lastUpdatedEpochMillis = epochMillis("20260807115500"),
        lifecycleStage =
            if (status ==
                "CONFIRMING"
            ) {
                VendorTransactionLifecycleStage.CONFIRMING
            } else {
                VendorTransactionLifecycleStage.TERMINAL
            },
    )

    private fun epochMillis(value: String): Long =
        LocalDateTime
            .of(
                value.substring(0, 4).toInt(),
                value.substring(4, 6).toInt(),
                value.substring(6, 8).toInt(),
                value.substring(8, 10).toInt(),
                value.substring(10, 12).toInt(),
                value.substring(12, 14).toInt(),
            ).atZone(ZONE)
            .toInstant()
            .toEpochMilli()

    private companion object {
        const val JOB_NAME = "tx-reconciliation"
        const val NOW = "20260807120000"
        val ZONE = ZoneOffset.UTC
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-08-07T12:00:00Z"), ZONE)
    }
}

private object TestStatusTranslator : VendorStatusTranslator {
    override fun translate(
        observation: VendorStatusObservation,
        network: String,
    ): TxStatus =
        when (observation.rawStatus) {
            "COMPLETED" -> TxStatus.FINALIZED
            "FAILED" -> TxStatus.FAILED
            "REJECTED", "BLOCKED" -> TxStatus.REJECTED
            "CONFIRMING" -> TxStatus.CONFIRMED
            else -> TxStatus.SUBMITTED
        }

    override fun terminalStatusForReconciliation(
        observation: VendorStatusObservation,
        sourceType: String,
    ): TxStatus? =
        when (observation.rawStatus) {
            "COMPLETED" -> TxStatus.FINALIZED
            "FAILED" -> TxStatus.FAILED
            "REJECTED", "BLOCKED" -> if (sourceType == "VAULT_ACCOUNT") TxStatus.REJECTED else null
            else -> null
        }
}

private class RecordingReconciliationRepository(
    private val detected: List<TxReconciliationRecord> = emptyList(),
    private val pending: List<TxReconciliationRecord> = emptyList(),
    private val byPhysical: Map<String, TxReconciliationRecord> = emptyMap(),
    private val stoppedCount: Int = 0,
) : TxReconciliationRepository {
    val createdWindows = mutableListOf<Pair<String, String>>()
    val stopRequests = mutableListOf<Pair<String, String>>()
    val claimRequests = mutableListOf<ReconciliationClaimRequest>()

    override fun findByPhysicalVendorTransactionId(vendorTransactionId: String): TxReconciliationRecord? = byPhysical[vendorTransactionId]

    override fun findCreatedBetween(
        createdAtOrAfter: String,
        createdAtOrBefore: String,
    ): List<TxReconciliationRecord> {
        createdWindows += createdAtOrAfter to createdAtOrBefore
        return detected
    }

    override fun markExpiredPendingStopped(
        detectedAtOrBefore: String,
        stoppedAt: String,
    ): Int {
        stopRequests += detectedAtOrBefore to stoppedAt
        return stoppedCount
    }

    override fun claimPendingForReconciliation(
        changedAtOrBefore: String,
        checkedAt: String,
        limit: Int,
        excludedVendorTransactionIds: Set<String>,
    ): List<TxReconciliationRecord> {
        claimRequests += ReconciliationClaimRequest(changedAtOrBefore, checkedAt, limit, excludedVendorTransactionIds)
        return pending.take(limit)
    }
}

private data class ReconciliationClaimRequest(
    val changedAtOrBefore: String,
    val checkedAt: String,
    val limit: Int,
    val excludedVendorTransactionIds: Set<String>,
)

private class RecordingVendor(
    private val pages: Map<String?, VendorPage<VendorTransaction>>,
    private val singles: Map<String, VendorTransaction> = emptyMap(),
    private val failingSingleId: String? = null,
) : VendorTransactionPort {
    val pageRequests = mutableListOf<VendorTransactionPageRequest>()
    val singleLookups = mutableListOf<String>()

    override fun transactions(request: VendorTransactionPageRequest): VendorPage<VendorTransaction> {
        pageRequests += request
        return checkNotNull(pages[request.cursor])
    }

    override fun transaction(transactionId: String): VendorTransaction? {
        singleLookups += transactionId
        check(transactionId != failingSingleId) { "single lookup failed: $transactionId" }
        return singles[transactionId]
    }

    override fun transactionByExternalTransactionId(externalTransactionId: String): VendorTransaction? = error("not used")

    override fun submitTransaction(request: VendorTransactionRequest): VendorTransactionSubmission = error("not used")
}

private class RecordingOperationalMetrics : OperationalMetricsPort by NoOpOperationalMetricsPort {
    val recoveredCounts = mutableListOf<Int>()

    override fun recordReconciliation(recoveredCount: Int) {
        recoveredCounts += recoveredCount
    }
}

private class RecordingJobs(
    private val initial: JobState?,
) : JobStateRepository {
    val started = mutableListOf<Pair<String, String>>()
    val succeeded = mutableListOf<Pair<String, String>>()

    override fun markStarted(
        jobName: String,
        at: String,
    ) {
        started += jobName to at
    }

    override fun markSucceeded(
        jobName: String,
        at: String,
    ) {
        succeeded += jobName to at
    }

    override fun find(jobName: String): JobState? = initial
}
