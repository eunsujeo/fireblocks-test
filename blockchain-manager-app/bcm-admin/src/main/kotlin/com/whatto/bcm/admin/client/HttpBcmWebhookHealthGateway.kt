package com.whatto.bcm.admin.client

import com.whatto.bcm.admin.config.AdminProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

@Component
class HttpBcmWebhookHealthGateway(
    private val httpClient: HttpClient,
    private val objectMapper: ObjectMapper,
    private val properties: AdminProperties,
) : BcmWebhookHealthGateway {
    override fun isReady(): Boolean {
        val request =
            HttpRequest
                .newBuilder(URI.create(properties.webhookManagementBaseUrl.trimEnd('/') + "/actuator/health"))
                .timeout(Duration.ofMillis(properties.readTimeoutMillis))
                .header("Accept", "application/json")
                .GET()
                .build()
        val response =
            try {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw SourceFailure("webhookHealth", 502, "BCM Webhook health request interrupted", exception)
            } catch (exception: Exception) {
                throw SourceFailure("webhookHealth", 502, "BCM Webhook health unavailable", exception)
            }
        if (response.statusCode() != 200) {
            throw SourceFailure("webhookHealth", response.statusCode(), "BCM Webhook health rejected request")
        }
        return try {
            objectMapper.readTree(response.body()).path("status").asString() == "UP"
        } catch (exception: Exception) {
            throw SourceFailure("webhookHealth", 502, "BCM Webhook health returned an invalid contract", exception)
        }
    }
}
