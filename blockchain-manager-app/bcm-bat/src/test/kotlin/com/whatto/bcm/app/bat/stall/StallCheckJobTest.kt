package com.whatto.bcm.app.bat.stall

import com.whatto.bcm.domain.job.JobState
import com.whatto.bcm.domain.job.JobStateRepository
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.tx.StallAlert
import com.whatto.bcm.domain.tx.StallAlertPort
import com.whatto.bcm.domain.tx.StallAlertReason
import com.whatto.bcm.domain.tx.StallCandidate
import com.whatto.bcm.domain.tx.StallCandidateRepository
import com.whatto.bcm.domain.tx.TxRecord
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.domain.vendor.VendorPage
import com.whatto.bcm.domain.vendor.VendorTransaction
import com.whatto.bcm.domain.vendor.VendorTransactionLifecycleStage
import com.whatto.bcm.domain.vendor.VendorTransactionPageRequest
import com.whatto.bcm.domain.vendor.VendorTransactionPeer
import com.whatto.bcm.domain.vendor.VendorTransactionPort
import com.whatto.bcm.domain.vendor.VendorTransactionRequest
import com.whatto.bcm.domain.vendor.VendorTransactionSubmission
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class StallCheckJobTest {
    @Test
    fun `막힘 점검 스케줄러는 기본 비활성이다`() {
        val properties = YamlPropertySourceLoader().load("application", ClassPathResource("application.yaml")).single()
        val environment = StandardEnvironment().apply { propertySources.addFirst(properties) }

        assertThat(environment.getProperty("bcm.stall-check.enabled", Boolean::class.java)).isFalse()
    }

    @Test
    fun `최신 관찰을 조회해 boost 가능·체인 전·미발견을 각각 한 번 경보한다`() {
        val candidates =
            RecordingStallCandidates(
                listOf(
                    candidate("tx-boost", SubmissionTransactionType.WITHDRAWAL),
                    candidate("tx-pre-chain", SubmissionTransactionType.WITHDRAWAL),
                    candidate("tx-missing", SubmissionTransactionType.WITHDRAWAL),
                ),
            )
        val vendor =
            RecordingVendor(
                mapOf(
                    "tx-boost" to transaction("tx-boost"),
                    "tx-pre-chain" to
                        transaction(
                            "tx-pre-chain",
                            stage = VendorTransactionLifecycleStage.PRE_CHAIN,
                            transactionHash = null,
                        ),
                ),
            )
        val alerts = mutableListOf<StallAlert>()
        val jobs = RecordingJobs()

        job(candidates, vendor, StallAlertPort(alerts::add), jobs).run()

        assertThat(vendor.lookups).containsExactly("tx-boost", "tx-pre-chain", "tx-missing")
        assertThat(alerts.map { it.reason })
            .containsExactly(
                StallAlertReason.AUTOMATIC_BOOST_DISABLED,
                StallAlertReason.PRE_CHAIN_DELAY,
                StallAlertReason.VENDOR_TRANSACTION_NOT_FOUND,
            )
        assertThat(candidates.alertedRoots).containsExactly("tx-boost", "tx-pre-chain", "tx-missing")
        assertThat(jobs.started).containsExactly(JOB_NAME to NOW)
        assertThat(jobs.succeeded).containsExactly(JOB_NAME to NOW)
    }

    @Test
    fun `이미 다른 실행자가 경보 표시한 후보는 경보를 중복 발송하지 않는다`() {
        val candidates = RecordingStallCandidates(listOf(candidate("tx-owned")), markAlert = false)
        val alerts = mutableListOf<StallAlert>()

        job(
            candidates,
            RecordingVendor(mapOf("tx-owned" to transaction("tx-owned"))),
            StallAlertPort(alerts::add),
            RecordingJobs(),
        ).run()

        assertThat(alerts).isEmpty()
    }

    @Test
    fun `한 벤더 조회가 실패해도 다음 후보를 계속하고 주기 성공은 기록하지 않는다`() {
        val candidates = RecordingStallCandidates(listOf(candidate("tx-error"), candidate("tx-next")))
        val vendor =
            RecordingVendor(
                transactions = mapOf("tx-next" to transaction("tx-next")),
                failingIds = setOf("tx-error"),
            )
        val alerts = mutableListOf<StallAlert>()
        val jobs = RecordingJobs()

        job(candidates, vendor, StallAlertPort(alerts::add), jobs).run()

        assertThat(vendor.lookups).containsExactly("tx-error", "tx-next")
        assertThat(alerts).hasSize(1)
        assertThat(jobs.succeeded).isEmpty()
    }

    private fun job(
        candidates: StallCandidateRepository,
        vendor: VendorTransactionPort,
        alerts: StallAlertPort,
        jobs: JobStateRepository,
    ) = StallCheckJob(
        candidates,
        vendor,
        alerts,
        jobs,
        Clock.fixed(Instant.parse("2026-08-07T03:00:00Z"), ZoneId.of("Asia/Seoul")),
        StallCheckProperties(enabled = true, staleAfterSeconds = 300, batchSize = 100),
    )

    private companion object {
        const val NOW = "20260807120000"
        const val JOB_NAME = "stall-check"
    }
}

