package com.whatto.bcm.admin.api

import com.whatto.bcm.admin.application.LocalAssetManagementService
import com.whatto.bcm.admin.application.LocalAssetRegistration
import com.whatto.bcm.admin.application.LocalNetworkAdoption
import com.whatto.bcm.admin.application.ViewState
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Validated
@RestController
@ConditionalOnProperty(prefix = "bcm.admin.local-asset-management", name = ["enabled"], havingValue = "true")
class LocalAssetManagementBffController(
    private val service: LocalAssetManagementService,
    private val clock: Clock,
) {
    @GetMapping("/bff/admin/asset-candidates")
    fun candidates(
        @RequestParam @Size(min = 2, max = 64) q: String,
        @RequestParam(required = false) @Size(max = 20) @Pattern(regexp = NETWORK_PATTERN) network: String?,
        request: HttpServletRequest,
    ): BffResponse<*> {
        requireLocalAssetHeader(request)
        return BffResponse(service.candidates(q, network), meta(request), ViewState.FRESH, emptyList())
    }

    @PostMapping("/bff/admin/assets", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun register(
        @Valid @RequestBody request: LocalAssetRegistrationRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<BffResponse<*>> {
        requireSameOriginMutation(httpRequest)
        val result =
            service.register(
                LocalAssetRegistration(
                    network = checkNotNull(request.network),
                    symbol = checkNotNull(request.symbol),
                    fireblocksAssetId = checkNotNull(request.fireblocksAssetId),
                    contractAddress = request.contractAddress,
                ),
            )
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(BffResponse(result, meta(httpRequest), ViewState.FRESH, emptyList()))
    }

    @PostMapping("/bff/admin/assets/bulk", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun registerAll(
        @Valid @RequestBody request: LocalAssetBulkRegistrationRequest,
        httpRequest: HttpServletRequest,
    ): ResponseEntity<BffResponse<*>> {
        requireSameOriginMutation(httpRequest)
        val result =
            service.registerAll(
                request.items.map { item ->
                    LocalAssetRegistration(
                        network = checkNotNull(item.network),
                        symbol = checkNotNull(item.symbol),
                        fireblocksAssetId = checkNotNull(item.fireblocksAssetId),
                        contractAddress = item.contractAddress,
                    )
                },
            )
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(BffResponse(result, meta(httpRequest), ViewState.FRESH, emptyList()))
    }

    @PutMapping("/bff/admin/networks/{code}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun adoptNetwork(
        @PathVariable @Pattern(regexp = NETWORK_PATTERN) code: String,
        @Valid @RequestBody request: LocalNetworkAdoptionRequest,
        httpRequest: HttpServletRequest,
    ): BffResponse<*> {
        requireSameOriginMutation(httpRequest)
        val result = service.adoptNetwork(LocalNetworkAdoption(code, checkNotNull(request.candidateId)))
        return BffResponse(result, meta(httpRequest), ViewState.FRESH, emptyList())
    }

    private fun requireLocalAssetHeader(request: HttpServletRequest) {
        if (request.getHeader(LOCAL_ASSET_HEADER) != LOCAL_ASSET_HEADER_VALUE) {
            throw LocalAssetManagementRequestForbidden()
        }
    }

    private fun requireSameOriginMutation(request: HttpServletRequest) {
        requireLocalAssetHeader(request)
        if (request.serverName.lowercase() !in LOOPBACK_NAMES) throw LocalAssetManagementRequestForbidden()
        val host = request.serverName.let { if (':' in it && !it.startsWith("[")) "[$it]" else it }
        val defaultPort = (request.scheme == "http" && request.serverPort == 80) || (request.scheme == "https" && request.serverPort == 443)
        val expectedOrigin = "${request.scheme}://$host${if (defaultPort) "" else ":${request.serverPort}"}"
        if (request.getHeader("Origin") != expectedOrigin) throw LocalAssetManagementRequestForbidden()
    }

    private fun meta(request: HttpServletRequest) =
        BffMeta(
            request.getHeader("X-Request-Id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            Instant.now(clock).toString(),
        )

    companion object {
        const val LOCAL_ASSET_HEADER = "X-BCM-Local-Asset-Management"
        const val LOCAL_ASSET_HEADER_VALUE = "execute"
        const val NETWORK_PATTERN = "[A-Z0-9_]{1,20}"
        const val SYMBOL_PATTERN = "[A-Z0-9_]{1,16}"
        val LOOPBACK_NAMES = setOf("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")
    }
}

data class LocalAssetRegistrationRequest(
    @field:NotNull
    @field:Pattern(regexp = LocalAssetManagementBffController.NETWORK_PATTERN)
    val network: String?,
    @field:NotNull
    @field:Pattern(regexp = LocalAssetManagementBffController.SYMBOL_PATTERN)
    val symbol: String?,
    @field:NotNull
    @field:Size(min = 1, max = 64)
    val fireblocksAssetId: String?,
    @field:Size(max = 128)
    val contractAddress: String?,
)

data class LocalAssetBulkRegistrationRequest(
    @field:Size(min = 1, max = 20)
    val items: List<@Valid LocalAssetRegistrationRequest>,
)

data class LocalNetworkAdoptionRequest(
    @field:NotNull
    @field:Size(min = 1, max = 64)
    val candidateId: String?,
)

class LocalAssetManagementRequestForbidden : RuntimeException("local asset management request origin is not allowed")
