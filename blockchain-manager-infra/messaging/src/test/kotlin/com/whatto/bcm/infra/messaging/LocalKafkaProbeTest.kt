package com.whatto.bcm.infra.messaging

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LocalKafkaProbeTest {
    @Test
    fun `loopback broker와 안전한 topic group 범위만 허용한다`() {
        assertThat(
            LocalKafkaProbe.requested(
                arrayOf("127.0.0.1:29092", "deposit-events", "smoke-run-1", "3", "15000"),
            ),
        ).isTrue()

        listOf(
            arrayOf("broker.example:9092", "deposit-events", "smoke-run-1", "3", "15000"),
            arrayOf("127.0.0.1:0", "deposit-events", "smoke-run-1", "3", "15000"),
            arrayOf("127.0.0.1:29092", "../topic", "smoke-run-1", "3", "15000"),
            arrayOf("127.0.0.1:29092", "deposit-events", "../group", "3", "15000"),
            arrayOf("127.0.0.1:29092", "deposit-events", "smoke-run-1", "0", "15000"),
            arrayOf("127.0.0.1:29092", "deposit-events", "smoke-run-1", "3", "0"),
        ).forEach { arguments -> assertThat(LocalKafkaProbe.requested(arguments)).isFalse() }
    }

    @Test
    fun `offset probe도 loopback broker와 안전한 topic만 허용한다`() {
        assertThat(LocalKafkaOffsetProbe.requested(arrayOf("127.0.0.1:29092", "withdrawal-events"))).isTrue()

        listOf(
            arrayOf("broker.example:9092", "withdrawal-events"),
            arrayOf("127.0.0.1:0", "withdrawal-events"),
            arrayOf("127.0.0.1:29092", "../topic"),
            arrayOf("127.0.0.1:29092"),
        ).forEach { arguments -> assertThat(LocalKafkaOffsetProbe.requested(arguments)).isFalse() }
    }
}
