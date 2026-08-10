package com.whatto.bcm.domain.event

/** outbox/ChainEvent 공용 UUID v7 생성 포트. */
fun interface EventIdGenerator {
    fun nextId(): String
}
