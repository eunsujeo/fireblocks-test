package com.whatto.bcm.infra.persistence.fee

import com.whatto.bcm.domain.fee.NetworkFeeQuote
import com.whatto.bcm.domain.vendor.VendorFeeLevel
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import

@DataJdbcTest
@Import(NetworkFeeQuoteJdbcAdapter::class)
class NetworkFeeQuotePersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var quotes: NetworkFeeQuoteJdbcAdapter

    @Test
    fun `같은 관측 시각의 세 fee level은 한 번만 저장된다`() {
        val observed =
            VendorFeeLevel.entries.map { level ->
                quote(level = level, gasPrice = "${level.ordinal + 1}.1")
            }

        assertThat(quotes.saveAll(observed)).isEqualTo(3)
        assertThat(quotes.saveAll(observed)).isZero()
        assertThat(quotes.count()).isEqualTo(3)
    }

    @Test
    fun `제출 시각 이하의 가장 최근 견적만 대응하고 미래 견적은 사용하지 않는다`() {
        quotes.saveAll(
            listOf(
                quote(observedAt = "20260813115900", gasPrice = "1.0"),
                quote(observedAt = "20260813120000", gasPrice = "2.0"),
                quote(observedAt = "20260813120100", gasPrice = "3.0"),
                quote(symbol = "ETH", observedAt = "20260813120000", gasPrice = "9.0"),
            ),
        )

        val matched = quotes.findLatestAtOrBefore("ETHEREUM", "USDC", VendorFeeLevel.MEDIUM, "20260813120030")
        assertThat(matched?.observedAt).isEqualTo("20260813120000")
        assertThat(matched?.feeLevel).isEqualTo(VendorFeeLevel.MEDIUM)
        assertThat(matched?.gasPrice).isEqualByComparingTo("2.0")
        assertThat(quotes.findLatestAtOrBefore("ETHEREUM", "USDC", VendorFeeLevel.MEDIUM, "20260813115859"))
            .isNull()
    }

    private fun quote(
        network: String = "ETHEREUM",
        symbol: String = "USDC",
        observedAt: String = "20260813120000",
        level: VendorFeeLevel = VendorFeeLevel.MEDIUM,
        gasPrice: String = "2.2",
    ) = NetworkFeeQuote(
        network = network,
        symbol = symbol,
        observedAt = observedAt,
        feeLevel = level,
        vendorAssetId = "${symbol}_ERC20",
        feePerByte = null,
        gasPrice = gasPrice.toBigDecimal(),
        networkFee = null,
        baseFee = null,
        priorityFee = null,
    )
}
