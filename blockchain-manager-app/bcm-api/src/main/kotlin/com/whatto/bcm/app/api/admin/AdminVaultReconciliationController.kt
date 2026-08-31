package com.whatto.bcm.app.api.admin

import com.whatto.bcm.app.api.web.ApiResponse
import com.whatto.bcm.app.api.web.RequestIdFilter
import com.whatto.bcm.app.application.admin.AdminVaultReconciliationResult
import com.whatto.bcm.app.application.admin.AdminVaultReconciliationService
import com.whatto.bcm.domain.admin.VaultReconciliation
import com.whatto.bcm.domain.admin.VaultReconciliationItem
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset

@Validated
@RestController
class AdminVaultReconciliationController(
    private val service: AdminVaultReconciliationService,
) {
    @PostMapping("/admin/vault-reconciliations", consumes = [MediaType.APPLICATION_JSON_VALUE])
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun start(
        @Valid @RequestBody request: StartAdminVaultReconciliationRequest,
        httpRequest: HttpServletRequest,
    ): ApiResponse<AdminVaultReconciliationRunData> =
        ApiResponse.of(
            AdminVaultReconciliationRunData.from(service.start(request.q)),
            RequestIdFilter.requestIdOf(httpRequest),
        )

    @GetMapping("/admin/vault-reconciliations/{runId}")
    fun result(
        @PathVariable @Size(max = 36) runId: String,
        @RequestParam(required = false) @Size(max = 64) cursor: String?,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) limit: Int,
        httpRequest: HttpServletRequest,
    ): ApiResponse<AdminVaultReconciliationData> =
        ApiResponse.of(
            AdminVaultReconciliationData.from(service.result(runId, cursor, limit)),
            RequestIdFilter.requestIdOf(httpRequest),
        )
}

data class StartAdminVaultReconciliationRequest(
    @field:Size(max = 128)
    val q: String? = null,
)

data class AdminVaultReconciliationData(
    val run: AdminVaultReconciliationRunData,
    val items: List<AdminVaultData>,
    val nextCursor: String?,
) {
    companion object {
        fun from(result: AdminVaultReconciliationResult) =
            AdminVaultReconciliationData(
                run = AdminVaultReconciliationRunData.from(result.run),
                items = result.items.map(AdminVaultData::from),
                nextCursor = result.nextCursor,
            )
    }
}

data class AdminVaultReconciliationRunData(
    val runId: String,
    val query: String?,
    val status: String,
    val vendorPageCount: Int,
    val vendorVaultCount: Long,
    val resultCount: Long,
    val failureCode: String?,
    val requestedAt: String,
    val startedAt: String?,
    val finishedAt: String?,
) {
    companion object {
        fun from(run: VaultReconciliation) =
            AdminVaultReconciliationRunData(
                runId = run.runId,
                query = run.query,
                status = run.status.name,
                vendorPageCount = run.vendorPageCount,
                vendorVaultCount = run.vendorVaultCount,
                resultCount = run.resultCount,
                failureCode = run.failureCode,
                requestedAt = run.requestedAt.isoInstant(),
                startedAt = run.startedAt?.isoInstant(),
                finishedAt = run.finishedAt?.isoInstant(),
            )
    }
}

data class AdminVaultData(
    val reconciliationStatus: String,
    val accountId: String?,
    val accountType: String?,
    val ref: String?,
    val vendorVaultId: String,
    val vendorVaultName: String?,
    val walletCount: Int?,
    val registeredAt: String?,
) {
    companion object {
        fun from(item: VaultReconciliationItem) =
            AdminVaultData(
                reconciliationStatus = item.reconciliationStatus.name,
                accountId = item.accountId,
                accountType = item.accountType?.name,
                ref = item.ref,
                vendorVaultId = item.vendorVaultId,
                vendorVaultName = item.vendorVaultName,
                walletCount = item.walletCount,
                registeredAt = item.registeredAt,
            )
    }
}

private fun String.isoInstant(): String =
    com.whatto.bcm.support.time.CoreDateTimes
        .parse(this)
        .toInstant(ZoneOffset.UTC)
        .toString()
