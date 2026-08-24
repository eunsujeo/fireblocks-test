package com.whatto.bcm.app.api.admin

import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class AdminApiAccessFilterTest {
    @Test
    fun `기능 테스트 Admin API는 loopback 호출만 통과시킨다`() {
        val filter = AdminApiAccessFilter(AdminApiAccessProperties())
        val request = MockHttpServletRequest("GET", "/admin/networks").apply { remoteAddr = "127.0.0.1" }
        val response = MockHttpServletResponse()
        var called = false

        filter.doFilter(request, response, FilterChain { _, _ -> called = true })

        assertThat(called).isTrue()
        assertThat(response.status).isEqualTo(200)
    }

    @Test
    fun `외부 호출은 직원 헤더가 있어도 Admin API에 접근하지 못한다`() {
        val filter = AdminApiAccessFilter(AdminApiAccessProperties())
        val request =
            MockHttpServletRequest("POST", "/admin/asset-mappings").apply {
                remoteAddr = "10.20.30.40"
                addHeader("X-Employee-No", "123456")
                addHeader("X-Branch-Code", "0001")
            }
        val response = MockHttpServletResponse()
        var called = false

        filter.doFilter(request, response, FilterChain { _, _ -> called = true })

        assertThat(called).isFalse()
        assertThat(response.status).isEqualTo(403)
    }

    @Test
    fun `일반 업무 API에는 Admin 접근 필터를 적용하지 않는다`() {
        val filter = AdminApiAccessFilter(AdminApiAccessProperties())
        val request = MockHttpServletRequest("POST", "/transactions").apply { remoteAddr = "10.20.30.40" }
        val response = MockHttpServletResponse()
        var called = false

        filter.doFilter(request, response, FilterChain { _, _ -> called = true })

        assertThat(called).isTrue()
    }

    @Test
    fun `공유 Admin API 모드는 mTLS JWT 구현 전 시작을 거절한다`() {
        assertThatThrownBy { AdminApiAccessProperties(mode = "SHARED").validate() }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatCode { AdminApiAccessProperties().validate() }.doesNotThrowAnyException()
    }
}
