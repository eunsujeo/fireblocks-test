package com.whatto.bcm.app.application.asset

import com.whatto.bcm.app.application.account.DepositAddressQueryService
import com.whatto.bcm.domain.asset.VendorAssetCatalogCacheRepository
import com.whatto.bcm.domain.asset.VendorAssetCatalogCacheState
import com.whatto.bcm.domain.asset.VendorAssetCatalogCandidate
import com.whatto.bcm.domain.asset.VendorAssetCatalogSearchResult
import com.whatto.bcm.domain.asset.VendorAssetCatalogSource
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.asset.VendorAssetMappingRepository
import com.whatto.bcm.domain.asset.VendorBlockchainCatalog
import com.whatto.bcm.domain.asset.VendorBlockchainCatalogRepository
import com.whatto.bcm.domain.exception.BulkAssetMappingException
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.InvalidAssetMappingException
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import com.whatto.bcm.domain.exception.VendorAssetMappingRegistrationConflictException
import com.whatto.bcm.domain.vendor.ChainAssetResolution
import com.whatto.bcm.domain.vendor.ChainAssetResolver
import com.whatto.bcm.domain.vendor.ResolvedChainAsset
import com.whatto.bcm.domain.vendor.VendorAsset
import com.whatto.bcm.domain.vendor.VendorAssetCatalogPort
import com.whatto.bcm.domain.vendor.VendorPage
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class VendorAssetMappingServiceTest {
    private val mappings = mockk<VendorAssetMappingRepository>()
    private val blockchains = mockk<VendorBlockchainCatalogRepository>()
    private val addressQueryService = mockk<DepositAddressQueryService>()
    private val assetCatalogCache = mockk<VendorAssetCatalogCacheRepository>()
    private val vendorCatalog = mockk<VendorAssetCatalogPort>()
    private val clock = Clock.fixed(Instant.parse("2026-08-06T12:00:00Z"), ZoneId.of("Asia/Seoul"))
    private val service =
        VendorAssetMappingService(
            mappings,
            blockchains,
            addressQueryService,
            assetCatalogCache,
            FireblocksChainAssetResolver(vendorCatalog),
            clock,
        )

    private val audit = AuditActor("123456", "0001")
    private val command =
        RegisterVendorAssetMappingCommand(
            network = "ETHEREUM",
            symbol = "USDC",
            fireblocksAssetId = "asset-uuid",
            contractAddress = "0xA0b8",
            employeeNo = "123456",
            branchCode = "0001",
        )

    @Test
    fun `등록 — 채택한 network의 자산을 끝까지 페이징해 주소 하나를 해소하고 실제 감사로 저장한다`() {
        every { mappings.find("ETHEREUM", "USDC") } returns null
        every { blockchains.findByNetwork("ETHEREUM") } returns blockchain()
        every { vendorCatalog.assets("ethereum-id", null, null) } returns
            VendorPage(listOf(vendorAsset("other", "0xOTHER")), "next-1")
        every { vendorCatalog.assets("ethereum-id", null, "next-1") } returns
            VendorPage(listOf(vendorAsset("asset-uuid", "0xA0B8")), null)
        val inserted = slot<VendorAssetMapping>()
        every { mappings.save(capture(inserted), any()) } answers { inserted.captured }

        val result = service.register(command)

        assertThat(result.vendorAssetId).isEqualTo("asset-uuid")
        assertThat(result.contractAddress).isEqualTo("0xA0B8")
        assertThat(result.registeredAt).isEqualTo("20260806120000")
        assertThat(result.registeredByEmployeeNo).isEqualTo("123456")
        assertThat(result.registeredByBranchCode).isEqualTo("0001")
    }

    @Test
    fun `등록 — 같은 우리 키는 set-once라 409이고 벤더를 조회하지 않는다`() {
        every { mappings.find("ETHEREUM", "USDC") } returns mapping()

        assertThatThrownBy { service.register(command) }.isInstanceOf(ConflictException::class.java)
        verify(exactly = 0) { vendorCatalog.assets(any(), any(), any()) }
        verify(exactly = 0) { mappings.save(any(), any()) }
    }

    @Test
    fun `등록 — 채택 안 한 network는 400이고 벤더와 INSERT를 호출하지 않는다`() {
        every { mappings.find("ETHEREUM", "USDC") } returns null
        every { blockchains.findByNetwork("ETHEREUM") } returns null

        assertThatThrownBy { service.register(command) }.isInstanceOf(InvalidAssetMappingException::class.java)
        verify(exactly = 0) { vendorCatalog.assets(any(), any(), any()) }
        verify(exactly = 0) { mappings.save(any(), any()) }
    }

    @Test
    fun `등록 — 틀린 컨트랙트 주소는 벤더를 호출하지만 400이고 INSERT는 없다`() {
        every { mappings.find("ETHEREUM", "USDC") } returns null
        every { blockchains.findByNetwork("ETHEREUM") } returns blockchain()
        every { vendorCatalog.assets("ethereum-id", null, null) } returns
            VendorPage(listOf(vendorAsset("asset-uuid", "0xWRONG")), null)

        assertThatThrownBy { service.register(command) }.isInstanceOf(InvalidAssetMappingException::class.java)
        verify(exactly = 1) { vendorCatalog.assets("ethereum-id", null, null) }
        verify(exactly = 0) { mappings.save(any(), any()) }
    }

    @Test
    fun `등록 — 화면에서 고른 Fireblocks asset id와 최신 자산이 다르면 주소가 같아도 거절한다`() {
        every { mappings.find("ETHEREUM", "USDC") } returns null
        every { blockchains.findByNetwork("ETHEREUM") } returns blockchain()
        every { vendorCatalog.assets("ethereum-id", null, null) } returns
            VendorPage(listOf(vendorAsset("different-asset", "0xA0B8")), null)

        assertThatThrownBy { service.register(command) }.isInstanceOf(InvalidAssetMappingException::class.java)
        verify(exactly = 0) { mappings.save(any(), any()) }
    }

    @Test
    fun `등록 — 같은 주소가 두 자산에 잡히면 사람이 판단하도록 409다`() {
        every { mappings.find("ETHEREUM", "USDC") } returns null
        every { blockchains.findByNetwork("ETHEREUM") } returns blockchain()
        every { vendorCatalog.assets("ethereum-id", null, null) } returns
            VendorPage(listOf(vendorAsset("asset-uuid", "0xA0B8"), vendorAsset("asset-uuid", "0xa0b8")), null)

        assertThatThrownBy { service.register(command) }.isInstanceOf(ConflictException::class.java)
        verify(exactly = 0) { mappings.save(any(), any()) }
    }

    @Test
    fun `등록 — 다른 체인의 자산이 섞여 오면 주소가 같아도 후보에서 제외한다`() {
        every { mappings.find("ETHEREUM", "USDC") } returns null
        every { blockchains.findByNetwork("ETHEREUM") } returns blockchain()
        every { vendorCatalog.assets("ethereum-id", null, null) } returns
            VendorPage(listOf(vendorAsset("wrong-chain", "0xA0B8").copy(blockchainId = "base-id")), null)

        assertThatThrownBy { service.register(command) }.isInstanceOf(InvalidAssetMappingException::class.java)
        verify(exactly = 0) { mappings.save(any(), any()) }
    }

    @Test
    fun `등록 — contractAddress null은 그 체인의 네이티브 자산 하나로 해소한다`() {
        val nativeCommand = command.copy(symbol = "ETH", fireblocksAssetId = "native-id", contractAddress = null)
        every { mappings.find("ETHEREUM", "ETH") } returns null
        every { blockchains.findByNetwork("ETHEREUM") } returns blockchain()
        every { vendorCatalog.assets("ethereum-id", null, null) } returns
            VendorPage(
                listOf(
                    vendorAsset("native-id", null).copy(displaySymbol = "ETH", assetClass = "NATIVE"),
                    vendorAsset("erc20-id", "0xTOKEN"),
                ),
                null,
            )
        val inserted = slot<VendorAssetMapping>()
        every { mappings.save(capture(inserted), any()) } answers { inserted.captured }

        val result = service.register(nativeCommand)

        assertThat(result.vendorAssetId).isEqualTo("native-id")
        assertThat(result.contractAddress).isNull()
    }

    @Test
    fun `등록 — Fireblocks 원천에서 후보 asset id가 없으면 카탈로그를 읽기 전에 400이다`() {
        every { mappings.find("ETHEREUM", "USDC") } returns null
        every { blockchains.findByNetwork("ETHEREUM") } returns blockchain()
        every { vendorCatalog.assets("ethereum-id", null, null) } returns VendorPage(emptyList(), null)

        assertThatThrownBy { service.register(command.copy(fireblocksAssetId = null)) }
            .isInstanceOfSatisfying(
                InvalidAssetMappingException::class.java,
            ) { assertThat(it.reason).isEqualTo("fireblocksAssetIdRequired") }
        verify(exactly = 0) { mappings.save(any(), any()) }
    }

    @Test
    fun `등록 — 관문이 해소한 벤더 식별자·컨트랙트 주소를 그대로 저장하고 요청 안에서 같은 자산으로 해소되면 일괄 등록을 거절한다`() {
        val resolver =
            ChainAssetResolver { blockchain, locators ->
                locators.map {
                    ChainAssetResolution.Resolved(
                        ResolvedChainAsset("${blockchain.candidateId}:Erc20:${it.contractAddress}", it.contractAddress, null),
                    )
                }
            }
        val neutral = VendorAssetMappingService(mappings, blockchains, addressQueryService, assetCatalogCache, resolver, clock)
        val dfnsCommand = command.copy(fireblocksAssetId = null)
        every { mappings.find(any(), any()) } returns null
        every { blockchains.findByNetwork("ETHEREUM") } returns blockchain()
        every { mappings.save(any(), "UNSPECIFIED") } answers { firstArg() }

        val saved = neutral.register(dfnsCommand)

        assertThat(saved.vendorAssetId).isEqualTo("ethereum-id:Erc20:0xA0b8")
        assertThat(saved.contractAddress).isEqualTo("0xA0b8")
        verify(exactly = 0) { vendorCatalog.assets(any(), any(), any()) }

        val duplicate =
            assertThrows<BulkAssetMappingException> { neutral.registerAll(listOf(dfnsCommand, dfnsCommand.copy(symbol = "USDC2"))) }
        assertThat(duplicate.index).isEqualTo(0)
        assertThat(duplicate.reason).isEqualTo("duplicateVendorAsset")
        verify(exactly = 0) { mappings.saveAll(any(), any()) }
    }

    @Test
    fun `일괄 등록 — 네트워크별 카탈로그를 한 번만 읽고 전체 검증 후 한 번에 저장한다`() {
        val baseCommand =
            command.copy(
                network = "BASE",
                fireblocksAssetId = "base-usdc",
                contractAddress = "0xBASE",
                requestId = "bulk-request",
            )
        every { mappings.find("ETHEREUM", "USDC") } returns null
        every { mappings.find("BASE", "USDC") } returns null
        every { blockchains.findByNetwork("ETHEREUM") } returns blockchain()
        every { blockchains.findByNetwork("BASE") } returns
            blockchain().copy(candidateId = "base-id", network = "BASE", chainId = 8453, displayName = "Base")
        every { vendorCatalog.assets("ethereum-id", null, null) } returns
            VendorPage(listOf(vendorAsset("asset-uuid", "0xA0B8")), null)
        every { vendorCatalog.assets("base-id", null, null) } returns
            VendorPage(listOf(vendorAsset("base-usdc", "0xBASE").copy(blockchainId = "base-id")), null)
        every { mappings.saveAll(any(), "bulk-request") } answers { firstArg() }

        val result = service.registerAll(listOf(command.copy(requestId = "bulk-request"), baseCommand))

        assertThat(result).extracting<String> { it.network }.containsExactly("ETHEREUM", "BASE")
        verify(exactly = 1) { mappings.saveAll(match { it.size == 2 }, "bulk-request") }
        verify(exactly = 1) { vendorCatalog.assets("ethereum-id", null, null) }
        verify(exactly = 1) { vendorCatalog.assets("base-id", null, null) }
    }

    @Test
    fun `일괄 등록 — 후보 하나라도 유효하지 않으면 아무것도 저장하지 않는다`() {
        val invalid = command.copy(network = "BASE", fireblocksAssetId = "missing", contractAddress = "0xMISSING")
        every { mappings.find("ETHEREUM", "USDC") } returns null
        every { mappings.find("BASE", "USDC") } returns null
        every { blockchains.findByNetwork("ETHEREUM") } returns blockchain()
        every { blockchains.findByNetwork("BASE") } returns
            blockchain().copy(candidateId = "base-id", network = "BASE", chainId = 8453, displayName = "Base")
        every { vendorCatalog.assets("ethereum-id", null, null) } returns
            VendorPage(listOf(vendorAsset("asset-uuid", "0xA0B8")), null)
        every { vendorCatalog.assets("base-id", null, null) } returns VendorPage(emptyList(), null)

        val failure = assertThrows<BulkAssetMappingException> { service.registerAll(listOf(command, invalid)) }
        assertThat(failure.index).isEqualTo(1)
        assertThat(failure.network).isEqualTo("BASE")
        assertThat(failure.symbol).isEqualTo("USDC")
        assertThat(failure.reason).isEqualTo("assetNotFound")

        verify(exactly = 0) { mappings.saveAll(any(), any()) }
    }

    @Test
    fun `일괄 등록 — 요청 안의 중복 키는 벤더 조회 전에 거절한다`() {
        val failure =
            assertThrows<BulkAssetMappingException> {
                service.registerAll(listOf(command, command.copy(fireblocksAssetId = "other")))
            }
        assertThat(failure.index).isZero()
        assertThat(failure.reason).isEqualTo("duplicateAssetMapping")

        verify(exactly = 0) { vendorCatalog.assets(any(), any(), any()) }
        verify(exactly = 0) { mappings.saveAll(any(), any()) }
    }

    @Test
    fun `일괄 등록 — symbol prefix가 겹쳐도 영속 충돌 항목을 정확히 표시한다`() {
        val shortSymbol = command.copy(symbol = "ABC", fireblocksAssetId = "asset-abc", contractAddress = "0xABC")
        val longSymbol = command.copy(symbol = "ABCD", fireblocksAssetId = "asset-abcd", contractAddress = "0xABCD")
        every { mappings.find("ETHEREUM", "ABC") } returns null
        every { mappings.find("ETHEREUM", "ABCD") } returns null
        every { blockchains.findByNetwork("ETHEREUM") } returns blockchain()
        every { vendorCatalog.assets("ethereum-id", null, null) } returns
            VendorPage(
                listOf(
                    vendorAsset("asset-abc", "0xABC"),
                    vendorAsset("asset-abcd", "0xABCD"),
                ),
                null,
            )
        every { mappings.saveAll(any(), any()) } throws
            VendorAssetMappingRegistrationConflictException("ETHEREUM", "ABCD")

        val failure = assertThrows<BulkAssetMappingException> { service.registerAll(listOf(shortSymbol, longSymbol)) }

        assertThat(failure.index).isEqualTo(1)
        assertThat(failure.network).isEqualTo("ETHEREUM")
        assertThat(failure.symbol).isEqualTo("ABCD")
        assertThat(failure.reason).isEqualTo("concurrentConflict")
    }

    @Test
    fun `후보 조회 — Fireblocks를 호출하지 않고 캐시의 검색 결과와 원천 상태를 반환한다`() {
        val cached =
            VendorAssetCatalogSearchResult(
                items =
                    listOf(
                        VendorAssetCatalogCandidate(
                            network = "ETHEREUM",
                            networkDisplayName = "Ethereum",
                            chainId = 1,
                            testnet = false,
                            symbol = "USDC",
                            displayName = "USD Coin",
                            fireblocksAssetId = "asset-uuid",
                            assetClass = "FT",
                            decimals = 6,
                            contractAddress = "0xA0B8",
                            catalogSyncedAt = "20260806110000",
                            registrationAllowed = true,
                            registrationDisabledReason = null,
                        ),
                    ),
                sources =
                    listOf(
                        VendorAssetCatalogSource(
                            "ETHEREUM",
                            "Ethereum",
                            VendorAssetCatalogCacheState.READY,
                            "20260806110000",
                        ),
                    ),
            )
        every { assetCatalogCache.search("usdc", "ETHEREUM", "20260804120000", 50) } returns cached

        val result = service.assetCandidates("usdc", "ETHEREUM")

        assertThat(result).isEqualTo(cached)
        verify(exactly = 0) { vendorCatalog.assets(any(), any(), any()) }
    }

    @Test
    fun `삭제 — 주소가 있으면 409, 없으면 삭제, 매핑이 없으면 404다`() {
        every { mappings.find("ETHEREUM", "USDC") } returns mapping()
        every { addressQueryService.existsByAsset("ETHEREUM", "USDC") } returns true
        assertThatThrownBy { service.delete("ETHEREUM", "USDC", audit) }.isInstanceOf(ConflictException::class.java)
        verify(exactly = 0) { mappings.deactivate(any(), any(), any(), any(), any(), any()) }

        every { addressQueryService.existsByAsset("ETHEREUM", "USDC") } returns false
        every { mappings.deactivate("ETHEREUM", "USDC", "123456", "0001", any(), any()) } returns Unit
        service.delete("ETHEREUM", "USDC", audit)
        verify(exactly = 1) { mappings.deactivate("ETHEREUM", "USDC", "123456", "0001", any(), any()) }

        every { mappings.find("BASE", "USDC") } returns null
        assertThatThrownBy { service.delete("BASE", "USDC", audit) }.isInstanceOf(ResourceNotFoundException::class.java)
    }

    @Test
    fun `채택 해제 — 매핑이 남아 있으면 409이고 카탈로그를 바꾸지 않는다`() {
        every { blockchains.findByNetwork("ETHEREUM") } returns blockchain()
        every { mappings.existsByNetwork("ETHEREUM") } returns true

        assertThatThrownBy { service.releaseNetwork("ETHEREUM", audit) }.isInstanceOf(ConflictException::class.java)
        verify(exactly = 0) { blockchains.release(any(), any(), any()) }
    }

    @Test
    fun `채택 — 같은 후보와 network는 멱등이고 다른 조합은 409다`() {
        every { blockchains.findByCandidateId("ethereum-id") } returns blockchain()

        val result = service.adoptNetwork(AdoptNetworkCommand("ETHEREUM", "ethereum-id", "123456", "0001"))

        assertThat(result.network).isEqualTo("ETHEREUM")
        verify(exactly = 0) { blockchains.adopt(any(), any(), any(), any()) }

        every { blockchains.findByCandidateId("ethereum-id") } returns blockchain().copy(network = "MAINNET")
        assertThatThrownBy {
            service.adoptNetwork(AdoptNetworkCommand("ETHEREUM", "ethereum-id", "123456", "0001"))
        }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `채택 — 없는 후보는 400이다`() {
        every { blockchains.findByCandidateId("missing") } returns null

        assertThatThrownBy {
            service.adoptNetwork(AdoptNetworkCommand("ETHEREUM", "missing", "123456", "0001"))
        }.isInstanceOf(InvalidAssetMappingException::class.java)
    }

    private fun blockchain() = VendorBlockchainCatalog("ethereum-id", "ETHEREUM", 1, "Ethereum", false, false, "20260806110000")

    private fun mapping() = VendorAssetMapping("ETHEREUM", "USDC", "asset-uuid", "0xA0B8", "20260806120000", "123456", "0001")

    private fun vendorAsset(
        id: String,
        contractAddress: String?,
    ) = VendorAsset(id, "ethereum-id", "USD Coin", "USDC", 6, "FT", contractAddress)
}
