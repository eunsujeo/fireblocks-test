package com.whatto.bcm.app.api.event

import com.whatto.bcm.app.api.web.ApiResponse
import com.whatto.bcm.app.api.web.RequestIdFilter
import com.whatto.bcm.app.application.event.EventCompletionService
import com.whatto.bcm.domain.event.EventCompletion
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import com.whatto.bcm.support.time.CoreDateTimes
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.constraints.Pattern
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset

@Validated
@RestController
class EventCompletionController(
    private val service: EventCompletionService,
    private val properties: DawIntegrationProperties,
) {
    @PutMapping("/events/{eventId}/completion")
    fun complete(
        @PathVariable
        @Pattern(regexp = UUID_PATTERN)
        eventId: String,
        httpRequest: HttpServletRequest,
    ): ApiResponse<EventCompletionData> {
        if (!properties.enabled) throw ResourceNotFoundException("route", "eventCompletion")
        return ApiResponse.of(
            EventCompletionData.from(service.complete(eventId)),
            RequestIdFilter.requestIdOf(httpRequest),
        )
    }

    companion object {
        const val UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"
    }
}

data class EventCompletionData(
    val eventId: String,
    val consumer: String,
    val completedAt: String,
    val txId: String?,
    val status: String?,
    val sweepRequestId: String?,
    val sweepItemId: String?,
    val executionId: String?,
) {
    companion object {
        fun from(completion: EventCompletion): EventCompletionData =
            EventCompletionData(
                eventId = completion.eventId,
                consumer = completion.consumer,
                completedAt = CoreDateTimes.parse(completion.completedAt).toInstant(ZoneOffset.UTC).toString(),
                txId = completion.transactionId,
                status = completion.status,
                sweepRequestId = completion.sweepRequestId,
                sweepItemId = completion.sweepItemId,
                executionId = completion.executionId,
            )
    }
}
