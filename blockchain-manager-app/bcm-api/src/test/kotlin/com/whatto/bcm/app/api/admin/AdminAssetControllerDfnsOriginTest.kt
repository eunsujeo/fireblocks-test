package com.whatto.bcm.app.api.admin

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi
import com.ninjasquad.springmockk.MockkBean
import com.whatto.bcm.app.application.asset.VendorAssetMappingService
import com.whatto.bcm.domain.asset.TokenStandard
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.exception.InvalidAssetMappingException
import io.mockk.every
import org.hamcrest.Matchers.hasKey
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.File

/** Dfns 데이터셋 원천의 Admin 자산 매핑 계약 — 요청은 network·contractAddress로 자산을 지정하고 응답은 Dfns 키만 Dfns 이름의 필드로 노출한다. */
@WebMvcTest(AdminAssetController::class)
@Import(DfnsOriginTestConfiguration::class)
class AdminAssetControllerDfnsOriginTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var service: VendorAssetMappingService

    private val mapping =
        VendorAssetMapping(
            "ETHEREUM_SEPOLIA",
            "USDC",
            "EthereumSepolia:Erc20:0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48",
            "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48",
            "20260915000000",
            "123456",
            "0001",
        )

    @Test
    fun `Fireblocks asset id 없이 등록하면 명령에 null로 넘기고 응답은 dfnsAssetKey만 채운다`() {
        every { service.register(match { it.fireblocksAssetId == null && it.contractAddress == mapping.contractAddress }) } returns mapping
        every { service.mappings("ETHEREUM_SEPOLIA", null) } returns listOf(mapping)

        mockMvc
            .perform(
                post("/admin/asset-mappings")
                    .header("X-Employee-No", "123456")
                    .header("X-Branch-Code", "0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"network":"ETHEREUM_SEPOLIA","symbol":"USDC","contractAddress":"${mapping.contractAddress}"}"""),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.fireblocksAssetId").value(nullValue()))
            .andExpect(jsonPath("$.data.dfnsAssetKey").value(mapping.vendorAssetId))
            .andExpect(jsonPath("$.data", not(hasKey<String>("vendorAssetId"))))
            .andExpect(openApi().isValid(SPEC))

        mockMvc
            .perform(get("/admin/asset-mappings").param("network", "ETHEREUM_SEPOLIA"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].fireblocksAssetId").value(nullValue()))
            .andExpect(jsonPath("$.data[0].dfnsAssetKey").value(mapping.vendorAssetId))
            .andExpect(openApi().isValid(SPEC))
    }

    @Test
    fun `Solana mint 등록은 tokenStandard를 명령으로 넘기고 허용 값 밖은 역직렬화 400이다`() {
        val solana = mapping.copy(network = "SOLANA_DEVNET", vendorAssetId = "SolanaDevnet:Spl2022:$MINT", contractAddress = MINT)
        every {
            service.register(
                match {
                    it.network == "SOLANA_DEVNET" &&
                        it.tokenStandard == TokenStandard.SPL_2022 &&
                        it.contractAddress == MINT
                },
            )
        } returns solana

        mockMvc
            .perform(
                post("/admin/asset-mappings")
                    .header("X-Employee-No", "123456")
                    .header("X-Branch-Code", "0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"network":"SOLANA_DEVNET","symbol":"USDC","contractAddress":"$MINT","tokenStandard":"SPL_2022"}"""),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.dfnsAssetKey").value(solana.vendorAssetId))
            .andExpect(openApi().isValid(SPEC))

        mockMvc
            .perform(
                post("/admin/asset-mappings")
                    .header("X-Employee-No", "123456")
                    .header("X-Branch-Code", "0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"network":"SOLANA_DEVNET","symbol":"USDC","contractAddress":"$MINT","tokenStandard":"ERC20"}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `Dfns 원천에 Fireblocks asset id를 실으면 관문의 400 사유를 그대로 돌려주고 빈 문자열은 검증에서 400이다`() {
        every { service.register(match { it.fireblocksAssetId == "USDC_ETH_TEST5" }) } throws
            InvalidAssetMappingException("ETHEREUM_SEPOLIA", "fireblocksAssetIdNotApplicable")

        mockMvc
            .perform(
                post("/admin/asset-mappings")
                    .header("X-Employee-No", "123456")
                    .header("X-Branch-Code", "0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """{"network":"ETHEREUM_SEPOLIA","symbol":"USDC","fireblocksAssetId":"USDC_ETH_TEST5","contractAddress":"${mapping.contractAddress}"}""",
                    ),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
            .andExpect(openApi().isValid(SPEC))

        mockMvc
            .perform(
                post("/admin/asset-mappings")
                    .header("X-Employee-No", "123456")
                    .header("X-Branch-Code", "0001")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"network":"ETHEREUM_SEPOLIA","symbol":"USDC","fireblocksAssetId":" ","contractAddress":null}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
    }

    companion object {
        private const val MINT = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
        private val SPEC: String = File("../../docs/api/openapi.yaml").absolutePath
    }
}
