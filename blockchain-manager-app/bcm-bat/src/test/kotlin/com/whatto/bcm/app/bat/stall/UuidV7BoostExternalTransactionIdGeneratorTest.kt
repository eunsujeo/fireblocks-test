package com.whatto.bcm.app.bat.stall

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class UuidV7BoostExternalTransactionIdGeneratorTest {
    @Test
    fun `boost externalTxId는 bst 접두 UUID v7이다`() {
        val generator =
            UuidV7BoostExternalTransactionIdGenerator(
                Clock.fixed(Instant.parse("2026-08-13T03:00:00Z"), ZoneOffset.UTC),
            )

        val id = generator.nextId()

        assertThat(id).startsWith("bst-")
        assertThat(UUID.fromString(id.removePrefix("bst-")).version()).isEqualTo(7)
    }
}
