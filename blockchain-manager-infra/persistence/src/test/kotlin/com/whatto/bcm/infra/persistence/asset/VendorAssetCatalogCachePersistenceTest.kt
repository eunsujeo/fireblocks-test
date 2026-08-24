package com.whatto.bcm.infra.persistence.asset

import com.whatto.bcm.domain.asset.VendorAssetCatalogCacheState
import com.whatto.bcm.domain.asset.VendorAssetCatalogSnapshot
import com.whatto.bcm.domain.asset.VendorAssetCatalogSnapshotAsset
import com.whatto.bcm.domain.asset.VendorBlockchainCatalog
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

@DataJdbcTest
@Import(VendorBlockchainCatalogJdbcAdapter::class, VendorAssetCatalogCacheJdbcAdapter::class)
class VendorAssetCatalogCachePersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var blockchains: VendorBlockchainCatalogJdbcAdapter

    @Autowired
    lateinit var assets: VendorAssetCatalogCacheJdbcAdapter

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun setUp() {
        blockchains.insert(blockchain("ethereum-id", "ETHEREUM"))
        blockchains.insert(blockchain("base-id", "BASE"))
        blockchains.insert(blockchain("polygon-id", null))
    }

    @Test
    fun `V13은 빈 자산 snapshot도 구분하는 network 성공 시각을 만든다`() {
        val columns =
            jdbc.queryForList(
                """
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = 'bcm_vndr_ast_ctlg_m'
                """.trimIndent(),
                String::class.java,
            )
        val indexes =
            jdbc.queryForList(
                """
                SELECT indexname FROM pg_indexes
                 WHERE schemaname = 'public' AND tablename = 'bcm_vndr_ast_ctlg_m'
                """.trimIndent(),
                String::class.java,
            )

        assertThat(columns).contains(
            "vndr_ast_id",
            "vndr_blkc_id",
            "ast_smbl",
            "dspl_nm",
            "ast_clss",
            "dcml_cnt",
            "cntr_addr",
            "prst_yn",
            "sync_dttm",
            "frst_reg_empno",
            "frst_reg_brcd",
            "last_chng_empno",
            "last_chng_brcd",
        )
        assertThat(indexes).contains(
            "idx_bcm_vndr_ast_ctlg_blkc",
            "idx_bcm_vndr_ast_ctlg_symbol",
            "idx_bcm_vndr_ast_ctlg_name",
            "idx_bcm_vndr_ast_ctlg_address",
            "idx_bcm_vndr_ast_ctlg_search",
        )
        assertThat(
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='public' AND table_name='bcm_blkc_m' AND column_name='ast_sync_dttm'",
                Long::class.java,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `검색은 exact symbol을 먼저 두고 이름 prefix와 주소도 찾는다`() {
        assets.replaceSnapshot(
            snapshot(
                "ethereum-id",
                "20260824010000",
                asset("usdc-eth", "USDC", "USD Coin", "0x1111"),
                asset("usdc-old", "USDC.e", "Bridged USD Coin", "0x2222"),
                asset("tether", "USDT", "Tether USD", "0x3333"),
            ),
        )
        assets.replaceSnapshot(
            snapshot(
                "polygon-id",
                "20260824020000",
                asset("usdc-polygon", "USDC", "USD Coin", "0x9999"),
            ),
        )

        val symbolResult = assets.search("usdc", null, "20260822010000", 50)
        val nameResult = assets.search("usd coin", "ETHEREUM", "20260822010000", 50)
        val addressResult = assets.search("0x333", null, "20260822010000", 50)

        assertThat(symbolResult.items.map { it.fireblocksAssetId }).containsExactly("usdc-eth", "usdc-polygon", "usdc-old")
        assertThat(nameResult.items.map { it.symbol }).containsExactly("USDC", "USDC.e")
        assertThat(addressResult.items.map { it.symbol }).containsExactly("USDT")
        assertThat(symbolResult.items.single { it.symbol == "USDC" }.catalogSyncedAt)
            .isEqualTo("20260824010000")
        assertThat(symbolResult.items.single { it.symbol == "USDC" })
            .extracting("fireblocksAssetId", "networkDisplayName", "chainId", "testnet")
            .containsExactly("usdc-eth", "ETHEREUM", 1L, false)
        assertThat(symbolResult.items.single { it.fireblocksAssetId == "usdc-polygon" }.network).isNull()
        assertThat(symbolResult.items.single { it.fireblocksAssetId == "usdc-polygon" }.registrationAllowed).isFalse()
        assertThat(symbolResult.items.single { it.fireblocksAssetId == "usdc-polygon" }.registrationDisabledReason)
            .isEqualTo("BCM 지원 Network가 아닙니다.")
        assertThat(symbolResult.sources.map { it.vendorBlockchainId })
            .containsExactly("base-id", "ethereum-id", "polygon-id")
        assertThat(symbolResult.sources.single { it.network == "ETHEREUM" }.state)
            .isEqualTo(VendorAssetCatalogCacheState.READY)
        assertThat(symbolResult.sources.single { it.network == "BASE" }.state)
            .isEqualTo(VendorAssetCatalogCacheState.NEVER_SYNCED)
    }

    @Test
    fun `자산이 0건인 network도 성공 시각과 READY 상태를 남긴다`() {
        assets.replaceSnapshot(snapshot("polygon-id", "20260824030000"))

        val source = assets.search("usdc", null, "20260822010000", 50).sources.single { it.vendorBlockchainId == "polygon-id" }

        assertThat(source.state).isEqualTo(VendorAssetCatalogCacheState.READY)
        assertThat(source.catalogSyncedAt).isEqualTo("20260824030000")
    }

    @Test
    fun `성공 snapshot에서 사라진 자산은 보존하되 검색에서 제외하고 오래된 원천은 stale이다`() {
        assets.replaceSnapshot(
            snapshot(
                "ethereum-id",
                "20260820010000",
                asset("usdc-eth", "USDC", "USD Coin", "0x1111"),
                asset("tether", "USDT", "Tether USD", "0x3333"),
            ),
        )
        assets.replaceSnapshot(
            snapshot(
                "ethereum-id",
                "20260821010000",
                asset("usdc-eth", "USDC", "USD Coin", "0x1111"),
            ),
        )

        assertThat(assets.search("usdt", null, "20260822010000", 50).items).isEmpty()
        assertThat(
            jdbc.queryForObject(
                "SELECT prst_yn FROM bcm_vndr_ast_ctlg_m WHERE vndr_ast_id = 'tether'",
                String::class.java,
            ),
        ).isEqualTo("N")
        assertThat(
            assets
                .search("usdc", null, "20260822010000", 50)
                .sources
                .single { it.network == "ETHEREUM" }
                .state,
        ).isEqualTo(VendorAssetCatalogCacheState.STALE)
    }

    @Test
    fun `같은 vendor asset id가 다른 네트워크에서 관찰되면 snapshot 전체를 롤백한다`() {
        assets.replaceSnapshot(
            snapshot(
                "ethereum-id",
                "20260824010000",
                asset("shared-id", "USDC", "USD Coin", "0x1111"),
            ),
        )

        assertThatThrownBy {
            assets.replaceSnapshot(
                snapshot(
                    "base-id",
                    "20260824020000",
                    asset("base-only", "KRWK", "Korean Won K", "0x2222"),
                    asset("shared-id", "USDC", "USD Coin", "0x3333"),
                ),
            )
        }.isInstanceOf(ConflictException::class.java)

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bcm_vndr_ast_ctlg_m", Long::class.java)).isEqualTo(1)
        assertThat(assets.search("krwk", null, "20260822010000", 50).items).isEmpty()
    }

    private fun blockchain(
        id: String,
        network: String?,
    ) = VendorBlockchainCatalog(id, network, 1, network ?: "Polygon", false, false, "20260824000000")

    private fun snapshot(
        blockchainId: String,
        syncedAt: String,
        vararg snapshotAssets: VendorAssetCatalogSnapshotAsset,
    ) = VendorAssetCatalogSnapshot(blockchainId, syncedAt, snapshotAssets.toList())

    private fun asset(
        id: String,
        symbol: String,
        name: String,
        address: String,
    ) = VendorAssetCatalogSnapshotAsset(id, symbol, name, "FT", 6, address)
}
