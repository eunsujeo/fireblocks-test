package com.whatto.bcm.admin.api

import com.whatto.bcm.admin.config.AdminProperties
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class AdminEnvironmentController(
    properties: AdminProperties,
) {
    private val environment =
        AdminEnvironmentData(
            vendorMode = properties.vendorMode,
            chainMode = properties.chainMode,
            dataSet = properties.dataSet,
            assetManagementEnabled = properties.localAssetManagement.enabled,
            developerPortalUrl = "${properties.targetBaseUrl.trimEnd('/')}/api-docs/",
        )

    @GetMapping("/bff/admin/environment")
    fun environment(): AdminEnvironmentData = environment
}

data class AdminEnvironmentData(
    val vendorMode: String,
    val chainMode: String,
    val dataSet: String,
    val assetManagementEnabled: Boolean,
    val developerPortalUrl: String,
)
