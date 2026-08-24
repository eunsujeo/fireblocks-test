package com.whatto.bcm.infra.messaging

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LocalKafkaEventProbeTest {
    @Test
    fun `loopback와 제한된 기대값만 허용한다`() {
        assertThat(
            LocalKafkaEventProbe.requested(
                arrayOf("127.0.0.1:9092", "deposit-events", "local-deposit-1", "15000", "account-1", "FINALIZED", "1"),
            ),
        ).isTrue()
        listOf(
            arrayOf("kafka.internal:9092", "deposit-events", "group", "15000", "account-1", "FINALIZED", "1"),
            arrayOf("127.0.0.1:9092", "../topic", "group", "15000", "account-1", "FINALIZED", "1"),
            arrayOf("127.0.0.1:9092", "deposit-events", "group", "0", "account-1", "FINALIZED", "1"),
            arrayOf("127.0.0.1:9092", "deposit-events", "group", "15000", "account 1", "FINALIZED", "1"),
        ).forEach { assertThat(LocalKafkaEventProbe.requested(it)).isFalse() }
    }
}
