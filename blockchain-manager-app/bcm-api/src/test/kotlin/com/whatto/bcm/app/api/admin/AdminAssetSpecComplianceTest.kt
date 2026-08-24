package com.whatto.bcm.app.api.admin

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi
import com.ninjasquad.springmockk.MockkBean
import com.whatto.bcm.app.application.asset.VendorAssetMappingService
import com.whatto.bcm.domain.asset.VendorAssetCatalogCacheState
import com.whatto.bcm.domain.asset.VendorAssetCatalogCandidate
import com.whatto.bcm.domain.asset.VendorAssetCatalogSearchResult
import com.whatto.bcm.domain.asset.VendorAssetCatalogSource
import com.whatto.bcm.domain.asset.VendorAssetMapping
import io.mockk.every
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.File
import java.nio.file.Files

@WebMvcTest(AdminAssetController::class)
class AdminAssetSpecComplianceTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var service: VendorAssetMappingService

    @Test
    fun `자산 매핑 POST 요청과 응답이 벤더 id 없는 0_3_0 계약과 일치한다`() {
        every {
            service.register(
                match {
                    it.network == "BASE" &&
                        it.symbol == "USDC" &&
                        it.contractAddress == "0x8335" &&
                        it.employeeNo == "123456" &&
                        it.branchCode == "0001" &&
                        it.requestId.isNotBlank()
                },
            )
        } returns VendorAssetMapping("BASE", "USDC", "internal-secret", "0x8335", "20260806120000", "123456", "0001")

        mockMvc
            .perform(
                post("/admin/asset-mappings")
                    .header("X-Employee-No", "123456")
                    .header("X-Branch-Code", "0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"network":"BASE","symbol":"USDC","contractAddress":"0x8335"}"""),
            ).andExpect(status().isCreated)
            .andExpect(openApi().isValid(SPEC))
    }

    @Test
    fun `자산 후보 GET은 q 필수이고 캐시 원천 상태를 포함한다`() {
        every { service.assetCandidates("USDC", null) } returns
            VendorAssetCatalogSearchResult(
                listOf(VendorAssetCatalogCandidate("BASE", "USDC", "USD Coin", "FT", 6, "0x8335", "20260824010000")),
                listOf(VendorAssetCatalogSource("BASE", VendorAssetCatalogCacheState.READY, "20260824010000")),
            )

        mockMvc
            .perform(get("/admin/asset-candidates").param("q", "USDC"))
            .andExpect(status().isOk)
            .andExpect(openApi().isValid(SPEC))
    }

    @Test
    fun `재개 변경 요청 target type은 OpenAPI enum에 포함된다`() {
        val spec = Files.readString(File(SPEC).toPath())

        assertThat(spec)
            .contains("enum: [POLICY, CONTRACT, BAND_S, ALLOWANCE_REVOKE, EXECUTION_GATE]")
    }

    companion object {
        private val SPEC: String = File("../../docs/api/openapi.yaml").absolutePath
    }
}