private fun candidate(
    vendorTransactionId: String,
    type: SubmissionTransactionType? = SubmissionTransactionType.WITHDRAWAL,
) = StallCandidate(
    record =
        TxRecord(
            vendorTxId = vendorTransactionId,
            activeVendorTxId = vendorTransactionId,
            externalTxId = "wd-$vendorTransactionId",
            accountId = "account-1",
            network = "ETHEREUM",
            symbol = "USDC",
            transactionHash = null,
            lastPublishedStatus = TxStatus.CONFIRMED,
            confirmationCount = 0,
            firstDetectedAt = "20260807110000",
            lastChangedAt = "20260807110000",
        ),
    submissionType = type,
)

private fun transaction(
    vendorTransactionId: String,
    stage: VendorTransactionLifecycleStage = VendorTransactionLifecycleStage.CONFIRMING,
    transactionHash: String? = "0xabc",
) = VendorTransaction(
    transactionId = vendorTransactionId,
    externalTransactionId = "wd-$vendorTransactionId",
    vendorAssetId = "USDC_ERC20",
    rawStatus = "CONFIRMING",
    subStatus = null,
    transactionHash = transactionHash,
    source = VendorTransactionPeer("VAULT_ACCOUNT", "1"),
    destination = VendorTransactionPeer("ONE_TIME_ADDRESS", null),
    sourceAddress = null,
    destinationAddress = null,
    amount = "1",
    confirmationCount = 0,
    createdAtEpochMillis = 0,
    lastUpdatedEpochMillis = 0,
    lifecycleStage = stage,
)

private class RecordingStallCandidates(
    private val candidates: List<StallCandidate>,
    private val markAlert: Boolean = true,
) : StallCandidateRepository {
    val alertedRoots = mutableListOf<String>()

    override fun findStallCandidates(
        changedBefore: String,
        limit: Int,
    ): List<StallCandidate> = candidates.take(limit)

    override fun markStallAlertedIfAbsent(
        candidate: TxRecord,
        alertedAt: String,
    ): Boolean {
        if (markAlert) alertedRoots += candidate.vendorTxId
        return markAlert
    }
}

private class RecordingVendor(
    private val transactions: Map<String, VendorTransaction>,
    private val failingIds: Set<String> = emptySet(),
) : VendorTransactionPort {
    val lookups = mutableListOf<String>()

    override fun transaction(transactionId: String): VendorTransaction? {
        lookups += transactionId
        if (transactionId in failingIds) error("simulated vendor failure")
        return transactions[transactionId]
    }

    override fun transactionByExternalTransactionId(externalTransactionId: String): VendorTransaction? = null

    override fun submitTransaction(request: VendorTransactionRequest): VendorTransactionSubmission = error("stall check must not submit")

    override fun transactions(request: VendorTransactionPageRequest): VendorPage<VendorTransaction> = VendorPage(emptyList(), null)
}

private class RecordingJobs : JobStateRepository {
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

    override fun find(jobName: String): JobState? = null
}
