package com.whatto.bcm.admin.api

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
