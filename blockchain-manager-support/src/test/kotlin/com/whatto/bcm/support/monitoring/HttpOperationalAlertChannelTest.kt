package com.whatto.bcm.support.monitoring

import com.sun.net.httpserver.HttpServer
import com.whatto.bcm.domain.monitoring.OperationalAlert
import com.whatto.bcm.domain.monitoring.OperationalAlertRoute
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class HttpOperationalAlertChannelTest {
    @Test
    fun `경보를 route와 안전한 식별자만 담아 bearer 인증 HTTP 채널로 보낸다`() {
        var body = ""
        var authorization = ""
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/alerts") { exchange ->
                    authorization = exchange.requestHeaders.getFirst("Authorization")
                    body = exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8)
                    exchange.sendResponseHeaders(202, -1)
                    exchange.close()
                }
                start()
            }
        val registry = SimpleMeterRegistry()
        val channel =
            HttpOperationalAlertChannel(
                OperationalAlertProperties(
                    enabled = true,
                    endpoint = "http://127.0.0.1:${server.address.port}/alerts",
                    bearerToken = "alert-secret",
                ),
                registry,
                FIXED_CLOCK,
            )

        try {
            channel.publish(
                OperationalAlert(
                    route = OperationalAlertRoute.WEBHOOK,
                    type = "webhook.alert.poison",
                    identifiers = mapOf("notificationId" to "noti-1"),
                    context = mapOf("retryCount" to "3"),
                ),
            )
        } finally {
            server.stop(0)
        }

        assertThat(authorization).isEqualTo("Bearer alert-secret")
        assertThat(body)
            .contains(
                "\"schemaVersion\":\"1\"",
                "\"route\":\"webhook\"",
                "\"type\":\"webhook.alert.poison\"",
                "\"occurredAt\":\"2026-08-17T03:04:05Z\"",
                "\"notificationId\":\"noti-1\"",
                "\"retryCount\":\"3\"",
            ).doesNotContain("alert-secret", "payload", "address", "amount")
        assertThat(
            registry
                .get("bcm.operational.alert.delivery")
                .tags("route", "webhook", "type", "webhook.alert.poison", "outcome", "sent")
                .counter()
                .count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `운영 채널이 503이어도 원 처리를 실패시키지 않고 실패 메트릭을 남긴다`() {
        val server =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/alerts") { exchange ->
                    exchange.sendResponseHeaders(503, -1)
                    exchange.close()
                }
                start()
            }
        val registry = SimpleMeterRegistry()
        val channel =
            HttpOperationalAlertChannel(
                OperationalAlertProperties(
                    enabled = true,
                    endpoint = "http://127.0.0.1:${server.address.port}/alerts",
                    bearerToken = "alert-secret",
                ),
                registry,
                FIXED_CLOCK,
            )
        val alert =
            OperationalAlert(
                route = OperationalAlertRoute.SWEEP,
                type = "sweep.alert.reconciliation-failed",
                identifiers = mapOf("accountId" to "account-1"),
            )

        try {
            assertThatCode { channel.publish(alert) }.doesNotThrowAnyException()
        } finally {
            server.stop(0)
        }

        assertThat(
            registry
                .get("bcm.operational.alert.delivery")
                .tags("route", "sweep", "type", "sweep.alert.reconciliation-failed", "outcome", "failed")
                .counter()
                .count(),
        ).isEqualTo(1.0)
    }

    @Test
    fun `채널 활성화 시 endpoint가 비어 있으면 시작을 거부한다`() {
        assertThatCode {
            HttpOperationalAlertChannel(
                OperationalAlertProperties(enabled = true, endpoint = ""),
                SimpleMeterRegistry(),
                FIXED_CLOCK,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `채널 활성화 시 bearer token이 비어 있으면 시작을 거부한다`() {
        assertThatCode {
            HttpOperationalAlertChannel(
                OperationalAlertProperties(enabled = true, endpoint = "https://alerts.example.test"),
                SimpleMeterRegistry(),
                FIXED_CLOCK,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-08-17T03:04:05Z"), ZoneOffset.UTC)
    }
}
