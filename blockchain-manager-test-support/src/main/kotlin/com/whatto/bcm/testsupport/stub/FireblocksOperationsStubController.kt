package com.whatto.bcm.testsupport.stub

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.ObjectMapper
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@RestController
@ConditionalOnProperty(prefix = "bcm.test-support", name = ["vendor-mode"], havingValue = "STUB", matchIfMissing = true)
internal class FireblocksOperationsStubController(
    private val chain: LocalStubChainState,
    private val webhooks: LocalWebhookState,
    private val signer: LocalWebhookSigner,
    private val delivery: LocalWebhookDeliveryService,
) {
    @GetMapping("/v1/estimate_network_fee")
    fun estimateNetworkFee(
        @RequestParam assetId: String,
    ): EstimatedNetworkFeeResponse {
        chain.asset(assetId) ?: notFound("local asset does not exist")
        return EstimatedNetworkFeeResponse(
            low = NetworkFeeResponse(gasPrice = "1", baseFee = "0.875", priorityFee = "0.125"),
            medium = NetworkFeeResponse(gasPrice = "2", baseFee = "1.5", priorityFee = "0.5"),
            high = NetworkFeeResponse(gasPrice = "3", baseFee = "2", priorityFee = "1"),
        )
    }

    @GetMapping("/v1/webhooks/{webhookId}")
    fun webhook(
        @PathVariable webhookId: String,
    ): WebhookResponse = webhooks.webhook(webhookId)

    @PatchMapping("/v1/webhooks/{webhookId}")
    fun updateWebhook(
        @PathVariable webhookId: String,
        @RequestBody request: UpdateWebhookRequest,
    ): WebhookResponse = webhooks.update(webhookId, request.enabled)

    @PostMapping("/v1/webhooks/{webhookId}/notifications/resend_failed")
    fun resendFailedNotifications(
        @PathVariable webhookId: String,
    ): ResendFailedNotificationsResponse = delivery.resendFailed(webhookId)

    @PostMapping("/__stub/webhooks/notifications/redeliver-last")
    fun redeliverLastNotification() {
        delivery.redeliverLast()
    }

    @PostMapping("/__stub/faults/webhooks/next-delivery")
    fun failNextWebhookDelivery() {
        delivery.failNextDelivery()
    }

    @GetMapping("/.well-known/jwks.json")
    fun jwks(): JwksResponse = signer.jwks()

    @PostMapping(
        "/__stub/webhooks/signatures",
        consumes = [MediaType.APPLICATION_OCTET_STREAM_VALUE],
    )
    fun signWebhook(
        @RequestBody body: ByteArray,
    ): WebhookSignatureResponse = WebhookSignatureResponse(signer.sign(body))

    private fun notFound(message: String): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND, message)
}

@Component
@ConditionalOnProperty(prefix = "bcm.test-support", name = ["vendor-mode"], havingValue = "STUB", matchIfMissing = true)
internal class LocalWebhookState {
    private val lock = ReentrantLock()
    private var status = "SUSPENDED"
    private val failedNotifications = ArrayDeque<ByteArray>()

    fun webhook(webhookId: String): WebhookResponse =
        lock.withLock {
            requireLocalWebhook(webhookId)
            response()
        }

    fun update(
        webhookId: String,
        enabled: Boolean,
    ): WebhookResponse =
        lock.withLock {
            requireLocalWebhook(webhookId)
            status = if (enabled) "ENABLED" else "DISABLED"
            response()
        }

    fun enabled(): Boolean = lock.withLock { status == "ENABLED" }

    fun recordFailure(payload: ByteArray) =
        lock.withLock {
            failedNotifications.addLast(payload.copyOf())
        }

    fun drainFailures(webhookId: String): List<ByteArray> =
        lock.withLock {
            requireLocalWebhook(webhookId)
            buildList {
                while (failedNotifications.isNotEmpty()) add(failedNotifications.removeFirst())
            }
        }

    fun reset() {
        lock.withLock {
            status = "SUSPENDED"
            failedNotifications.clear()
        }
    }

    private fun response(): WebhookResponse = WebhookResponse(LOCAL_WEBHOOK_ID, status, SUPPORTED_EVENTS)

    private fun requireLocalWebhook(webhookId: String) {
        if (webhookId != LOCAL_WEBHOOK_ID) throw ResponseStatusException(HttpStatus.NOT_FOUND, "local webhook does not exist")
    }

    companion object {
        const val LOCAL_WEBHOOK_ID = "local-webhook"
        val SUPPORTED_EVENTS =
            listOf(
                "transaction.created",
                "transaction.status.updated",
                "transaction.approval_status.updated",
                "transaction.network_records.processing_completed",
            )
    }
}

