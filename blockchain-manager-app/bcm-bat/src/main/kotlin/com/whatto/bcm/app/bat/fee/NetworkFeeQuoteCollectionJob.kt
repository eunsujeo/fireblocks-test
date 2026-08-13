package com.whatto.bcm.app.bat.fee

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.asset.VendorAssetMappingRepository
import com.whatto.bcm.domain.fee.NetworkFeeQuote
import com.whatto.bcm.domain.fee.NetworkFeeQuoteRepository
import com.whatto.bcm.domain.job.JobStateRepository
import com.whatto.bcm.domain.vendor.VendorFeeLevel
import com.whatto.bcm.domain.vendor.VendorNetworkFee
import com.whatto.bcm.domain.vendor.VendorNetworkFeePort
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
@ConditionalOnProperty(prefix = "bcm.network-fee-quote-collection", name = ["enabled"], havingValue = "true")
class NetworkFeeQuoteCollectionJob(
    private val mappings: VendorAssetMappingRepository,
    private val vendor: VendorNetworkFeePort,
    private val quotes: NetworkFeeQuoteRepository,
    private val jobs: JobStateRepository,
    private val transactionRunner: TransactionRunner,
    private val clock: Clock,
) {
    @Scheduled(fixedDelayString = "\${bcm.network-fee-quote-collection.fixed-delay-millis:300000}")
    fun run() {
        jobs.markStarted(JOB_NAME, CoreDateTimes.now(clock))
        val observations = mappings.findAll().map { it to vendor.estimateNetworkFee(it.vendorAssetId) }
        val observedAt = CoreDateTimes.now(clock)
        val collected =
            observations.flatMap { (mapping, estimate) ->
                estimate.byLevel().map { (level, fee) -> mapping.toQuote(observedAt, level, fee) }
            }
        val inserted =
            transactionRunner.run {
                val count = quotes.saveAll(collected)
                jobs.markSucceeded(JOB_NAME, observedAt)
                count
            }
        logger.info("네트워크 수수료 견적 수집 완료 assets={} quotes={} inserted={}", observations.size, collected.size, inserted)
    }

    private fun VendorAssetMapping.toQuote(
        observedAt: String,
        level: VendorFeeLevel,
        fee: VendorNetworkFee,
    ) = NetworkFeeQuote(
        network = network,
        symbol = symbol,
        observedAt = observedAt,
        feeLevel = level,
        vendorAssetId = vendorAssetId,
        feePerByte = fee.feePerByte,
        gasPrice = fee.gasPrice,
        networkFee = fee.networkFee,
        baseFee = fee.baseFee,
        priorityFee = fee.priorityFee,
    )

    internal companion object {
        const val JOB_NAME = "network-fee-quote-collection"
        val logger = LoggerFactory.getLogger(NetworkFeeQuoteCollectionJob::class.java)
    }
}

@ConfigurationProperties("bcm.network-fee-quote-collection")
data class NetworkFeeQuoteCollectionProperties(
    val enabled: Boolean = false,
    val fixedDelayMillis: Long = 300_000,
) {
    init {
        require(fixedDelayMillis >= MINIMUM_DELAY_MILLIS) {
            "fixedDelayMillis must be at least $MINIMUM_DELAY_MILLIS"
        }
    }

    private companion object {
        const val MINIMUM_DELAY_MILLIS = 30_000
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NetworkFeeQuoteCollectionProperties::class)
class NetworkFeeQuoteCollectionConfig
