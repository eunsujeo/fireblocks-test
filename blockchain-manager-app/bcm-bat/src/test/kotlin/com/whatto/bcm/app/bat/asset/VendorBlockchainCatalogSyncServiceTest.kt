package com.whatto.bcm.app.bat.asset

import com.whatto.bcm.domain.asset.VendorBlockchainCatalog
import com.whatto.bcm.domain.asset.VendorBlockchainCatalogRepository
import com.whatto.bcm.domain.vendor.VendorAsset
import com.whatto.bcm.domain.vendor.VendorAssetCatalogPort
import com.whatto.bcm.domain.vendor.VendorBlockchain
import com.whatto.bcm.domain.vendor.VendorBlockchainCatalogAlertPort
import com.whatto.bcm.domain.vendor.VendorBlockchainOnchain
import com.whatto.bcm.domain.vendor.VendorPage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class VendorBlockchainCatalogSyncServiceTest {
    private val clock = Clock.fixed(Instant.parse("2026-08-06T03:00:00Z"), ZoneId.of("Asia/Seoul"))

    @Test
    fun `동기화 — 새 체인은 미채택으로 추가하고 기존·사라진 체인은 보존한다`() {
        val repository = FakeCatalogRepository()
        repository.rows["ethereum-id"] = catalog("ethereum-id", "ETHEREUM", 1, "Old Ethereum")
        repository.rows["disappeared-id"] = catalog("disappeared-id", "OLD", 10, "Disappeared")
        val vendor =
            FakeVendorCatalog(
                pages =
                    mapOf(
                        null to VendorPage(listOf(blockchain("ethereum-id", 1, "Ethereum")), "next"),
                        "next" to VendorPage(listOf(blockchain("base-id", 8453, "Base")), null),
                    ),
            )
        val alerts = RecordingAlertPort()

        VendorBlockchainCatalogSyncService(vendor, repository, alerts, clock).sync()

        assertThat(repository.rows["ethereum-id"]?.displayName).isEqualTo("Ethereum")
        assertThat(repository.rows["ethereum-id"]?.network).isEqualTo("ETHEREUM")
        assertThat(repository.rows["base-id"]?.network).isNull()
        assertThat(repository.rows["disappeared-id"]?.displayName).isEqualTo("Disappeared")
        assertThat(alerts.changes).isEmpty()
    }

    @Test
    fun `동기화 — chainId 변경은 행을 갱신하지 않고 경보한다`() {
        val repository = FakeCatalogRepository()
        repository.rows["ethereum-id"] = catalog("ethereum-id", "ETHEREUM", 1, "Ethereum")
        val vendor =
            FakeVendorCatalog(
                pages = mapOf(null to VendorPage(listOf(blockchain("ethereum-id", 999, "Wrong Ethereum")), null)),
            )
        val alerts = RecordingAlertPort()

        VendorBlockchainCatalogSyncService(vendor, repository, alerts, clock).sync()

        assertThat(repository.rows["ethereum-id"]?.chainId).isEqualTo(1)
        assertThat(repository.rows["ethereum-id"]?.displayName).isEqualTo("Ethereum")
        assertThat(alerts.changes).containsExactly(Triple("ethereum-id", 1, 999))
    }

    @Test
    fun `동기화 — 아직 모르는 chainId는 최초 관측값으로 한 번만 채운다`() {
        val repository = FakeCatalogRepository()
        repository.rows["ethereum-id"] = catalog("ethereum-id", "ETHEREUM", null, "Ethereum")
        val vendor =
            FakeVendorCatalog(
                pages = mapOf(null to VendorPage(listOf(blockchain("ethereum-id", 1, "Ethereum")), null)),
            )
        val alerts = RecordingAlertPort()

        VendorBlockchainCatalogSyncService(vendor, repository, alerts, clock).sync()

        assertThat(repository.rows["ethereum-id"]?.chainId).isEqualTo(1)
        assertThat(alerts.changes).isEmpty()
    }

    private fun catalog(
        id: String,
        network: String?,
        chainId: Long?,
        name: String,
    ) = VendorBlockchainCatalog(id, network, chainId, name, false, false, "20260805000000")

    private fun blockchain(
        id: String,
        chainId: Long,
        name: String,
    ) = VendorBlockchain(id, name, false, VendorBlockchainOnchain("EVM", chainId.toString(), false, null))
}

private class FakeVendorCatalog(
    private val pages: Map<String?, VendorPage<VendorBlockchain>>,
) : VendorAssetCatalogPort {
    override fun blockchains(pageCursor: String?): VendorPage<VendorBlockchain> = checkNotNull(pages[pageCursor])

    override fun assets(
        blockchainId: String,
        symbol: String?,
        pageCursor: String?,
    ): VendorPage<VendorAsset> = error("not used")
}

private class FakeCatalogRepository : VendorBlockchainCatalogRepository {
    val rows = mutableMapOf<String, VendorBlockchainCatalog>()

    override fun findAll(
        query: String?,
        chainId: Long?,
        adopted: Boolean?,
        testnet: Boolean?,
    ) = rows.values.toList()

    override fun findByCandidateId(candidateId: String) = rows[candidateId]

    override fun findByNetwork(network: String) = rows.values.firstOrNull { it.network == network }

    override fun insert(catalog: VendorBlockchainCatalog): VendorBlockchainCatalog = catalog.also { rows[it.candidateId] = it }

    override fun updateSnapshot(catalog: VendorBlockchainCatalog): VendorBlockchainCatalog =
        catalog
            .copy(chainId = rows[catalog.candidateId]?.chainId ?: catalog.chainId)
            .also { rows[it.candidateId] = it }

    override fun adopt(
        candidateId: String,
        network: String,
        employeeNo: String,
        branchCode: String,
    ): VendorBlockchainCatalog = checkNotNull(rows[candidateId]).copy(network = network).also { rows[candidateId] = it }

    override fun release(
        network: String,
        employeeNo: String,
        branchCode: String,
    ): Boolean {
        val row = checkNotNull(findByNetwork(network))
        rows[row.candidateId] = row.copy(network = null)
        return true
    }
}

private class RecordingAlertPort : VendorBlockchainCatalogAlertPort {
    val changes = mutableListOf<Triple<String, Long?, Long?>>()

    override fun chainIdChanged(
        candidateId: String,
        storedChainId: Long?,
        observedChainId: Long?,
    ) {
        changes += Triple(candidateId, storedChainId, observedChainId)
    }
}
