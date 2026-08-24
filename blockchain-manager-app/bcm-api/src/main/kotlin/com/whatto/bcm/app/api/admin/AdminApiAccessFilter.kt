package com.whatto.bcm.app.api.admin

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.InitializingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.net.InetAddress

@ConfigurationProperties("bcm.admin-api.access")
data class AdminApiAccessProperties(
    val mode: String = FUNCTION_TEST,
) : InitializingBean {
    override fun afterPropertiesSet() = validate()

    fun validate() {
        check(mode == FUNCTION_TEST) { "shared Admin API requires mTLS and short-lived JWT before it can start" }
    }

    companion object {
        const val FUNCTION_TEST = "FUNCTION_TEST"
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminApiAccessProperties::class)
class AdminApiAccessConfig

@Component
class AdminApiAccessFilter(
    private val properties: AdminApiAccessProperties = AdminApiAccessProperties(),
) : OncePerRequestFilter() {
    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.startsWith("/admin/")

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        properties.validate()
        if (!request.remoteAddr.isLoopbackAddress()) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN)
            return
        }
        filterChain.doFilter(request, response)
    }

    private fun String.isLoopbackAddress(): Boolean = runCatching { InetAddress.getByName(this).isLoopbackAddress }.getOrDefault(false)
}
