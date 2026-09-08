package com.whatto.bcm.app.webhook.application.webhook

import com.whatto.bcm.app.application.id.UuidV7EventIdGenerator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class UuidV7EventIdGeneratorTest {
    @Test
    fun `같은 밀리초에 연속 생성해도 UUID v7 문자열 순서가 생성 순서와 같다`() {
        val generator =
            UuidV7EventIdGenerator(
                Clock.fixed(Instant.parse("2026-08-07T00:00:00Z"), ZoneOffset.UTC),
            )

        val ids = List(5_000) { generator.nextId() }

        assertThat(ids).containsExactlyElementsOf(ids.sorted())
        assertThat(ids.map(UUID::fromString).map(UUID::version)).containsOnly(7)
        assertThat(ids.map(UUID::fromString).map(UUID::variant)).containsOnly(2)
    }
}
