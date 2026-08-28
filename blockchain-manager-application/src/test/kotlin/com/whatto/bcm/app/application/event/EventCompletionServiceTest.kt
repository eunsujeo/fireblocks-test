package com.whatto.bcm.app.application.event

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.event.EventCompletion
import com.whatto.bcm.domain.event.EventCompletionRepository
import com.whatto.bcm.domain.event.EventCompletionResult
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class EventCompletionServiceTest {
    private val repository = mockk<EventCompletionRepository>()
    private val transactionRunner =
        object : TransactionRunner {
            override fun <T> run(block: () -> T): T = block()
        }
    private val clock = Clock.fixed(Instant.parse("2026-08-27T01:02:03Z"), ZoneOffset.UTC)
    private val service = EventCompletionService(transactionRunner, repository, clock)

    @Test
    fun `발행 성공 이벤트를 DAW CORE 완료로 기록한다`() {
        val completion = completion()
        every {
            repository.complete(EVENT_ID, "DAW_CORE", "20260827010203")
        } returns EventCompletionResult.Completed(completion)

        assertThat(service.complete(EVENT_ID)).isEqualTo(completion)
    }

    @Test
    fun `응답 유실 뒤 같은 eventId를 재호출하면 최초 완료를 그대로 반환한다`() {
        val completion = completion(completedAt = "20260827005959")
        every {
            repository.complete(EVENT_ID, "DAW_CORE", "20260827010203")
        } returns EventCompletionResult.AlreadyCompleted(completion)

        assertThat(service.complete(EVENT_ID).completedAt).isEqualTo("20260827005959")
    }

    @Test
    fun `없는 이벤트와 아직 발행 성공하지 않은 이벤트를 구분한다`() {
        every {
            repository.complete(EVENT_ID, "DAW_CORE", any())
        } returns EventCompletionResult.NotFound
        assertThatThrownBy { service.complete(EVENT_ID) }
            .isInstanceOf(ResourceNotFoundException::class.java)

        every {
            repository.complete(EVENT_ID, "DAW_CORE", any())
        } returns EventCompletionResult.NotPublished
        assertThatThrownBy { service.complete(EVENT_ID) }
            .isInstanceOf(ConflictException::class.java)
    }

    private fun completion(completedAt: String = "20260827010203") =
        EventCompletion(
            eventId = EVENT_ID,
            consumer = "DAW_CORE",
            completedAt = completedAt,
            transactionId = "tx-91c",
            status = "FINALIZED",
            sweepRequestId = null,
            sweepItemId = null,
            executionId = null,
        )

    private companion object {
        const val EVENT_ID = "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7891"
    }
}
