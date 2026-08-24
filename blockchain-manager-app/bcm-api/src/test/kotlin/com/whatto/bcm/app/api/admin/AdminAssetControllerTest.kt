package com.whatto.bcm.app.api.admin

import com.ninjasquad.springmockk.MockkBean
import com.whatto.bcm.app.application.asset.AdoptNetworkCommand
import com.whatto.bcm.app.application.asset.AuditActor
import com.whatto.bcm.app.application.asset.VendorAssetMappingService
import com.whatto.bcm.domain.asset.VendorAssetCatalogCacheState
import com.whatto.bcm.domain.asset.VendorAssetCatalogCandidate
import com.whatto.bcm.domain.asset.VendorAssetCatalogSearchResult
import com.whatto.bcm.domain.asset.VendorAssetCatalogSource
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.asset.VendorBlockchainCatalog
import com.whatto.bcm.domain.exception.BulkAssetMappingException
import com.whatto.bcm.domain.exception.InvalidAssetMappingException
import io.mockk.every
import io.mockk.verify
import org.hamcrest.Matchers.hasKey
import org.hamcrest.Matchers.not
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(AdminAssetController::class)
class AdminAssetControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var service: VendorAssetMappingService

    private val audit = AuditActor("123456", "0001")
    private val network = VendorBlockchainCatalog("opaque-candidate", "ETHEREUM", 1, "Ethereum", false, false, "20260806120000")
    private val mapping = VendorAssetMapping("ETHEREUM", "USDC", "secret-asset-id", "0xA0B8", "20260806120000", "123456", "0001")

    @Test
    fun `목록 3개 — 자산 후보와 현재 매핑은 Fireblocks asset id와 Network 표시정보를 돌려준다`() {
        every { service.networks("eth", 1, true, false) } returns listOf(network)
        every { service.assetCandidates("USDC", "ETHEREUM") } returns candidateSearchResult()
        every { service.mappings("ETHEREUM", "USDC") } returns listOf(mapping)

        mockMvc
            .perform(
                get("/admin/networks")
                    .param("q", "eth")
                    .param("chainId", "1")
                    .param("adopted", "true")
                    .param("testnet", "false"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].candidateId").value("opaque-candidate"))
            .andExpect(jsonPath("$.data[0].code").value("ETHEREUM"))
            .andExpect(jsonPath("$.data[0]", not(hasKey<String>("legacyId"))))

        mockMvc
            .perform(get("/admin/asset-candidates").param("network", "ETHEREUM").param("q", "USDC"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items[0].network").value("ETHEREUM"))
            .andExpect(jsonPath("$.data.items[0].symbol").value("USDC"))
            .andExpect(jsonPath("$.data.items[0].networkDisplayName").value("Ethereum"))
            .andExpect(jsonPath("$.data.items[0].chainId").value(1))
            .andExpect(jsonPath("$.data.items[0].testnet").value(false))
            .andExpect(jsonPath("$.data.items[0].fireblocksAssetId").value("secret-asset-id"))
            .andExpect(jsonPath("$.data.items[0].assetClass").value("FT"))
            .andExpect(jsonPath("$.data.items[0].catalogSyncedAt").value("20260806110000"))
            .andExpect(jsonPath("$.data.items[0]", not(hasKey<String>("id"))))
            .andExpect(jsonPath("$.data.items[0]", not(hasKey<String>("blockchainId"))))
            .andExpect(jsonPath("$.data.sources[0].state").value("READY"))

        mockMvc
            .perform(get("/admin/asset-mappings").param("network", "ETHEREUM").param("symbol", "USDC"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].symbol").value("USDC"))
            .andExpect(jsonPath("$.data[0].token").doesNotExist())
            .andExpect(jsonPath("$.data[0].contractAddress").value("0xA0B8"))
            .andExpect(jsonPath("$.data[0].fireblocksAssetId").value("secret-asset-id"))
            .andExpect(jsonPath("$.data[0]", not(hasKey<String>("vendorAssetId"))))
            .andExpect(jsonPath("$.data[0]", not(hasKey<String>("registeredByEmployeeNo"))))
    }

    @Test
    fun `채택·등록·삭제 — 감사 헤더를 서비스 명령으로 넘기고 계약 상태를 돌려준다`() {
        every { service.adoptNetwork(AdoptNetworkCommand("ETHEREUM", "opaque-candidate", "123456", "0001")) } returns network
        every {
            service.register(
                match {
                    it.network == "ETHEREUM" &&
                        it.symbol == "USDC" &&
                        it.fireblocksAssetId == "secret-asset-id" &&
                        it.contractAddress == "0xA0b8" &&
                        it.employeeNo == "123456" &&
                        it.branchCode == "0001" &&
                        it.requestId.isNotBlank()
                },
            )
        } returns mapping
        every { service.releaseNetwork("ETHEREUM", audit) } returns Unit
        every { service.delete("ETHEREUM", "USDC", match { it.employeeNo == "123456" && it.requestId.isNotBlank() }) } returns Unit

        mockMvc
            .perform(
                put("/admin/networks/ETHEREUM")
                    .header("X-Employee-No", "123456")
                    .header("X-Branch-Code", "0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"candidateId":"opaque-candidate"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.code").value("ETHEREUM"))

        mockMvc
            .perform(
                post("/admin/asset-mappings")
                    .header("X-Employee-No", "123456")
                    .header("X-Branch-Code", "0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"network":"ETHEREUM","symbol":"USDC","fireblocksAssetId":"secret-asset-id","contractAddress":"0xA0b8"}"""),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.network").value("ETHEREUM"))
            .andExpect(jsonPath("$.data.symbol").value("USDC"))
            .andExpect(jsonPath("$.data.fireblocksAssetId").value("secret-asset-id"))
            .andExpect(jsonPath("$.data.token").doesNotExist())
            .andExpect(jsonPath("$.data", not(hasKey<String>("vendorAssetId"))))

        mockMvc
            .perform(
                delete("/admin/asset-mappings/ETHEREUM/USDC")
                    .header("X-Employee-No", "123456")
                    .header("X-Branch-Code", "0001"),
            ).andExpect(status().isNoContent)
            .andExpect(content().string(""))

        mockMvc
            .perform(
                delete("/admin/networks/ETHEREUM")
                    .header("X-Employee-No", "123456")
                    .header("X-Branch-Code", "0001"),
            ).andExpect(status().isNoContent)
    }

    @Test
    fun `자산 일괄 등록은 최대 20건을 같은 request id로 서비스에 전달한다`() {
        every {
            service.registerAll(
                match { commands ->
                    commands.size == 2 &&
                        commands.map { it.network } == listOf("ETHEREUM", "BASE") &&
                        commands.map { it.requestId }.distinct().size == 1
                },
            )
        } returns listOf(mapping, mapping.copy(network = "BASE", vendorAssetId = "base-usdc"))

        mockMvc
            .perform(
                post("/admin/asset-mappings/bulk")
                    .header("X-Employee-No", "123456")
                    .header("X-Branch-Code", "0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"items":[{"network":"ETHEREUM","symbol":"USDC","fireblocksAssetId":"secret-asset-id","contractAddress":"0xA0b8"},{"network":"BASE","symbol":"USDC","fireblocksAssetId":"base-usdc","contractAddress":"0xBase"}]}""",
                    ),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[1].network").value("BASE"))
    }

    @Test
    fun `자산 일괄 등록은 20건을 넘으면 서비스 호출 전에 400이다`() {
        val items =
            (1..21).joinToString(",") { index ->
                """{"network":"ETHEREUM","symbol":"A$index","fireblocksAssetId":"asset-$index","contractAddress":null}"""
            }

        mockMvc
            .perform(
                post("/admin/asset-mappings/bulk")
                    .header("X-Employee-No", "123456")
                    .header("X-Branch-Code", "0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"items":[$items]}"""),
            ).andExpect(status().isBadRequest)

        verify(exactly = 0) { service.registerAll(any()) }
    }

    @Test
    fun `자산 일괄 등록 실패는 DAW ADMIN이 수정할 항목과 이유를 구조화해 돌려준다`() {
        every { service.registerAll(any()) } throws
            BulkAssetMappingException(
                index = 1,
                network = "BASE",
                symbol = "USDC",
                reason = "assetNotFound",
                failure = InvalidAssetMappingException("BASE", "assetNotFound"),
            )

        mockMvc
            .perform(
                post("/admin/asset-mappings/bulk")
                    .header("X-Employee-No", "123456")
                    .header("X-Branch-Code", "0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"items":[{"network":"ETHEREUM","symbol":"USDC","fireblocksAssetId":"eth-usdc","contractAddress":"0xEth"},{"network":"BASE","symbol":"USDC","fireblocksAssetId":"missing","contractAddress":"0xBase"}]}""",
                    ),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
            .andExpect(jsonPath("$.error.details.index").value(1))
            .andExpect(jsonPath("$.error.details.network").value("BASE"))
            .andExpect(jsonPath("$.error.details.symbol").value("USDC"))
            .andExpect(jsonPath("$.error.details.reason").value("assetNotFound"))
    }

    @Test
    fun `상태 변경 감사 헤더가 없으면 400이고 서비스는 호출하지 않는다`() {
        mockMvc
            .perform(
                post("/admin/asset-mappings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"network":"ETHEREUM","symbol":"USDC","fireblocksAssetId":"secret-asset-id","contractAddress":null}"""),
            ).andExpect(status().isBadRequest)

        verify(exactly = 0) { service.register(any()) }
    }

    @Test
    fun `등록 contractAddress 필드 생략은 네이티브 null과 구분해 400이다`() {
        mockMvc
            .perform(
                post("/admin/asset-mappings")
                    .header("X-Employee-No", "123456")
                    .header("X-Branch-Code", "0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"network":"ETHEREUM","symbol":"ETH","fireblocksAssetId":"ETH_TEST"}"""),
            ).andExpect(status().isBadRequest)

        verify(exactly = 0) { service.register(any()) }
    }

    private fun candidateSearchResult() =
        VendorAssetCatalogSearchResult(
            listOf(
                VendorAssetCatalogCandidate(
                    network = "ETHEREUM",
                    networkDisplayName = "Ethereum",
                    chainId = 1,
                    testnet = false,
                    symbol = "USDC",
                    displayName = "USD Coin",
                    fireblocksAssetId = "secret-asset-id",
                    assetClass = "FT",
                    decimals = 6,
                    contractAddress = "0xA0B8",
                    catalogSyncedAt = "20260806110000",
                    registrationAllowed = true,
                    registrationDisabledReason = null,
                ),
            ),
            listOf(VendorAssetCatalogSource("ETHEREUM", "Ethereum", VendorAssetCatalogCacheState.READY, "20260806110000")),
        )
}
