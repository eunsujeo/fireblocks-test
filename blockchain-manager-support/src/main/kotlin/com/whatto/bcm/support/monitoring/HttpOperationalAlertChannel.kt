package com.whatto.bcm.support.monitoring

import com.whatto.bcm.domain.monitoring.OperationalAlert
import com.whatto.bcm.domain.monitoring.OperationalAlertChannel
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration

@ConfigurationProperties("bcm.operational-alert")
data class OperationalAlertProperties(
    var enabled: Boolean = false,
    var endpoint: String = "",
    var bearerToken: String = "",
    var connectTimeoutMillis: Long = 1_000,
    var readTimeoutMillis: Long = 2_000,
) {
    override fun toString(): String =
        "OperationalAlertProperties(" +
            "enabled=$enabled, endpoint='$endpoint', bearerToken='[REDACTED]', " +
            "connectTimeoutMillis=$connectTimeoutMillis, readTimeoutMillis=$readTimeoutMillis)"
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OperationalAlertProperties::class)
class OperationalAlertChannelConfiguration {
    @Bean
    fun operationalAlertChannel(
        properties: OperationalAlertProperties,
        registry: MeterRegistry,
        clock: Clock,
    ): OperationalAlertChannel = HttpOperationalAlertChannel(properties, registry, clock)
}

class HttpOperationalAlertChannel(
    private val properties: OperationalAlertProperties,
    private val registry: MeterRegistry,
    private val clock: Clock,
) : OperationalAlertChannel {
    init {
        require(properties.connectTimeoutMillis > 0) { "operational alert connect timeout must be positive" }
        require(properties.readTimeoutMillis > 0) { "operational alert read timeout must be positive" }
        require(!properties.enabled || properties.bearerToken.isNotBlank()) {
            "operational alert bearer token is required when enabled"
        }
    }

    private val endpoint: URI? = configuredEndpoint(properties)
    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofMillis(properties.connectTimeoutMillis))
            .build()

    override fun publish(alert: OperationalAlert) {
        val target = endpoint
        if (target == null) {
            record(alert, "disabled")
            logger.error(
                "Operational alert channel disabled route={} type={} identifiers={} context={}",
                alert.route.value,
                alert.type,
                alert.identifiers,
                alert.context,
            )
            return
        }

        try {
            val response = client.send(request(target, alert), HttpResponse.BodyHandlers.discarding())
            if (response.statusCode() in 200..299) {
                record(alert, "sent")
            } else {
                record(alert, "failed")
                logger.error(
                    "Operational alert delivery failed route={} type={} status={}",
                    alert.route.value,
                    alert.type,
                    response.statusCode(),
                )
            }
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            recordFailure(alert, exception)
        } catch (exception: Exception) {
            recordFailure(alert, exception)
        }
    }

    private fun request(
        target: URI,
        alert: OperationalAlert,
    ): HttpRequest {
        val builder =
            HttpRequest
                .newBuilder(target)
                .timeout(Duration.ofMillis(properties.readTimeoutMillis))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload(alert)))
        if (properties.bearerToken.isNotBlank()) {
            builder.header("Authorization", "Bearer ${properties.bearerToken}")
        }
        return builder.build()
    }

    private fun payload(alert: OperationalAlert): String =
        "{" +
            "\"schemaVersion\":\"1\"," +
            "\"route\":${alert.route.value.jsonString()}," +
            "\"type\":${alert.type.jsonString()}," +
            "\"occurredAt\":${clock.instant().toString().jsonString()}," +
            "\"identifiers\":${alert.identifiers.jsonObject()}," +
            "\"context\":${alert.context.jsonObject()}" +
            "}"

    private fun recordFailure(
        alert: OperationalAlert,
        exception: Exception,
    ) {
        record(alert, "failed")
        logger.error(
            "Operational alert delivery failed route={} type={}",
            alert.route.value,
            alert.type,
            exception,
        )
    }

    private fun record(
        alert: OperationalAlert,
        outcome: String,
    ) {
        registry
            .counter(
                "bcm.operational.alert.delivery",
                "route",
                alert.route.value,
                "type",
                alert.type,
                "outcome",
                outcome,
            ).increment()
    }

    private fun Map<String, String>.jsonObject(): String =
        entries
            .sortedBy { it.key }
            .joinToString(prefix = "{", postfix = "}") { (key, value) ->
                "${key.jsonString()}:${value.jsonString()}"
            }

    private fun String.jsonString(): String =
        buildString(length + 2) {
            append('"')
            this@jsonString.forEach { character ->
                when (character) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000c' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else ->
                        if (character.code < 0x20) {
                            append("\\u%04x".format(character.code))
                        } else {
                            append(character)
                        }
                }
            }
            append('"')
        }

    private companion object {
        val logger = LoggerFactory.getLogger(HttpOperationalAlertChannel::class.java)

        fun configuredEndpoint(properties: OperationalAlertProperties): URI? {
            if (!properties.enabled) return null
            require(properties.endpoint.isNotBlank()) { "operational alert endpoint is required when enabled" }
            val endpoint = URI.create(properties.endpoint)
            require(endpoint.scheme in setOf("http", "https") && endpoint.host != null && endpoint.userInfo == null) {
                "operational alert endpoint must be an HTTP(S) URI without user info"
            }
            return endpoint
        }
    }
}
