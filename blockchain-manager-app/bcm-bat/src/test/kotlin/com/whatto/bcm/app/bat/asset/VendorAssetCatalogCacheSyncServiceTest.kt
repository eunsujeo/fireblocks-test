package com.whatto.bcm.app.bat.asset

import com.whatto.bcm.domain.asset.VendorAssetCatalogCacheRepository
import com.whatto.bcm.domain.asset.VendorAssetCatalogSearchResult
import com.whatto.bcm.domain.asset.VendorAssetCatalogSnapshot
import com.whatto.bcm.domain.asset.VendorBlockchainCatalog
import com.whatto.bcm.domain.asset.VendorBlockchainCatalogRepository
import com.whatto.bcm.domain.exception.VendorAssetCatalogSyncException
import com.whatto.bcm.domain.job.JobState
import com.whatto.bcm.domain.job.JobStateRepository
import com.whatto.bcm.domain.vendor.VendorAsset
import com.whatto.bcm.domain.vendor.VendorAssetCatalogPort
import com.whatto.bcm.domain.vendor.VendorAssetCatalogSyncAlertPort
import com.whatto.bcm.domain.vendor.VendorBlockchain
import com.whatto.bcm.domain.vendor.VendorPage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class VendorAssetCatalogCacheSyncServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-08-24T01:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `채택 네트워크마다 모든 페이지를 받은 뒤 snapshot을 교체하고 성공 heartbeat를 남긴다`() {
        val vendor =
            FakeVendorAssetCatalog(
                mapOf(
                    PageKey("ethereum-id", null) to VendorPage(listOf(asset("usdc", "ethereum-id", "USDC")), "next"),
                    PageKey("ethereum-id", "next") to VendorPage(listOf(asset("usdt", "ethereum-id", "USDT")), null),
                    PageKey("base-id", null) to VendorPage(listOf(asset("krwk", "base-id", "KRWK")), null),
                ),
            )
        val cache = RecordingAssetCatalogCache()
        val jobs = RecordingJobStates()

        service(vendor, cache, jobs).sync()

        assertThat(cache.snapshots.map { it.vendorBlockchainId }).containsExactly("base-id", "ethereum-id")
        assertThat(
            cache.snapshots
                .single { it.vendorBlockchainId == "ethereum-id" }
                .assets
                .map { it.vendorAssetId },
        ).containsExactly("usdc", "usdt")
        assertThat(cache.snapshots).allMatch { it.syncedAt == "20260824010000" }
        assertThat(jobs.started).containsExactly("VENDOR_ASSET_CATALOG_SYNC" to "20260824010000")
        assertThat(jobs.succeeded).containsExactly("VENDOR_ASSET_CATALOG_SYNC" to "20260824010000")
    }

    @Test
    fun `한 네트워크 조회가 실패해도 나머지를 반영하고 작업 전체는 실패로 남긴다`() {
        val vendor =
            FakeVendorAssetCatalog(
                pages = mapOf(PageKey("base-id", null) to VendorPage(listOf(asset("krwk", "base-id", "KRWK")), null)),
                failures = setOf(PageKey("ethereum-id", null)),
            )
        val cache = RecordingAssetCatalogCache()
        val jobs = RecordingJobStates()
        val alerts = RecordingAssetCatalogAlerts()

        assertThatThrownBy { service(vendor, cache, jobs, alerts).sync() }
            .isInstanceOf(VendorAssetCatalogSyncException::class.java)

        assertThat(cache.snapshots.map { it.vendorBlockchainId }).containsExactly("base-id")
        assertThat(alerts.networks).containsExactly("ETHEREUM")
        assertThat(jobs.started).hasSize(1)
        assertThat(jobs.succeeded).isEmpty()
    }

    @Test
    fun `응답 자산의 blockchainId가 요청과 다르면 그 네트워크 snapshot을 저장하지 않는다`() {
        val vendor =
            FakeVendorAssetCatalog(
                mapOf(
                    PageKey("base-id", null) to VendorPage(listOf(asset("wrong", "ethereum-id", "USDC")), null),
                    PageKey("ethereum-id", null) to VendorPage(emptyList(), null),
                ),
            )
        val cache = RecordingAssetCatalogCache()
        val alerts = RecordingAssetCatalogAlerts()

        assertThatThrownBy { service(vendor, cache, RecordingJobStates(), alerts).sync() }
            .isInstanceOf(VendorAssetCatalogSyncException::class.java)

        assertThat(cache.snapshots.map { it.vendorBlockchainId }).containsExactly("ethereum-id")
        assertThat(alerts.networks).containsExactly("BASE")
    }

    private fun service(
        vendor: VendorAssetCatalogPort,
        cache: VendorAssetCatalogCacheRepository,
        jobs: JobStateRepository,
        alerts: VendorAssetCatalogSyncAlertPort = RecordingAssetCatalogAlerts(),
    ) = VendorAssetCatalogCacheSyncService(
        vendor,
        FakeBlockchainRepository(),
        cache,
        jobs,
        alerts,
        clock,
    )

    private fun asset(
        id: String,
        blockchainId: String,
        symbol: String,
    ) = VendorAsset(id, blockchainId, "$symbol asset", symbol, 6, "FT", "0x$id")
}

