package com.whatto.bcm.admin.api

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping

@Controller
class AdminUiController {
    @GetMapping(
        "/",
        "/admin",
        "/admin/",
        "/admin/dashboard",
        "/admin/networks",
        "/admin/assets",
        "/admin/vaults",
        "/admin/contracts",
        "/admin/policies",
        "/admin/band-s",
        "/admin/emergency",
        "/admin/search",
        "/admin/transactions/{identifier}",
        "/admin/change-requests/{requestId}",
    )
    fun admin(): String = "forward:/admin/index.html"
}

@Controller
@ConditionalOnProperty(prefix = "bcm.admin.system-test", name = ["enabled"], havingValue = "true")
class SystemTestUiController {
    @GetMapping(
        "/admin/test-runs",
        "/admin/test-runs/{runId}",
    )
    fun admin(): String = "forward:/admin/index.html"
}
