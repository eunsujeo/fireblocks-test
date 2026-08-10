package com.whatto.bcm.app.application.webhook

import com.whatto.bcm.domain.event.EventIdGenerator
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** 같은 프로세스 안에서 문자열 정렬 순서가 생성 순서와 일치하는 UUID v7 생성기. */
@Component
class UuidV7EventIdGenerator(
    private val clock: Clock,
) : EventIdGenerator {
    private val state = AtomicLong(0)
    private val random = SecureRandom()

    override fun nextId(): String {
        val timestampAndSequence = nextTimestampAndSequence()
        val timestampMillis = timestampAndSequence ushr SEQUENCE_BITS
        val sequence = timestampAndSequence and SEQUENCE_MASK
        val mostSignificantBits =
            (timestampMillis shl 16) or
                (UUID_VERSION_7 shl 12) or
                sequence
        val leastSignificantBits =
            (random.nextLong() and VARIANT_PAYLOAD_MASK) or RFC_4122_VARIANT
        return UUID(mostSignificantBits, leastSignificantBits).toString()
    }

    private fun nextTimestampAndSequence(): Long {
        while (true) {
            val previous = state.get()
            val previousMillis = previous ushr SEQUENCE_BITS
            var nextMillis = maxOf(clock.millis(), previousMillis)
            var nextSequence =
                if (nextMillis == previousMillis) {
                    (previous and SEQUENCE_MASK) + 1
                } else {
                    random.nextInt(SEQUENCE_LIMIT).toLong()
                }
            if (nextSequence >= SEQUENCE_LIMIT) {
                nextMillis += 1
                nextSequence = 0
            }
            val next = (nextMillis shl SEQUENCE_BITS) or nextSequence
            if (state.compareAndSet(previous, next)) return next
        }
    }

    private companion object {
        const val SEQUENCE_BITS = 12
        const val SEQUENCE_LIMIT = 1 shl SEQUENCE_BITS
        const val SEQUENCE_MASK = SEQUENCE_LIMIT.toLong() - 1
        const val UUID_VERSION_7 = 0x7L
        const val RFC_4122_VARIANT = Long.MIN_VALUE
        const val VARIANT_PAYLOAD_MASK = 0x3fff_ffff_ffff_ffffL
    }
}