@Component
@ConditionalOnProperty(prefix = "bcm.test-support", name = ["vendor-mode"], havingValue = "STUB", matchIfMissing = true)
internal class LocalWebhookDeliveryService(
    private val properties: com.whatto.bcm.testsupport.config.TestSupportProperties,
    private val webhooks: LocalWebhookState,
    private val signer: LocalWebhookSigner,
    private val objectMapper: ObjectMapper,
) {
    private val client =
        java.net.http.HttpClient
            .newBuilder()
            .connectTimeout(DELIVERY_TIMEOUT)
            .build()
    private val eventSequence =
        java.util.concurrent.atomic
            .AtomicLong()
    private val lastPayload =
        java.util.concurrent.atomic
            .AtomicReference<ByteArray?>()
    private val failNextDelivery =
        java.util.concurrent.atomic
            .AtomicBoolean()

    fun publish(
        eventType: String,
        transaction: TransactionResponse,
    ) {
        if (!webhooks.enabled() || properties.webhookDeliveryUrl.isBlank()) return
        val payload =
            objectMapper.writeValueAsBytes(
                linkedMapOf(
                    "id" to "event-local-${eventSequence.incrementAndGet().toString().padStart(8, '0')}",
                    "eventType" to eventType,
                    "data" to transaction,
                ),
            )
        lastPayload.set(payload.copyOf())
        if (failNextDelivery.compareAndSet(true, false)) {
            webhooks.recordFailure(payload)
            return
        }
        if (!deliver(payload)) webhooks.recordFailure(payload)
    }

    fun failNextDelivery() {
        if (!failNextDelivery.compareAndSet(false, true)) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "next webhook delivery failure is already configured")
        }
    }

    fun redeliverLast() {
        val payload = lastPayload.get()?.copyOf() ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "no webhook was delivered")
        if (!deliver(payload)) {
            webhooks.recordFailure(payload)
            throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "injected webhook redelivery failed")
        }
    }

    fun reset() {
        eventSequence.set(0)
        lastPayload.set(null)
        failNextDelivery.set(false)
    }

    fun resendFailed(webhookId: String): ResendFailedNotificationsResponse {
        val failures = webhooks.drainFailures(webhookId)
        failures.forEach { payload ->
            if (!deliver(payload)) webhooks.recordFailure(payload)
        }
        return ResendFailedNotificationsResponse(failures.size)
    }

    private fun deliver(payload: ByteArray): Boolean =
        try {
            val response =
                client.send(
                    java.net.http.HttpRequest
                        .newBuilder(java.net.URI.create(properties.webhookDeliveryUrl))
                        .timeout(DELIVERY_TIMEOUT)
                        .header("Content-Type", "application/json")
                        .header(WEBHOOK_SIGNATURE_HEADER, signer.sign(payload))
                        .POST(
                            java.net.http.HttpRequest.BodyPublishers
                                .ofByteArray(payload),
                        ).build(),
                    java.net.http.HttpResponse.BodyHandlers
                        .discarding(),
                )
            response.statusCode() in 200..299
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (_: Exception) {
            false
        }

    companion object {
        private const val WEBHOOK_SIGNATURE_HEADER = "Fireblocks-Webhook-Signature"
        private val DELIVERY_TIMEOUT = java.time.Duration.ofSeconds(3)
    }
}

@Component
@ConditionalOnProperty(prefix = "bcm.test-support", name = ["vendor-mode"], havingValue = "STUB", matchIfMissing = true)
internal class LocalWebhookSigner(
    private val objectMapper: ObjectMapper,
) {
    private val keyPair: KeyPair =
        KeyPairGenerator
            .getInstance("RSA")
            .apply { initialize(RSA_KEY_SIZE) }
            .generateKeyPair()
    private val keyId = "bcm-local-webhook-${sha256Hex(keyPair.public.encoded).take(KEY_FINGERPRINT_LENGTH)}"

    fun sign(payload: ByteArray): String {
        val protected =
            base64Url(
                objectMapper.writeValueAsBytes(
                    linkedMapOf(
                        "alg" to "RS512",
                        "kid" to keyId,
                    ),
                ),
            )
        val encodedPayload = base64Url(payload)
        val signature =
            Signature.getInstance("SHA512withRSA").run {
                initSign(keyPair.private)
                update("$protected.$encodedPayload".toByteArray(StandardCharsets.US_ASCII))
                sign()
            }
        return "$protected..${base64Url(signature)}"
    }

    fun jwks(): JwksResponse {
        val publicKey = keyPair.public as RSAPublicKey
        return JwksResponse(
            keys =
                listOf(
                    JwkResponse(
                        kid = keyId,
                        kty = "RSA",
                        alg = "RS512",
                        use = "sig",
                        n = base64Url(publicKey.modulus.toUnsignedBytes()),
                        e = base64Url(publicKey.publicExponent.toUnsignedBytes()),
                    ),
                ),
        )
    }

    private fun base64Url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun sha256Hex(value: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value)
            .joinToString("") { "%02x".format(it) }

    private fun BigInteger.toUnsignedBytes(): ByteArray {
        val bytes = toByteArray()
        return if (bytes.first() == 0.toByte()) bytes.drop(1).toByteArray() else bytes
    }

    companion object {
        private const val RSA_KEY_SIZE = 2048
        private const val KEY_FINGERPRINT_LENGTH = 12
    }
}

internal data class EstimatedNetworkFeeResponse(
    val low: NetworkFeeResponse,
    val medium: NetworkFeeResponse,
    val high: NetworkFeeResponse,
)

internal data class NetworkFeeResponse(
    val gasPrice: String,
    val baseFee: String,
    val priorityFee: String,
)

internal data class UpdateWebhookRequest(
    val enabled: Boolean,
)

internal data class WebhookResponse(
    val id: String,
    val status: String,
    val events: List<String>,
)

internal data class ResendFailedNotificationsResponse(
    val total: Int,
)

internal data class WebhookSignatureResponse(
    val signature: String,
)

internal data class JwksResponse(
    val keys: List<JwkResponse>,
)

internal data class JwkResponse(
    val kid: String,
    val kty: String,
    val alg: String,
    val use: String,
    val n: String,
    val e: String,
)
