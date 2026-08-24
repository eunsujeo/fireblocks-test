package com.whatto.bcm.app.api.admin

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi
import com.ninjasquad.springmockk.MockkBean
import com.whatto.bcm.app.application.asset.AssetCandidate
import com.whatto.bcm.app.application.asset.RegisterVendorAssetMappingCommand
import com.whatto.bcm.app.application.asset.VendorAssetMappingService
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
            service.register(RegisterVendorAssetMappingCommand("BASE", "USDC", "0x8335", "123456", "0001"))
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
    fun `자산 후보 GET은 symbol 필수이고 결과에 우리 network를 포함한다`() {
        every { service.assetCandidates("USDC", null) } returns
            listOf(AssetCandidate("BASE", "USDC", "USD Coin", 6, "0x8335", false))

        mockMvc
            .perform(get("/admin/asset-candidates").param("symbol", "USDC"))
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
