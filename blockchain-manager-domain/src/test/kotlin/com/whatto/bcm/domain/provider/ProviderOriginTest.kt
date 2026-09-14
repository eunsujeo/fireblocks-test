package com.whatto.bcm.domain.provider

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ProviderOriginTest {
    private fun fixture() = ProviderOrigin("origin-test", "fireblocks", "fireblocks", "instance-test", "organization-test", "TESTNET")

    @Test
    fun `모든 원천 필드가 같아야 실행을 허용한다`() {
        val expected = fixture()
        assertThatCode { expected.requireMatch(fixture()) }.doesNotThrowAnyException()
        listOf(
            expected.copy(originId = "another-origin"),
            expected.copy(platformInstanceId = "another-instance"),
            expected.copy(vendorOrganizationId = "another-organization"),
            expected.copy(chainMode = "MAINNET"),
            expected.copy(executionMode = "dfns", protocolProvider = "dfns"),
            expected.copy(executionMode = "local", chainMode = "LOCAL"),
        ).forEach { stored ->
            assertThatThrownBy { expected.requireMatch(stored) }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("Provider origin mismatch")
        }
    }

    @Test
    fun `원천 미등록은 현재 설정으로 대체하지 않는다`() {
        assertThatThrownBy { fixture().requireMatch(null) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Provider origin binding is missing")
    }

    @Test
    fun `빈 식별자와 공백 및 잘못된 제공자 환경 조합을 거절한다`() {
        val valid = fixture()
        listOf("", " ", " origin", "origin\t", "x".repeat(65)).forEach { id ->
            assertThatThrownBy { valid.copy(originId = id) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { valid.copy(platformInstanceId = id) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { valid.copy(vendorOrganizationId = id) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy { valid.copy(protocolProvider = "dfns") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { valid.copy(chainMode = "LOCAL") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { valid.copy(executionMode = "local") }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
