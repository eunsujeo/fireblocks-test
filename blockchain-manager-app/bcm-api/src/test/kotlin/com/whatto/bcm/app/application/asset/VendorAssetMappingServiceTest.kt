package com.whatto.bcm.app.application.asset

import com.whatto.bcm.app.application.account.DepositAddressQueryService
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.asset.VendorAssetMappingRepository
import com.whatto.bcm.domain.asset.VendorBlockchainCatalog
import com.whatto.bcm.domain.asset.VendorBlockchainCatalogRepository
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.InvalidAssetMappingException
import com.whatto.bcm.domain.exception.ResourceNotFoundException
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class VendorAssetMappingServiceTest {
    private val mappings = mockk<VendorAssetMappingRepository>()
    private val blockchains = mockk<VendorBlockchainCatalogRepository>()
    private val addressQueryService = mockk<DepositAddressQueryService>()
    private val vendorCatalog = mockk<VendorAssetCatalogPort>()
    private val clock = Clock.fixed(Instant.parse("2026-08-06T12:00:00Z"), ZoneId.of("Asia/Seoul"))
    private val service = VendorAssetMappingService(mappings, blockchains, addressQueryService, vendorCatalog, clock)

    private val audit = AuditActor("123456", "0001")
    private val command = RegisterVendorAssetMappingCommand("ETHEREUM", "USDC", "0xA0b8", "123456", "0001")

    @Test
    fun `등록 — 채택한 network의 자산을 끝까지 페이징해 주소 하나를 해소하고 실제 감사로 저장한다`() {
        every { mappings.find("ETHEREUM", "USDC") } returns null
        every { blockchains.findByNetwork("ETHEREUM") } returns blockchain()
        every { vendorCatalog.assets("ethereum-id", null, null) } returns
            VendorPage(listOf(vendorAsset("other", "0xOTHER")), "next-1")
        every { vendorCatalog.assets("ethereum-id", null, "next-1") } returns
            VendorPage(listOf(vendorAsset("asset-uuid", "0xA0B8")), null)
        val inserted = slot<VendorAssetMapping>()
        every { mappings.insert(capture(inserted)) } answers { inserted.captured }

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
        verify(exactly = 0) { mappings.insert(any()) }
    }

    @Test
    fun `등록 — 채택 안 한 network는 400이고 벤더와 INSERT를 호출하지 않는다`() {
        every { mappings.find("ETHEREUM", "USDC") } returns null
        every { blockchains.findByNetwork("ETHEREUM") } returns null

        assertThatThrownBy { service.register(command) }.isInstanceOf(InvalidAssetMappingException::class.java)
        verify(exactly = 0) { vendorCatalog.assets(any(), any(), any()) }
        verify(exactly = 0) { mappings.insert(any()) }
    }

    @Test
    fun `등록 — 틀린 컨트랙트 주소는 벤더를 호출하지만 400이고 INSERT는 없다`() {
        every { mappings.find("ETHEREUM", "USDC") } returns null
        every { blockchains.findByNetwork("ETHEREUM") } returns blockchain()
        every { vendorCatalog.assets("ethereum-id", null, null) } returns
            VendorPage(listOf(vendorAsset("asset-uuid", "0xWRONG")), null)

        assertThatThrownBy { service.register(command) }.isInstanceOf(InvalidAssetMappingException::class.java)
        verify(exactly = 1) { vendorCatalog.assets("ethereum-id", null, null) }
        verify(exactly = 0) { mappings.insert(any()) }
    }

    @Test
    fun `등록 — 같은 주소가 두 자산에 잡히면 사람이 판단하도록 409다`() {
        every { mappings.find("ETHEREUM", "USDC") } returns null
        every { blockchains.findByNetwork("ETHEREUM") } returns blockchain()
        every { vendorCatalog.assets("ethereum-id", null, null) } returns
            VendorPage(listOf(vendorAsset("one", "0xA0B8"), vendorAsset("two", "0xa0b8")), null)

        assertThatThrownBy { service.register(command) }.isInstanceOf(ConflictException::class.java)
        verify(exactly = 0) { mappings.insert(any()) }
    }

    @Test
    fun `등록 — 다른 체인의 자산이 섞여 오면 주소가 같아도 후보에서 제외한다`() {
        every { mappings.find("ETHEREUM", "USDC") } returns null
        every { blockchains.findByNetwork("ETHEREUM") } returns blockchain()
        every { vendorCatalog.assets("ethereum-id", null, null) } returns
            VendorPage(listOf(vendorAsset("wrong-chain", "0xA0B8").copy(blockchainId = "base-id")), null)

        assertThatThrownBy { service.register(command) }.isInstanceOf(InvalidAssetMappingException::class.java)
        verify(exactly = 0) { mappings.insert(any()) }
    }

    @Test
    fun `등록 — contractAddress null은 그 체인의 네이티브 자산 하나로 해소한다`() {
        val nativeCommand = command.copy(symbol = "ETH", contractAddress = null)
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
        every { mappings.insert(capture(inserted)) } answers { inserted.captured }

        val result = service.register(nativeCommand)

        assertThat(result.vendorAssetId).isEqualTo("native-id")
        assertThat(result.contractAddress).isNull()
    }

    @Test
    fun `후보 조회 — network를 내부 후보로 해소하고 벤더 id 없는 중립 필드만 만든다`() {
        every { blockchains.findByNetwork("ETHEREUM") } returns blockchain()
        every { vendorCatalog.assets("ethereum-id", "USDC", null) } returns
            VendorPage(listOf(vendorAsset("secret-id", "0xA0B8")), null)

        val result = service.assetCandidates("usdc", "ETHEREUM").single()

        assertThat(result.network).isEqualTo("ETHEREUM")
        assertThat(result.symbol).isEqualTo("USDC")
        assertThat(result.contractAddress).isEqualTo("0xA0B8")
        assertThat(result.native).isFalse()
    }

    @Test
    fun `삭제 — 주소가 있으면 409, 없으면 삭제, 매핑이 없으면 404다`() {
        every { mappings.find("ETHEREUM", "USDC") } returns mapping()
        every { addressQueryService.existsByAsset("ETHEREUM", "USDC") } returns true
        assertThatThrownBy { service.delete("ETHEREUM", "USDC", audit) }.isInstanceOf(ConflictException::class.java)
        verify(exactly = 0) { mappings.delete(any(), any()) }

        every { addressQueryService.existsByAsset("ETHEREUM", "USDC") } returns false
        every { mappings.delete("ETHEREUM", "USDC") } returns Unit
        service.delete("ETHEREUM", "USDC", audit)
        verify(exactly = 1) { mappings.delete("ETHEREUM", "USDC") }

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
