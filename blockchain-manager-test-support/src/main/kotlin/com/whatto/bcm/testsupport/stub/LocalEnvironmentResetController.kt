package com.whatto.bcm.testsupport.stub

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.filter.OncePerRequestFilter
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

@RestController
@ConditionalOnProperty(prefix = "bcm.test-support", name = ["reset-enabled"], havingValue = "true")
internal class LocalEnvironmentResetController(
    private val chain: LocalStubChainState,
    private val vaults: FireblocksVaultState,
    private val transactions: FireblocksTransactionState,
    private val webhooks: LocalWebhookState,
    private val delivery: LocalWebhookDeliveryService,
    private val apiAuthentication: FireblocksApiRequestAuthenticator,
) {
    @PostMapping("/__stub/reset")
    fun reset(): LocalEnvironmentResetResponse {
        chain.reset()
        transactions.reset()
        vaults.reset()
        webhooks.reset()
        delivery.reset()
        apiAuthentication.reset()
        return LocalEnvironmentResetResponse(
            reset = listOf("STUB", "ANVIL"),
            untouched = listOf("BCM_POSTGRESQL", "BCM_KAFKA"),
        )
    }
}

@Component
@ConditionalOnProperty(prefix = "bcm.test-support", name = ["reset-enabled"], havingValue = "true")
internal class LocalEnvironmentRequestGuard {
    private val lock = ReentrantReadWriteLock(true)

    fun <T> operation(action: () -> T): T = lock.read(action)

    fun <T> reset(action: () -> T): T = lock.write(action)
}

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "bcm.test-support", name = ["reset-enabled"], havingValue = "true")
internal class LocalEnvironmentRequestGuardFilter(
    private val guard: LocalEnvironmentRequestGuard,
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.requestURI != RESET_PATH &&
            !request.requestURI.startsWith("/v1/") &&
            !request.requestURI.startsWith("/__stub/") &&
            request.requestURI != "/.well-known/jwks.json"

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val action = { filterChain.doFilter(request, response) }
        if (request.requestURI == RESET_PATH) {
            guard.reset(action)
        } else {
            guard.operation(action)
        }
    }

    companion object {
        private const val RESET_PATH = "/__stub/reset"
    }
}

internal data class LocalEnvironmentResetResponse(
    val reset: List<String>,
    val untouched: List<String>,
)
