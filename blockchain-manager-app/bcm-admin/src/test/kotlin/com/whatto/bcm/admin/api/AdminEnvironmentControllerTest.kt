package com.whatto.bcm.admin.api

import com.whatto.bcm.admin.config.AdminProperties
import com.whatto.bcm.admin.config.LocalAssetManagementProperties
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders

class AdminEnvironmentControllerTest {
    @Test
    fun `현재 벤더 체인 데이터셋을 함께 표시한다`() {
        val mockMvc: MockMvc =
            MockMvcBuilders
                .standaloneSetup(
                    AdminEnvironmentController(
                        AdminProperties(
                            vendorMode = "STUB",
                            chainMode = "LOCAL",
                            dataSet = "stub",
                            targetBaseUrl = "http://127.0.0.1:38080",
                            localAssetManagement = LocalAssetManagementProperties(enabled = true),
                        ),
                    ),
                ).build()

        mockMvc
            .perform(get("/bff/admin/environment"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.vendorMode").value("STUB"))
            .andExpect(jsonPath("$.chainMode").value("LOCAL"))
            .andExpect(jsonPath("$.dataSet").value("stub"))
            .andExpect(jsonPath("$.assetManagementEnabled").value(true))
            .andExpect(jsonPath("$.developerPortalUrl").value("http://127.0.0.1:38080/api-docs/"))
    }
}
