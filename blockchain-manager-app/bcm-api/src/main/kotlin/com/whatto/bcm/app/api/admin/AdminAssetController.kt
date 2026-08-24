package com.whatto.bcm.app.api.admin

import com.whatto.bcm.app.api.web.ApiResponse
import com.whatto.bcm.app.api.web.RequestIdFilter
import com.whatto.bcm.app.application.asset.AdoptNetworkCommand
import com.whatto.bcm.app.application.asset.AuditActor
import com.whatto.bcm.app.application.asset.RegisterVendorAssetMappingCommand
import com.whatto.bcm.app.application.asset.VendorAssetMappingService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** 07-asset-master의 벤더 중립 Admin API. 인증은 배포 환경에서 Admin 백엔드만 접근하도록 망으로 제한한다. */
@Validated
@RestController
class AdminAssetController(
    private val service: VendorAssetMappingService,
) {
    @GetMapping("/admin/networks")
    fun networks(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) chainId: Long?,
        @RequestParam(required = false) adopted: Boolean?,
        @RequestParam(required = false) testnet: Boolean?,
        httpRequest: HttpServletRequest,
    ): ApiResponse<List<NetworkData>> =
        ApiResponse.of(
            service.networks(q, chainId, adopted, testnet).map(NetworkData::from),
            RequestIdFilter.requestIdOf(httpRequest),
        )

    @PutMapping("/admin/networks/{code}")
    fun adoptNetwork(
        @PathVariable @Pattern(regexp = NETWORK_PATTERN) code: String,
        @Valid @RequestBody request: AdoptNetworkRequest,
        @RequestHeader(EMPLOYEE_HEADER) @NotBlank @Size(max = 6) employeeNo: String,
        @RequestHeader(BRANCH_HEADER) @NotBlank @Size(max = 4) branchCode: String,
        httpRequest: HttpServletRequest,
    ): ApiResponse<NetworkData> =
        ApiResponse.of(
            NetworkData.from(
                service.adoptNetwork(
                    AdoptNetworkCommand(code, checkNotNull(request.candidateId), employeeNo, branchCode),
                ),
            ),
            RequestIdFilter.requestIdOf(httpRequest),
        )

    @DeleteMapping("/admin/networks/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun releaseNetwork(
        @PathVariable @Pattern(regexp = NETWORK_PATTERN) code: String,
        @RequestHeader(EMPLOYEE_HEADER) @NotBlank @Size(max = 6) employeeNo: String,
        @RequestHeader(BRANCH_HEADER) @NotBlank @Size(max = 4) branchCode: String,
    ) {
        service.releaseNetwork(code, AuditActor(employeeNo, branchCode))
    }

    @GetMapping("/admin/asset-candidates")
    fun assetCandidates(
        @RequestParam symbol: String,
        @RequestParam(required = false) network: String?,
        httpRequest: HttpServletRequest,
    ): ApiResponse<List<AssetCandidateData>> =
        ApiResponse.of(
            service.assetCandidates(symbol, network).map(AssetCandidateData::from),
            RequestIdFilter.requestIdOf(httpRequest),
        )

    @GetMapping("/admin/asset-mappings")
    fun mappings(
        @RequestParam(required = false) @Pattern(regexp = NETWORK_PATTERN) network: String?,
        @RequestParam(required = false) @Pattern(regexp = SYMBOL_PATTERN) symbol: String?,
        httpRequest: HttpServletRequest,
    ): ApiResponse<List<AssetMappingData>> =
        ApiResponse.of(
            service.mappings(network, symbol).map(AssetMappingData::from),
            RequestIdFilter.requestIdOf(httpRequest),
        )

    @PostMapping("/admin/asset-mappings")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(
        @Valid @RequestBody request: RegisterAssetMappingRequest,
        @RequestHeader(EMPLOYEE_HEADER) @NotBlank @Size(max = 6) employeeNo: String,
        @RequestHeader(BRANCH_HEADER) @NotBlank @Size(max = 4) branchCode: String,
        httpRequest: HttpServletRequest,
    ): ApiResponse<AssetMappingData> {
        val mapping =
            service.register(
                RegisterVendorAssetMappingCommand(
                    network = checkNotNull(request.network),
                    symbol = checkNotNull(request.symbol),
                    contractAddress = request.contractAddress,
                    employeeNo = employeeNo,
                    branchCode = branchCode,
                    requestId = RequestIdFilter.requestIdOf(httpRequest),
                ),
            )
        return ApiResponse.of(AssetMappingData.from(mapping), RequestIdFilter.requestIdOf(httpRequest))
    }

    @DeleteMapping("/admin/asset-mappings/{network}/{symbol}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @PathVariable @Pattern(regexp = NETWORK_PATTERN) network: String,
        @PathVariable @Pattern(regexp = SYMBOL_PATTERN) symbol: String,
        @RequestHeader(EMPLOYEE_HEADER) @NotBlank @Size(max = 6) employeeNo: String,
        @RequestHeader(BRANCH_HEADER) @NotBlank @Size(max = 4) branchCode: String,
        httpRequest: HttpServletRequest,
    ) {
        service.delete(network, symbol, AuditActor(employeeNo, branchCode, RequestIdFilter.requestIdOf(httpRequest)))
    }

    companion object {
        const val NETWORK_PATTERN = "[A-Z0-9_]{1,20}"
        const val SYMBOL_PATTERN = "[A-Z0-9_]{1,16}"
        const val EMPLOYEE_HEADER = "X-Employee-No"
        const val BRANCH_HEADER = "X-Branch-Code"
    }
}
