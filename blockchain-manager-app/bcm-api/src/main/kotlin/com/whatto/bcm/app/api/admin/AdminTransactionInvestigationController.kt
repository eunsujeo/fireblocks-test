package com.whatto.bcm.app.api.admin

import com.whatto.bcm.app.api.web.ApiResponse
import com.whatto.bcm.app.api.web.RequestIdFilter
import com.whatto.bcm.app.application.admin.AdminTransactionInvestigationService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

@RestController
class AdminTransactionInvestigationController(
    private val service: AdminTransactionInvestigationService,
) {
    @GetMapping("/admin/transaction-investigations/{identifier}")
    fun investigation(
        @PathVariable @NotBlank @Size(max = 128) identifier: String,
        request: HttpServletRequest,
    ): ApiResponse<AdminTransactionInvestigationData> =
        ApiResponse.of(
            AdminTransactionInvestigationData.from(service.investigate(identifier)),
            RequestIdFilter.requestIdOf(request),
        )
}
