package com.whatto.bcm.domain.event

/** 이벤트 브로커 발행 경계. partitionKey는 같은 계정 이벤트 순서를 고정한다. */
fun interface EventPublisher {
    fun publish(
        topic: String,
        partitionKey: String,
        payload: String,
    )
}

/** 재시도 상한을 넘겨 발행하지 못한 outbox 이벤트 신호. */
fun interface PoisonOutboxAlertPort {
    fun alert(
        eventId: String,
        retryCount: Int,
    )
}
