package com.whatto.bcm.admin.config

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AdminExposureBoundaryTest {
    @Test
    fun `기능 테스트 Admin은 loopback frontend와 BCM만 허용한다`() {
        assertThatCode {
            AdminExposureBoundary(AdminProperties(), "127.0.0.1").afterPropertiesSet()
        }.doesNotThrowAnyException()
    }

    @Test
    fun `기능 테스트 Admin의 외부 바인딩이나 외부 BCM 대상은 시작을 거절한다`() {
        assertThatThrownBy {
            AdminExposureBoundary(AdminProperties(), "0.0.0.0").afterPropertiesSet()
        }.isInstanceOf(IllegalStateException::class.java)

        assertThatThrownBy {
            AdminExposureBoundary(AdminProperties(targetBaseUrl = "https://bcm.example.com"), "127.0.0.1")
                .afterPropertiesSet()
        }.isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `공유 모드는 mTLS JWT 경계 구현 전 시작을 거절한다`() {
        assertThatThrownBy {
            AdminExposureBoundary(AdminProperties(mode = "SHARED"), "127.0.0.1").afterPropertiesSet()
        }.isInstanceOf(IllegalStateException::class.java)
    }
}
