package com.whatto.bcm.app.bat.fee

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.asset.VendorAssetMappingRepository
import com.whatto.bcm.domain.fee.NetworkFeeQuote
import com.whatto.bcm.domain.fee.NetworkFeeQuoteRepository
import com.whatto.bcm.domain.job.JobStateRepository
import com.whatto.bcm.domain.vendor.VendorNetworkFee
import com.whatto.bcm.domain.vendor.VendorNetworkFeeEstimate
import com.whatto.bcm.domain.vendor.VendorNetworkFeePort
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class NetworkFeeQuoteCollectionJobTest {
    @Test
    fun `등록된 모든 자산의 세 fee level을 같은 시각에 저장하고 성공 heartbeat를 남긴다`() {
        val mappings = RecordingMappings(listOf(mapping("USDC", "USDC_ERC20"), mapping("ETH", "ETH")))
        val vendor = RecordingVendor(mapOf("USDC_ERC20" to estimate(), "ETH" to estimate()))
        val quotes = RecordingQuotes()
        val jobs = RecordingJobs()

        job(mappings, vendor, quotes, jobs).run()

        assertThat(quotes.saved).hasSize(6)
        assertThat(quotes.saved.map(NetworkFeeQuote::observedAt)).containsOnly(NOW)
        assertThat(quotes.saved.map(NetworkFeeQuote::vendorAssetId))
            .containsExactly("USDC_ERC20", "USDC_ERC20", "USDC_ERC20", "ETH", "ETH", "ETH")
        assertThat(jobs.started).containsExactly("network-fee-quote-collection" to NOW)
        assertThat(jobs.succeeded).containsExactly("network-fee-quote-collection" to NOW)
    }

    @Test
    fun `자산 하나의 견적 호출이 실패하면 어떤 견적과 성공 heartbeat도 남기지 않는다`() {
        val mappings = RecordingMappings(listOf(mapping("USDC", "USDC_ERC20"), mapping("ETH", "ETH")))
        val vendor = RecordingVendor(mapOf("USDC_ERC20" to estimate()), failureAssetId = "ETH")
        val quotes = RecordingQuotes()
        val jobs = RecordingJobs()

        assertThatThrownBy { job(mappings, vendor, quotes, jobs).run() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("unavailable")

        assertThat(quotes.saved).isEmpty()
        assertThat(jobs.succeeded).isEmpty()
    }

    @Test
    fun `수집 주기는 Fireblocks 캐시보다 짧게 설정할 수 없다`() {
        assertThatThrownBy { NetworkFeeQuoteCollectionProperties(fixedDelayMillis = 29_999) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("30000")
    }

    private fun job(
        mappings: VendorAssetMappingRepository,
        vendor: VendorNetworkFeePort,
        quotes: NetworkFeeQuoteRepository,
        jobs: JobStateRepository,
    ) = NetworkFeeQuoteCollectionJob(
        mappings = mappings,
        vendor = vendor,
        quotes = quotes,
        jobs = jobs,
        transactionRunner =
            object : TransactionRunner {
                override fun <T> run(block: () -> T): T = block()
            },
        clock = CLOCK,
    )

    private fun mapping(
        symbol: String,
        vendorAssetId: String,
    ) = VendorAssetMapping("ETHEREUM", symbol, vendorAssetId, null, "20260813100000", "SYSTEM", "9999")

    private fun estimate() =
        VendorNetworkFeeEstimate(
            low = VendorNetworkFee(gasPrice = "1.0".toBigDecimal()),
            medium = VendorNetworkFee(gasPrice = "2.0".toBigDecimal()),
            high = VendorNetworkFee(gasPrice = "3.0".toBigDecimal()),
        )

    private companion object {
        const val NOW = "20260813120000"
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-08-13T12:00:00Z"), ZoneId.of("Asia/Seoul"))
    }
}

private class RecordingMappings(
    private val values: List<VendorAssetMapping>,
) : VendorAssetMappingRepository {
    override fun find(
        network: String,
        symbol: String,
    ): VendorAssetMapping? = values.find { it.network == network && it.symbol == symbol }

    override fun findByVendorAssetId(vendorAssetId: String): VendorAssetMapping? = values.find { it.vendorAssetId == vendorAssetId }

    override fun findAll(
        network: String?,
        symbol: String?,
    ): List<VendorAssetMapping> = values

    override fun existsByNetwork(network: String): Boolean = values.any { it.network == network }

    override fun insert(mapping: VendorAssetMapping): VendorAssetMapping = error("not used")

    override fun delete(
        network: String,
        symbol: String,
    ) = error("not used")
}

private class RecordingVendor(
    private val estimates: Map<String, VendorNetworkFeeEstimate>,
    private val failureAssetId: String? = null,
) : VendorNetworkFeePort {
    override fun estimateNetworkFee(vendorAssetId: String): VendorNetworkFeeEstimate {
        if (vendorAssetId == failureAssetId) throw IllegalStateException("fee unavailable: $vendorAssetId")
        return checkNotNull(estimates[vendorAssetId])
    }
}

private class RecordingQuotes : NetworkFeeQuoteRepository {
    val saved = mutableListOf<NetworkFeeQuote>()

    override fun saveAll(quotes: List<NetworkFeeQuote>): Int {
        saved += quotes
        return quotes.size
    }

    override fun findLatestAtOrBefore(
        network: String,
        symbol: String,
        feeLevel: com.whatto.bcm.domain.vendor.VendorFeeLevel,
        requestedAt: String,
    ): NetworkFeeQuote? = error("not used")

    override fun findForSubmission(externalTransactionId: String): NetworkFeeQuote? = error("not used")

    override fun findForBoost(
        originalTransactionId: String,
        attemptSequence: Int,
    ): NetworkFeeQuote? = error("not used")
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

    override fun find(jobName: String) = null
}
