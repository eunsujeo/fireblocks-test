package com.whatto.bcm.testsupport.stub

import com.whatto.bcm.testsupport.config.ApiAuthenticationMode
import com.whatto.bcm.testsupport.config.TestSupportProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.server.ResponseStatusException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Clock
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

internal class FireblocksApiAuthenticationFilter(
    private val authenticator: FireblocksApiRequestAuthenticator,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.startsWith("/v1/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val body = request.inputStream.readAllBytes()
        val uri = request.requestURI + request.queryString?.let { "?$it" }.orEmpty()
        try {
            authenticator.authenticate(
                apiKey = request.getHeader(API_KEY_HEADER),
                authorization = request.getHeader(HttpHeaders.AUTHORIZATION),
                uri = uri,
                body = body,
            )
        } catch (_: ResponseStatusException) {
            response.sendError(HttpStatus.UNAUTHORIZED.value())
            return
        }
        filterChain.doFilter(CachedBodyRequest(request, body), response)
    }

    companion object {
        private const val API_KEY_HEADER = "X-API-Key"
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "bcm.test-support", name = ["vendor-mode"], havingValue = "STUB", matchIfMissing = true)
internal class FireblocksApiAuthenticationConfiguration {
    @Bean
    fun fireblocksApiAuthenticationFilter(authenticator: FireblocksApiRequestAuthenticator): FireblocksApiAuthenticationFilter =
        FireblocksApiAuthenticationFilter(authenticator)
}

@Component
@ConditionalOnProperty(prefix = "bcm.test-support", name = ["vendor-mode"], havingValue = "STUB", matchIfMissing = true)
internal class FireblocksApiRequestAuthenticator(
    private val properties: TestSupportProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    private val usedNonces = ConcurrentHashMap<String, Long>()
    private val publicKey: PublicKey? =
        properties.apiPublicKeyFile.takeIf { properties.apiAuthenticationMode == ApiAuthenticationMode.STRICT }?.let { loadPublicKey() }

    fun authenticate(
        apiKey: String?,
        authorization: String?,
        uri: String,
        body: ByteArray,
    ) {
        if (apiKey != properties.fireblocksApiKey) unauthorized()
        val token = authorization?.takeIf { it.startsWith(BEARER_PREFIX) }?.removePrefix(BEARER_PREFIX) ?: unauthorized()
        val parts = token.split('.')
        if (parts.size != 3 || parts.any(String::isBlank)) unauthorized()
        val header = decodeDocument(parts[0])
        val payload = decodeDocument(parts[1])
        if (header.text("alg") != "RS256") unauthorized()
        val nonce = payload.text("nonce") ?: unauthorized()
        val issuedAt = payload.long("iat") ?: unauthorized()
        val expiresAt = payload.long("exp") ?: unauthorized()
        if (payload.text("sub") != properties.fireblocksApiKey) unauthorized()
        if (payload.text("uri") != uri) unauthorized()
        if (payload.text("bodyHash") != sha256Hex(body)) unauthorized()
        if (expiresAt <= issuedAt || expiresAt - issuedAt >= MAX_TOKEN_LIFETIME_SECONDS) unauthorized()
        if (properties.apiAuthenticationMode == ApiAuthenticationMode.STRICT) {
            authenticateStrict(parts, nonce, issuedAt, expiresAt)
        }
    }

    fun reset() {
        usedNonces.clear()
    }

    private fun authenticateStrict(
        parts: List<String>,
        nonce: String,
        issuedAt: Long,
        expiresAt: Long,
    ) {
        val now = clock.instant().epochSecond
        if (issuedAt > now + CLOCK_SKEW_SECONDS || expiresAt <= now - CLOCK_SKEW_SECONDS) unauthorized()
        val verified =
            try {
                Signature.getInstance("SHA256withRSA").run {
                    initVerify(checkNotNull(publicKey))
                    update("${parts[0]}.${parts[1]}".toByteArray(StandardCharsets.US_ASCII))
                    verify(decodeBase64(parts[2]))
                }
            } catch (_: Exception) {
                unauthorized()
            }
        if (!verified) unauthorized()
        usedNonces.entries.removeIf { it.value < now - CLOCK_SKEW_SECONDS }
        if (usedNonces.putIfAbsent(nonce, expiresAt) != null) unauthorized()
    }

    private fun loadPublicKey(): PublicKey {
        val pem = Files.readString(Path.of(properties.apiPublicKeyFile))
        val encoded =
            pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .filterNot(Char::isWhitespace)
        return KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(encoded)))
    }

    private fun decodeDocument(value: String): JsonNode =
        try {
            objectMapper.readTree(decodeBase64(value))
        } catch (_: Exception) {
            unauthorized()
        }

    private fun decodeBase64(value: String): ByteArray =
        try {
            Base64.getUrlDecoder().decode(value)
        } catch (_: IllegalArgumentException) {
            unauthorized()
        }

    private fun JsonNode.text(field: String): String? = get(field)?.takeIf(JsonNode::isString)?.asString()?.takeIf(String::isNotBlank)

    private fun JsonNode.long(field: String): Long? = get(field)?.takeIf(JsonNode::isIntegralNumber)?.asLong()

    private fun sha256Hex(value: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value)
            .joinToString("") { "%02x".format(it) }

    private fun unauthorized(): Nothing = throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

    companion object {
        private const val BEARER_PREFIX = "Bearer "
        private const val MAX_TOKEN_LIFETIME_SECONDS = 30
        private const val CLOCK_SKEW_SECONDS = 5
    }
}

private class CachedBodyRequest(
    request: HttpServletRequest,
    private val body: ByteArray,
) : HttpServletRequestWrapper(request) {
    override fun getInputStream(): ServletInputStream =
        object : ServletInputStream() {
            private val input = ByteArrayInputStream(body)

            override fun read(): Int = input.read()

            override fun isFinished(): Boolean = input.available() == 0

            override fun isReady(): Boolean = true

            override fun setReadListener(readListener: ReadListener?) {
                // The local Stub consumes requests synchronously.
            }
        }

    override fun getReader(): BufferedReader =
        BufferedReader(InputStreamReader(inputStream, characterEncoding?.let(Charset::forName) ?: StandardCharsets.UTF_8))

    override fun getContentLength(): Int = body.size

    override fun getContentLengthLong(): Long = body.size.toLong()
}
