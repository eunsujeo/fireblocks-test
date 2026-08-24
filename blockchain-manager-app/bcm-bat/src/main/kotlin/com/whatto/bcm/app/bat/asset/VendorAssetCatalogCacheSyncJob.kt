package com.whatto.bcm.app.bat.asset

import com.whatto.bcm.domain.asset.VendorAssetCatalogCacheRepository
import com.whatto.bcm.domain.asset.VendorAssetCatalogSnapshot
import com.whatto.bcm.domain.asset.VendorAssetCatalogSnapshotAsset
import com.whatto.bcm.domain.asset.VendorBlockchainCatalogRepository
import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.exception.VendorAssetCatalogSyncException
import com.whatto.bcm.domain.job.JobStateRepository
import com.whatto.bcm.domain.monitoring.OperationalAlert
import com.whatto.bcm.domain.monitoring.OperationalAlertChannel
import com.whatto.bcm.domain.monitoring.OperationalAlertRoute
import com.whatto.bcm.domain.vendor.VendorAsset
import com.whatto.bcm.domain.vendor.VendorAssetCatalogPort
import com.whatto.bcm.domain.vendor.VendorAssetCatalogSyncAlertPort
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.time.Clock

@Component
class VendorAssetCatalogCacheSyncJob(
    private val service: VendorAssetCatalogCacheSyncService,
) {
    @Scheduled(cron = "\${bcm.asset-catalog-sync.cron:0 15 3 * * *}", zone = "Asia/Seoul")
    fun run() {
        service.sync(VendorAssetCatalogSyncScope.ALL)
    }
}

@Component
@ConditionalOnProperty(prefix = "bcm", name = ["job"], havingValue = "asset-catalog-sync-once")
class VendorAssetCatalogCacheSyncOnceRunner(
    private val command: VendorAssetCatalogCacheSyncCommand,
    private val context: ConfigurableApplicationContext,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        command.sync(VendorAssetCatalogSyncScope.ALL)
        context.close()
    }
}

@Component
@ConditionalOnProperty(prefix = "bcm", name = ["job"], havingValue = "asset-catalog-supported-sync-once")
class VendorAssetCatalogCacheSupportedSyncOnceRunner(
    private val command: VendorAssetCatalogCacheSyncCommand,
    private val context: ConfigurableApplicationContext,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        command.sync(VendorAssetCatalogSyncScope.ADOPTED)
        context.close()
    }
}

fun interface VendorAssetCatalogCacheSyncCommand {
    fun sync(scope: VendorAssetCatalogSyncScope)
}

enum class VendorAssetCatalogSyncScope {
    ALL,
    ADOPTED,
}

@Service
class VendorAssetCatalogCacheSyncService(
    private val vendorCatalog: VendorAssetCatalogPort,
    private val blockchains: VendorBlockchainCatalogRepository,
    private val cache: VendorAssetCatalogCacheRepository,
    private val jobs: JobStateRepository,
    private val alerts: VendorAssetCatalogSyncAlertPort,
    private val clock: Clock,
) : VendorAssetCatalogCacheSyncCommand {
    fun sync() = sync(VendorAssetCatalogSyncScope.ALL)

    override fun sync(scope: VendorAssetCatalogSyncScope) {
        val syncedAt = CoreDateTimes.now(clock)
        val jobName = if (scope == VendorAssetCatalogSyncScope.ALL) JOB_NAME else SUPPORTED_JOB_NAME
        jobs.markStarted(jobName, syncedAt)
        val failures = mutableListOf<Pair<String, RuntimeException>>()
        blockchains.findAll(adopted = if (scope == VendorAssetCatalogSyncScope.ADOPTED) true else null).forEach { blockchain ->
            val source = blockchain.network ?: blockchain.candidateId
            try {
                val assets = allVendorAssets(blockchain.candidateId)
                cache.replaceSnapshot(
                    VendorAssetCatalogSnapshot(
                        vendorBlockchainId = blockchain.candidateId,
                        syncedAt = syncedAt,
                        assets = assets.map { it.toSnapshotAsset() },
                    ),
                )
            } catch (exception: RuntimeException) {
                failures += source to exception
                alerts.syncFailed(source, exception::class.simpleName ?: "RuntimeException")
            }
        }
        if (failures.isNotEmpty()) {
            throw VendorAssetCatalogSyncException(failures.map { it.first }, failures.first().second)
        }
        jobs.markSucceeded(jobName, syncedAt)
    }

    private fun allVendorAssets(blockchainId: String): List<VendorAsset> {
        val assets = mutableListOf<VendorAsset>()
        val seenCursors = mutableSetOf<String>()
        var cursor: String? = null
        do {
            val page = vendorCatalog.assets(blockchainId, symbol = null, pageCursor = cursor)
            if (page.data.any { it.blockchainId != blockchainId }) {
                throw VendorApiException("listAssetsBlockchainMismatch", null)
            }
            assets += page.data
            cursor = page.next
            if (cursor != null && !seenCursors.add(cursor)) {
                throw VendorApiException("listAssetsPagination", null)
            }
        } while (cursor != null)
        return assets
    }

    private fun VendorAsset.toSnapshotAsset() =
        VendorAssetCatalogSnapshotAsset(
            vendorAssetId = id,
            symbol = displaySymbol,
            displayName = displayName,
            assetClass = assetClass,
            decimals = decimals,
            contractAddress = contractAddress,
        )

    internal companion object {
        const val JOB_NAME = "VENDOR_ASSET_CATALOG_SYNC"
        const val SUPPORTED_JOB_NAME = "VENDOR_ASSET_CATALOG_SUPPORTED_SYNC"
    }
}

@Component
class OperationalVendorAssetCatalogSyncAlertAdapter(
    private val channel: OperationalAlertChannel,
) : VendorAssetCatalogSyncAlertPort {
    override fun syncFailed(
        network: String,
        failureType: String,
    ) {
        channel.publish(
            OperationalAlert(
                route = OperationalAlertRoute.ASSET,
                type = "asset.alert.catalog-sync-failed",
                identifiers = mapOf("network" to network),
                context = mapOf("failureType" to failureType),
            ),
        )
    }
}