private data class PageKey(
    val blockchainId: String,
    val cursor: String?,
)

private class FakeVendorAssetCatalog(
    private val pages: Map<PageKey, VendorPage<VendorAsset>>,
    private val failures: Set<PageKey> = emptySet(),
) : VendorAssetCatalogPort {
    override fun blockchains(pageCursor: String?): VendorPage<VendorBlockchain> = error("not used")

    override fun assets(
        blockchainId: String,
        symbol: String?,
        pageCursor: String?,
    ): VendorPage<VendorAsset> {
        val key = PageKey(blockchainId, pageCursor)
        if (key in failures) error("vendor failed")
        return checkNotNull(pages[key])
    }
}

private class FakeBlockchainRepository : VendorBlockchainCatalogRepository {
    private val rows =
        linkedMapOf(
            "base-id" to VendorBlockchainCatalog("base-id", "BASE", 8453, "Base", false, false, "20260824000000"),
            "ethereum-id" to
                VendorBlockchainCatalog("ethereum-id", "ETHEREUM", 1, "Ethereum", false, false, "20260824000000"),
            "unused-id" to VendorBlockchainCatalog("unused-id", null, 999, "Unused", true, false, "20260824000000"),
        )

    override fun findAll(
        query: String?,
        chainId: Long?,
        adopted: Boolean?,
        testnet: Boolean?,
    ): List<VendorBlockchainCatalog> = rows.values.filter { adopted == null || (it.network != null) == adopted }

    override fun findByCandidateId(candidateId: String) = rows[candidateId]

    override fun findByNetwork(network: String) = rows.values.firstOrNull { it.network == network }

    override fun insert(catalog: VendorBlockchainCatalog) = error("not used")

    override fun updateSnapshot(catalog: VendorBlockchainCatalog) = error("not used")

    override fun adopt(
        candidateId: String,
        network: String,
        employeeNo: String,
        branchCode: String,
    ) = error("not used")

    override fun release(
        network: String,
        employeeNo: String,
        branchCode: String,
    ) = error("not used")
}

private class RecordingAssetCatalogCache : VendorAssetCatalogCacheRepository {
    val snapshots = mutableListOf<VendorAssetCatalogSnapshot>()

    override fun replaceSnapshot(snapshot: VendorAssetCatalogSnapshot) {
        snapshots += snapshot
    }

    override fun search(
        query: String,
        network: String?,
        staleBefore: String,
        limit: Int,
    ): VendorAssetCatalogSearchResult = error("not used")
}

private class RecordingJobStates : JobStateRepository {
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

private class RecordingAssetCatalogAlerts : VendorAssetCatalogSyncAlertPort {
    val networks = mutableListOf<String>()

    override fun syncFailed(
        network: String,
        failureType: String,
    ) {
        networks += network
    }
}
