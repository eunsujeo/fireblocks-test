package com.whatto.bcm.app.application.event

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.event.EventCompletion
import com.whatto.bcm.domain.event.EventCompletionRepository
import com.whatto.bcm.domain.event.EventCompletionResult
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.stereotype.Service
import java.time.Clock

@Service
class EventCompletionService(
    private val transactionRunner: TransactionRunner,
    private val completions: EventCompletionRepository,
    private val clock: Clock,
) {
    fun complete(eventId: String): EventCompletion =
        transactionRunner.run {
            when (val result = completions.complete(eventId, DAW_CORE, CoreDateTimes.now(clock))) {
                is EventCompletionResult.Completed -> result.completion
                is EventCompletionResult.AlreadyCompleted -> result.completion
                EventCompletionResult.NotFound -> throw ResourceNotFoundException("event", eventId)
                EventCompletionResult.NotPublished -> throw ConflictException("eventNotPublished", eventId)
                EventCompletionResult.NotConsumable -> throw ConflictException("eventConsumer", eventId)
            }
        }

    private companion object {
        const val DAW_CORE = "DAW_CORE"
    }
}
