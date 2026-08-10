package com.whatto.bcm.app.application.event

import com.whatto.bcm.domain.event.OutboxEvent
import com.whatto.bcm.domain.event.OutboxEventRepository
import org.springframework.stereotype.Service

/** 다른 피처가 outbox 저장소를 직접 참조하지 않도록 발행 예약 쓰기를 캡슐화한다. */
@Service
class OutboxEventService(
    private val repository: OutboxEventRepository,
) {
    fun enqueue(events: List<OutboxEvent>) {
        repository.insertAll(events)
    }
}
