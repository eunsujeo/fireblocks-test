package com.whatto.bcm.app.application.id

import com.whatto.bcm.domain.event.EventIdGenerator
import com.whatto.bcm.support.id.UuidV7Generator
import org.springframework.stereotype.Component
import java.time.Clock

/** 같은 프로세스 안에서 문자열 정렬 순서가 생성 순서와 일치하는 UUID v7 생성기. */
@Component
class UuidV7EventIdGenerator(
    clock: Clock,
) : EventIdGenerator {
    private val delegate = UuidV7Generator(clock)

    override fun nextId(): String = delegate.nextId()
}
