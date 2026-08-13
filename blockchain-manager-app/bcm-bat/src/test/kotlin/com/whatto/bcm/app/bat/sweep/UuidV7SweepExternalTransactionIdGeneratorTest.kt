package com.whatto.bcm.app.bat.sweep

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class UuidV7SweepExternalTransactionIdGeneratorTest {
    @Test
    fun `sweep externalTxId는 swp 접두 UUID v7이다`() {
        val generator =
            UuidV7SweepExternalTransactionIdGenerator(
                Clock.fixed(Instant.parse("2026-08-11T03:00:00Z"), ZoneOffset.UTC),
            )

        val id = generator.nextId()

        assertThat(id).startsWith("swp-")
        assertThat(UUID.fromString(id.removePrefix("swp-")).version()).isEqualTo(7)
    }
}
