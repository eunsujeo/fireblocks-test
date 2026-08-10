package com.whatto.bcm.app.api.web

import com.whatto.bcm.domain.exception.AccountNotFoundException
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.RelayRejectedException
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import com.whatto.bcm.domain.exception.SubmissionInProgressException
import com.whatto.bcm.domain.exception.VendorApiException
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 공통 규약 검증 전용 테스트 컨트롤러 — 실제 오퍼레이션은 T2.3 부터.
 */
@RestController
@RequestMapping("/test-envelope")
class EnvelopeTestController {
    @GetMapping("/single")
    fun single(request: HttpServletRequest): ApiResponse<Map<String, String>> =
        ApiResponse.of(mapOf("ref" to "ACT-000123"), RequestIdFilter.requestIdOf(request))

    @GetMapping("/paged")
    fun paged(request: HttpServletRequest): ApiResponse<List<Map<String, String>>> =
        ApiResponse.of(
            listOf(mapOf("txId" to "tx_9f2a")),
            RequestIdFilter.requestIdOf(request),
            Pagination(nextCursor = "cursor-next", hasMore = true),
        )

    @PostMapping("/echo")
    fun echo(
        @RequestBody body: Map<String, Any>,
        request: HttpServletRequest,
    ): ApiResponse<Map<String, Any>> = ApiResponse.of(body, RequestIdFilter.requestIdOf(request))

    @GetMapping("/account-missing")
    fun accountMissing(): Nothing = throw AccountNotFoundException(accountId = "acct-404")

    @GetMapping("/resource-missing")
    fun resourceMissing(): Nothing = throw ResourceNotFoundException(resource = "depositAddress", key = "acct-1:BTC")

    @GetMapping("/conflict")
    fun conflict(): Nothing = throw ConflictException(resource = "transaction", key = "ext-tx-1")

    @GetMapping("/relay-rejected")
    fun relayRejected(): Nothing = throw RelayRejectedException(reason = "gas estimation rejected")

    @GetMapping("/submission-in-progress")
    fun submissionInProgress(): Nothing = throw SubmissionInProgressException("wd-1", 3)

    @GetMapping("/vendor-failure")
    fun vendorFailure(): Nothing = throw VendorApiException(operation = "createVault", httpStatus = 503)

    @GetMapping("/boom")
    fun boom(): Nothing = throw IllegalStateException("internal secret detail")
}
