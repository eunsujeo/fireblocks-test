package com.whatto.bcm.domain.vendor

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** 지갑 자산 관찰의 금액 변환 계약 — 최소 단위 정수 + 같은 응답의 decimals만 사용하고 값이 바뀌지 않는다(계약13 잔액 계약). */
class NetworkWalletAssetBalanceTest {
    @Test
    fun `최소 단위 정수와 decimals로 지수 표기 없는 소수 금액을 만들고 뒤따르는 0은 제거한다`() {
        assertThat(balance("1500000", 6).amount()).isEqualTo("1.5")
        assertThat(balance("1000000", 6).amount()).isEqualTo("1")
        assertThat(balance("1", 18).amount()).isEqualTo("0.000000000000000001")
        assertThat(balance("123456789012345678901234567890", 18).amount()).isEqualTo("123456789012.34567890123456789")
        assertThat(balance("42", 0).amount()).isEqualTo("42")
    }

    @Test
    fun `0 잔액은 소수 자릿수와 무관하게 문자열 0이다`() {
        assertThat(balance("0", 6).amount()).isEqualTo("0")
        assertThat(balance("0", 0).amount()).isEqualTo("0")
    }

    @Test
    fun `정수가 아닌 잔액·음수·선행 0·범위 밖 decimals·빈 키는 관찰로 만들 수 없다`() {
        listOf("1.5", "-1", "01", "", " 1", "1e6", "abc").forEach { units ->
            assertThatThrownBy { balance(units, 6) }.describedAs(units).isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy { balance("1", -1) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThat(balance("1", 255).decimals).isEqualTo(NetworkWalletAssetBalance.MAX_DECIMALS)
        assertThatThrownBy { balance("1", 256) }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { NetworkWalletAssetBalance(" ", "USDC", 6, "1", null) }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun balance(
        units: String,
        decimals: Int,
    ) = NetworkWalletAssetBalance("EthereumSepolia:Erc20:0x0000000000000000000000000000000000000001", "USDC", decimals, units, true)
}
