package com.whatto.bcm.app.api.sweep

import com.whatto.bcm.app.api.account.AccountController
import com.whatto.bcm.app.api.event.DawIntegrationProperties
import com.whatto.bcm.app.api.event.EventCompletionController
import com.whatto.bcm.app.api.web.ApiResponse
import com.whatto.bcm.app.api.web.RequestIdFilter
import com.whatto.bcm.app.application.sweep.SweepRequestCommand
import com.whatto.bcm.app.application.sweep.SweepRequestCommandItem
import com.whatto.bcm.app.application.sweep.SweepRequestService
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import com.whatto.bcm.domain.sweep.SweepRequest
import com.whatto.bcm.support.time.CoreDateTimes
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset

@Validated
@RestController
class SweepRequestController(
    private val service: SweepRequestService,
    private val properties: DawIntegrationProperties,
) {
    @PostMapping("/sweeps")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun accept(
        @Valid @RequestBody body: SweepRequestBody,
        httpRequest: HttpServletRequest,
    ): ApiResponse<SweepRequestData> {
        if (!properties.enabled) throw ResourceNotFoundException("route", "sweepRequest")
        val accepted = service.accept(body.toCommand())
        return ApiResponse.of(SweepRequestData.from(accepted), RequestIdFilter.requestIdOf(httpRequest))
    }
}

data class SweepRequestBody(
    @field:NotBlank
    @field:Size(max = 128)
    val externalSweepRequestId: String,
    @field:Pattern(regexp = AccountController.NETWORK_PATTERN)
    val network: String,
    @field:Pattern(regexp = AccountController.SYMBOL_PATTERN)
    val symbol: String,
    @field:Size(min = 1)
    @field:Valid
    val items: List<SweepRequestItemBody>,
) {
    @get:AssertTrue
    val validItemIdentity: Boolean
        get() =
            items.map { it.accountId }.distinct().size == items.size &&
                items.flatMap { it.sourceEventIds }.distinct().size == items.sumOf { it.sourceEventIds.size }

    fun toCommand() =
        SweepRequestCommand(
            externalSweepRequestId,
            network,
            symbol,
            items.map { SweepRequestCommandItem(it.accountId, it.sourceEventIds) },
        )
}

data class SweepRequestItemBody(
    @field:NotBlank
    @field:Size(max = 64)
    val accountId: String,
    @field:Size(min = 1)
    val sourceEventIds: List<String>,
) {
    @get:AssertTrue
    val validSourceEventIds: Boolean
        get() = sourceEventIds.all(UUID_V7_REGEX::matches)

    private companion object {
        val UUID_V7_REGEX = Regex(EventCompletionController.UUID_PATTERN)
    }
}

data class SweepRequestData(
    val sweepRequestId: String,
    val externalSweepRequestId: String,
    val network: String,
    val symbol: String,
    val status: String,
    val requestedAt: String,
    val items: List<SweepRequestItemData>,
) {
    companion object {
        fun from(request: SweepRequest) =
            SweepRequestData(
                request.sweepRequestId,
                request.externalSweepRequestId,
                request.network,
                request.symbol,
                request.status.name,
                CoreDateTimes.parse(request.requestedAt).toInstant(ZoneOffset.UTC).toString(),
                request.items.map {
                    SweepRequestItemData(it.sweepItemId, it.accountId, it.status.name)
                },
            )
    }
}

data class SweepRequestItemData(
    val sweepItemId: String,
    val accountId: String,
    val status: String,
)
