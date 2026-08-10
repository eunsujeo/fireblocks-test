package com.whatto.bcm.app.api.web

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * 요청 추적 id 발급 — 모든 응답의 meta.requestId 와 로그(MDC)를 같은 값으로 잇는다.
 */
@Component
class RequestIdFilter : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val requestId = UUID.randomUUID().toString()
        request.setAttribute(ATTRIBUTE, requestId)
        MDC.put(MDC_KEY, requestId)
        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove(MDC_KEY)
        }
    }

    companion object {
        private const val ATTRIBUTE = "com.whatto.bcm.requestId"
        private const val MDC_KEY = "requestId"

        /** 필터가 심은 requestId — 미배선이면 조기 실패로 배선 버그를 드러낸다. */
        fun requestIdOf(request: HttpServletRequest): String =
            request.getAttribute(ATTRIBUTE) as? String
                ?: error("RequestIdFilter 미배선 — requestId 요청 속성이 없다")
    }
}
