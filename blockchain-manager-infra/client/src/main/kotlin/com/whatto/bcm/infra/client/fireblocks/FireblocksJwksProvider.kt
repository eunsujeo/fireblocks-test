package com.whatto.bcm.infra.client.fireblocks

import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.infra.client.config.ConditionalOnFireblocksProtocol
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.RSAPublicKeySpec
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fireblocks JWKS 캐시. 정상 수신은 메모리만 읽고, 최초 조회와 낯선 kid(키 교체)는 별도 가상 스레드에서 수행한다.
 */
@Component
@ConditionalOnFireblocksProtocol
internal class FireblocksJwksProvider(
    private val properties: FireblocksProperties,
    private val objectMapper: ObjectMapper,
) : WebhookPublicKeyProvider {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var cachedKeys: Map<String, PublicKey> = emptyMap()

    @Volatile
    private var lastRefreshNanos: Long? = null

    @Volatile
    private var lastInitialFailureNanos: Long? = null

    private val refreshInProgress = AtomicBoolean(false)

    override fun findByKeyId(keyId: String): PublicKey? {
        cachedKeys[keyId]?.let { return it }
        if (cachedKeys.isEmpty()) return loadInitialKeys()[keyId]
        refreshAfterUnknownKey()
        return null
    }

    @Synchronized
    private fun loadInitialKeys(): Map<String, PublicKey> {
        if (cachedKeys.isNotEmpty()) return cachedKeys
        val now = System.nanoTime()
        if (isWithinCooldown(lastInitialFailureNanos, now)) {
            throw VendorApiException("loadWebhookJwks", null)
        }
        return try {
            cachedKeys = fetchOutsideRequestThread()
            lastRefreshNanos = System.nanoTime()
            lastInitialFailureNanos = null
            cachedKeys
        } catch (exception: VendorApiException) {
            lastInitialFailureNanos = System.nanoTime()
            throw exception
        }
    }

    /**
     * 캐시가 이미 있는데 낯선 kid가 오면 그 요청은 즉시 401로 끝내고 뒤에서만 갱신한다.
     * 새 벤더 키라면 다음 재전달부터 통과하고, 임의 kid 공격은 외부 호출을 동기 증폭시키지 못한다.
     */
    private fun refreshAfterUnknownKey() {
        if (isWithinCooldown(lastRefreshNanos, System.nanoTime()) || !refreshInProgress.compareAndSet(false, true)) return

        Thread
            .ofVirtual()
            .name("fireblocks-jwks-refresh")
            .start {
                try {
                    cachedKeys = fetch()
                    lastRefreshNanos = System.nanoTime()
                } catch (exception: Exception) {
                    log.warn("Fireblocks webhook JWKS background refresh failed", exception)
                } finally {
                    refreshInProgress.set(false)
                }
            }
    }

    private fun isWithinCooldown(
        sinceNanos: Long?,
        nowNanos: Long,
    ): Boolean {
        if (sinceNanos == null) return false
        val cooldown = TimeUnit.MILLISECONDS.toNanos(properties.webhookJwksRefreshCooldownMillis)
        return nowNanos - sinceNanos < cooldown
    }

    private fun fetchOutsideRequestThread(): Map<String, PublicKey> {
        if (properties.webhookJwksUrl.isBlank()) throw VendorApiException("loadWebhookJwks", null)
        val task = FutureTask(::fetch)
        Thread.ofVirtual().name("fireblocks-jwks-fetch").start(task)
        return try {
            task.get(properties.webhookJwksTimeoutMillis, TimeUnit.MILLISECONDS)
        } catch (exception: TimeoutException) {
            task.cancel(true)
            throw VendorApiException("loadWebhookJwks", null, exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw VendorApiException("loadWebhookJwks", null, exception)
        } catch (exception: ExecutionException) {
            val cause = exception.cause
            if (cause is VendorApiException) throw cause
            throw VendorApiException("loadWebhookJwks", null, cause ?: exception)
        }
    }

    private fun fetch(): Map<String, PublicKey> {
        val timeout = Duration.ofMillis(properties.webhookJwksTimeoutMillis)
        val client =
            HttpClient
                .newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
        val request =
            HttpRequest
                .newBuilder(URI.create(properties.webhookJwksUrl))
                .timeout(timeout)
                .GET()
                .build()
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        if (response.statusCode() !in 200..299) {
            throw VendorApiException("loadWebhookJwks", response.statusCode())
        }
        return parseKeys(response.body()).also {
            if (it.isEmpty()) throw VendorApiException("loadWebhookJwks", response.statusCode())
        }
    }

    private fun parseKeys(body: ByteArray): Map<String, PublicKey> {
        val keys = objectMapper.readTree(body).path("keys")
        if (!keys.isArray) return emptyMap()
        return keys
            .mapNotNull(::toPublicKey)
            .associate { it }
    }

    private fun toPublicKey(jwk: JsonNode): Pair<String, PublicKey>? {
        if (jwk.path("kty").asString() != "RSA") return null
        val algorithm = jwk.path("alg").asString()
        if (algorithm.isNotBlank() && algorithm != "RS512") return null
        val keyId = jwk.path("kid").asString().takeIf(String::isNotBlank) ?: return null
        val modulus = jwk.path("n").asString().takeIf(String::isNotBlank) ?: return null
        val exponent = jwk.path("e").asString().takeIf(String::isNotBlank) ?: return null
        val spec =
            RSAPublicKeySpec(
                BigInteger(1, Base64.getUrlDecoder().decode(modulus)),
                BigInteger(1, Base64.getUrlDecoder().decode(exponent)),
            )
        return keyId to KeyFactory.getInstance("RSA").generatePublic(spec)
    }
}
