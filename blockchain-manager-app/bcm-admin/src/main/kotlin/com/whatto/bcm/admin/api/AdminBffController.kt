package com.whatto.bcm.admin.api

import com.whatto.bcm.admin.application.AdminOverview
import com.whatto.bcm.admin.application.AdminReadService
import com.whatto.bcm.admin.application.AssetFilters
import com.whatto.bcm.admin.application.NetworkFilters
import com.whatto.bcm.admin.application.SourceIssue
import com.whatto.bcm.admin.application.ViewResult
import com.whatto.bcm.admin.application.ViewState
import com.whatto.bcm.admin.client.SourceFailure
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class BffMeta(
    val requestId: String,
    val generatedAt: String,
)

data class BffResponse<T>(
    val data: T,
    val meta: BffMeta,
    val state: ViewState,
    val issues: List<SourceIssue>,
)

data class BffErrorResponse(
    val error: BffError,
    val meta: BffMeta,
)

data class BffError(
    val code: String,
    val message: String,
)

@Validated
@RestController
class AdminBffController(
    private val service: AdminReadService,
    private val clock: Clock,
) {
    @GetMapping("/bff/admin/overview")
    fun overview(request: HttpServletRequest): BffResponse<AdminOverview> {
        val data = service.overview()
        return BffResponse(data, meta(request, data.generatedAt), data.state, data.issues)
    }

    @GetMapping("/bff/admin/networks")
    fun networks(
        @RequestParam(required = false) @Size(max = 100) q: String?,
        @RequestParam(required = false) @Min(1) chainId: Long?,
        @RequestParam(required = false) adopted: Boolean?,
        @RequestParam(required = false) testnet: Boolean?,
        request: HttpServletRequest,
    ) = respond(request, service.networks(NetworkFilters(q, chainId, adopted, testnet)))

    @GetMapping("/bff/admin/assets")
    fun assets(
        @RequestParam(required = false) @Size(max = 20) network: String?,
        @RequestParam(required = false) @Size(max = 16) symbol: String?,
        request: HttpServletRequest,
    ) = respond(request, service.assets(AssetFilters(network, symbol)))

    @GetMapping("/bff/admin/search")
    fun search(
        @RequestParam @Size(min = 2, max = 100) q: String,
        request: HttpServletRequest,
    ) = respond(request, service.search(q))

    @GetMapping("/bff/admin/transactions/{identifier}")
    fun transaction(
        @PathVariable
        @NotBlank
        @Size(max = 128)
        identifier: String,
        request: HttpServletRequest,
    ) = respond(request, service.transaction(identifier))

    @GetMapping("/bff/admin/contracts")
    fun contracts(request: HttpServletRequest) = respond(request, service.contracts())

    @GetMapping("/bff/admin/policies")
    fun policies(request: HttpServletRequest) = respond(request, service.policies())

    @GetMapping("/bff/admin/band-s")
    fun bandS(request: HttpServletRequest) = respond(request, service.bandS())

    @GetMapping("/bff/admin/emergency")
    fun emergency(request: HttpServletRequest) = respond(request, service.emergency())

    @GetMapping("/bff/admin/change-requests/{requestId}")
    fun changeRequest(
        @PathVariable @NotBlank @Size(max = 36) requestId: String,
        request: HttpServletRequest,
    ) = respond(request, service.changeRequest(requestId))

    private fun <T> respond(
        request: HttpServletRequest,
        result: ViewResult<T>,
    ): BffResponse<T> = BffResponse(result.data, meta(request), result.state, result.issues)

    private fun meta(
        request: HttpServletRequest,
        generatedAt: String = Instant.now(clock).toString(),
    ) = BffMeta(request.getHeader("X-Request-Id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(), generatedAt)
}

@RestControllerAdvice(assignableTypes = [AdminBffController::class, LocalAssetManagementBffController::class])
class AdminBffExceptionHandler(
    private val clock: Clock,
) {
    @ExceptionHandler(SourceFailure::class)
    fun sourceFailure(
        failure: SourceFailure,
        request: HttpServletRequest,
    ): ResponseEntity<BffErrorResponse> {
        val forbidden = failure.status == 403
        val notFound = failure.status == 404
        if (forbidden || notFound) {
            log.warn("BCM Admin source read unavailable source={} status={}", failure.source, failure.status)
        } else {
            log.error("BCM Admin source read failed source={} status={}", failure.source, failure.status, failure)
        }
        return ResponseEntity
            .status(
                when {
                    forbidden -> HttpStatus.FORBIDDEN
                    notFound -> HttpStatus.NOT_FOUND
                    failure.status == 400 -> HttpStatus.BAD_REQUEST
                    failure.status == 409 -> HttpStatus.CONFLICT
                    else -> HttpStatus.BAD_GATEWAY
                },
            ).body(
                BffErrorResponse(
                    BffError(
                        when {
                            forbidden -> "FORBIDDEN"
                            notFound -> "NOT_FOUND"
                            failure.status == 400 -> "VALIDATION_FAILED"
                            failure.status == 409 -> "CONFLICT"
                            else -> "UPSTREAM_UNAVAILABLE"
                        },
                        when {
                            forbidden -> "조회 권한이 없습니다."
                            notFound -> "거래를 찾을 수 없습니다."
                            failure.status == 400 -> "자산 후보와 등록 값이 일치하지 않습니다."
                            failure.status == 409 -> "이미 등록되었거나 다른 매핑과 충돌합니다."
                            else -> "BCM 조회 소스를 사용할 수 없습니다."
                        },
                    ),
                    BffMeta(
                        request.getHeader("X-Request-Id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                        Instant.now(clock).toString(),
                    ),
                ),
            )
    }

    @ExceptionHandler(LocalAssetManagementRequestForbidden::class)
    fun localAssetForbidden(request: HttpServletRequest): ResponseEntity<BffErrorResponse> =
        ResponseEntity
            .status(HttpStatus.FORBIDDEN)
            .body(
                BffErrorResponse(
                    BffError("FORBIDDEN", "로컬 자산 등록 요청 출처를 확인할 수 없습니다."),
                    BffMeta(
                        request.getHeader("X-Request-Id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
                        Instant.now(clock).toString(),
                    ),
                ),
            )

    companion object {
        private val log = LoggerFactory.getLogger(AdminBffExceptionHandler::class.java)
    }
}
