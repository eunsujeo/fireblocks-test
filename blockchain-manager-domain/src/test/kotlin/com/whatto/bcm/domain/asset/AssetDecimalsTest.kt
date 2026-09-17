package com.whatto.bcm.domain.asset

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** 정밀도 상한과 최소 단위 환산의 공통 규칙(03 V27·07). */
class AssetDecimalsTest {
    @Test
    fun `모델링한 자산의 상한은 255이고 그 밖은 정밀도로 받지 않는다`() {
        listOf(0, 6, 18, 255).forEach { assertThat(AssetDecimals.isValid(it)).describedAs("$it").isTrue() }
        listOf(-1, 256, 1_000).forEach { assertThat(AssetDecimals.isValid(it)).describedAs("$it").isFalse() }
    }

    @Test
    fun `최소 단위 정수를 사람 단위 금액으로 지수 없이 옮긴다`() {
        assertThat(AssetDecimals.amountOf("1500000", 6)).isEqualTo("1.5")
        assertThat(AssetDecimals.amountOf("1000000", 6)).isEqualTo("1")
        assertThat(AssetDecimals.amountOf("1", 18)).isEqualTo("0.000000000000000001")
        assertThat(AssetDecimals.amountOf("0", 6)).isEqualTo("0")
        assertThat(AssetDecimals.amountOf("123", 0)).isEqualTo("123")
        // 큰 값도 지수 표기로 바뀌지 않는다 — 이벤트 금액은 문자열 그대로 소비된다.
        assertThat(AssetDecimals.amountOf("100000000000000000000000000", 18)).isEqualTo("100000000")
    }

    @Test
    fun `선행 0이나 정수가 아닌 표기와 범위 밖 정밀도는 환산하지 않는다`() {
        listOf("01", "1.5", "-1", " 1", "", "0x1").forEach { baseUnits ->
            assertThatThrownBy { AssetDecimals.amountOf(baseUnits, 6) }
                .describedAs(baseUnits)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("base units")
        }
        assertThatThrownBy { AssetDecimals.amountOf("1", 256) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("decimals")
    }

    @Test
    fun `등록 매핑은 범위 밖 정밀도를 저장값으로 받지 않는다`() {
        assertThat(mapping(6).decimals).isEqualTo(6)
        // 정밀도를 저장하기 전에 등록된 기존 매핑은 null이다.
        assertThat(mapping(null).decimals).isNull()
        listOf(-1, 256).forEach { decimals ->
            assertThatThrownBy { mapping(decimals) }
                .describedAs("$decimals")
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("decimals")
        }
    }

    private fun mapping(decimals: Int?) =
        VendorAssetMapping(
            network = "ETHEREUM_SEPOLIA",
            symbol = "USDC",
            vendorAssetId = "EthereumSepolia:Native",
            contractAddress = null,
            registeredAt = "20260916000000",
            registeredByEmployeeNo = "123456",
            registeredByBranchCode = "0001",
            decimals = decimals,
        )

    @Test
    fun `사람 단위 금액은 등록 정밀도로 최소 단위 정수가 되고 왕복해도 값이 변하지 않는다`() {
        assertThat(AssetDecimals.baseUnitsOf("1", 6)).isEqualTo("1000000")
        assertThat(AssetDecimals.baseUnitsOf("1.5", 6)).isEqualTo("1500000")
        assertThat(AssetDecimals.baseUnitsOf("0.000001", 6)).isEqualTo("1")
        assertThat(AssetDecimals.baseUnitsOf("0", 6)).isEqualTo("0")
        assertThat(AssetDecimals.baseUnitsOf("12", 0)).isEqualTo("12")
        // 표기가 달라도 같은 금액이면 같은 최소 단위다 — 회수의 "같은 본문"이 표기 차이로 어긋나지 않는다.
        assertThat(AssetDecimals.baseUnitsOf("1.500", 6)).isEqualTo("1500000")
        listOf("1", "1.5", "0.000001").forEach { amount ->
            assertThat(AssetDecimals.amountOf(AssetDecimals.baseUnitsOf(amount, 6), 6))
                .describedAs(amount)
                .isEqualTo(
                    java.math
                        .BigDecimal(amount)
                        .stripTrailingZeros()
                        .toPlainString(),
                )
        }
    }

    @Test
    fun `정밀도보다 자릿수가 많거나 음수인 금액은 반올림하지 않고 거절한다`() {
        assertThatThrownBy { AssetDecimals.baseUnitsOf("1.0000001", 6) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AssetDecimals.baseUnitsOf("0.1", 0) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AssetDecimals.baseUnitsOf("-1", 6) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AssetDecimals.baseUnitsOf("abc", 6) }
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { AssetDecimals.baseUnitsOf("1", 256) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
