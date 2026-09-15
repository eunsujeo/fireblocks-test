package com.whatto.bcm.domain.wallet

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** 계약13 — 지갑 주소를 토큰 수신 주소로 쓰는 네트워크는 운영 정책이 명시한 것만이며 코드가 추정하지 않는다. */
class NetworkWalletAddressPolicyTest {
    @Test
    fun `명시된 네트워크만 지갑 주소를 토큰 수신 주소로 허용한다`() {
        val policy = NetworkWalletAddressPolicy(setOf("ETHEREUM_SEPOLIA"), 5)

        assertThat(policy.allowsAccountAddress("ETHEREUM_SEPOLIA")).isTrue()
        assertThat(policy.allowsAccountAddress("SOLANA_DEVNET")).isFalse()
        assertThat(NetworkWalletAddressPolicy(emptySet(), 5).allowsAccountAddress("ETHEREUM_SEPOLIA")).isFalse()
    }

    @Test
    fun `빈 네트워크 코드와 1초 미만 재시도 안내는 거절한다`() {
        assertThatThrownBy { NetworkWalletAddressPolicy(setOf(" "), 5) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { NetworkWalletAddressPolicy(setOf("ETHEREUM_SEPOLIA"), 0) }.isInstanceOf(IllegalArgumentException::class.java)
    }
}
