package com.whatto.bcm.app.api.admin

import com.ninjasquad.springmockk.MockkBean
import com.whatto.bcm.app.application.asset.AdoptNetworkCommand
import com.whatto.bcm.app.application.asset.AssetCandidate
import com.whatto.bcm.app.application.asset.AuditActor
import com.whatto.bcm.app.application.asset.RegisterVendorAssetMappingCommand
import com.whatto.bcm.app.application.asset.VendorAssetMappingService
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.asset.VendorBlockchainCatalog
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
    fun `목록 3개 — network를 내부 조회 키로 쓰되 응답에 벤더 id가 없다`() {
        every { service.networks("eth", 1, true, false) } returns listOf(network)
        every { service.assetCandidates("USDC", "ETHEREUM") } returns
            listOf(AssetCandidate("ETHEREUM", "USDC", "USD Coin", 6, "0xA0B8", false))
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
            .perform(get("/admin/asset-candidates").param("network", "ETHEREUM").param("symbol", "USDC"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].network").value("ETHEREUM"))
            .andExpect(jsonPath("$.data[0].symbol").value("USDC"))
            .andExpect(jsonPath("$.data[0]", not(hasKey<String>("id"))))
            .andExpect(jsonPath("$.data[0]", not(hasKey<String>("blockchainId"))))

        mockMvc
            .perform(get("/admin/asset-mappings").param("network", "ETHEREUM").param("symbol", "USDC"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].symbol").value("USDC"))
            .andExpect(jsonPath("$.data[0].token").doesNotExist())
            .andExpect(jsonPath("$.data[0].contractAddress").value("0xA0B8"))
            .andExpect(jsonPath("$.data[0]", not(hasKey<String>("vendorAssetId"))))
            .andExpect(jsonPath("$.data[0]", not(hasKey<String>("registeredByEmployeeNo"))))
    }

    @Test
    fun `채택·등록·삭제 — 감사 헤더를 서비스 명령으로 넘기고 계약 상태를 돌려준다`() {
        every { service.adoptNetwork(AdoptNetworkCommand("ETHEREUM", "opaque-candidate", "123456", "0001")) } returns network
        every {
            service.register(RegisterVendorAssetMappingCommand("ETHEREUM", "USDC", "0xA0b8", "123456", "0001"))
        } returns mapping
        every { service.releaseNetwork("ETHEREUM", audit) } returns Unit
        every { service.delete("ETHEREUM", "USDC", audit) } returns Unit

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
                    .content("""{"network":"ETHEREUM","symbol":"USDC","contractAddress":"0xA0b8"}"""),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.network").value("ETHEREUM"))
            .andExpect(jsonPath("$.data.symbol").value("USDC"))
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
    fun `상태 변경 감사 헤더가 없으면 400이고 서비스는 호출하지 않는다`() {
        mockMvc
            .perform(
                post("/admin/asset-mappings")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"network":"ETHEREUM","symbol":"USDC","contractAddress":null}"""),
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
                    .content("""{"network":"ETHEREUM","symbol":"ETH"}"""),
            ).andExpect(status().isBadRequest)

        verify(exactly = 0) { service.register(any()) }
    }
}
