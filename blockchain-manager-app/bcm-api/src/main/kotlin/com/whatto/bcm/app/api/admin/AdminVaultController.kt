package com.whatto.bcm.app.api.admin

import com.whatto.bcm.app.api.web.ApiResponse
import com.whatto.bcm.app.api.web.RequestIdFilter
import com.whatto.bcm.app.application.admin.AdminVaultQueryService
import com.whatto.bcm.app.application.admin.AdminVaultSummary
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.constraints.Size
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@Validated
@RestController
class AdminVaultController(
    private val service: AdminVaultQueryService,
) {
    @GetMapping("/admin/vaults")
    fun vaults(
        @RequestParam(required = false) @Size(max = 128) q: String?,
        request: HttpServletRequest,
    ): ApiResponse<List<AdminVaultSummary>> = ApiResponse.of(service.vaults(q), RequestIdFilter.requestIdOf(request))
}
