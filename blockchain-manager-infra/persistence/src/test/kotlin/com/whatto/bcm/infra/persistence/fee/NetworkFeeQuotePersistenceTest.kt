package com.whatto.bcm.infra.persistence.fee

import com.whatto.bcm.domain.fee.NetworkFeeQuote
import com.whatto.bcm.domain.vendor.VendorFeeLevel
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate

@DataJdbcTest
@Import(NetworkFeeQuoteJdbcAdapter::class)
class NetworkFeeQuotePersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var quotes: NetworkFeeQuoteJdbcAdapter

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Test
    fun `같은 관측 시각의 세 fee level은 한 번만 저장된다`() {
        val observed =
            VendorFeeLevel.entries.map { level ->
                quote(level = level, gasPrice = "${level.ordinal + 1}.1")
            }

        assertThat(quotes.saveAll(observed)).isEqualTo(3)
        assertThat(quotes.saveAll(observed)).isZero()
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM bcm_fee_qt_l", Int::class.java)).isEqualTo(3)
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

    @Test
    fun `일반 제출은 MEDIUM을 boost는 저장된 fee level을 요청 시각 기준으로 대응한다`() {
        quotes.saveAll(
            listOf(
                quote(observedAt = "20260813115900", level = VendorFeeLevel.MEDIUM, gasPrice = "2.0"),
                quote(observedAt = "20260813115900", level = VendorFeeLevel.HIGH, gasPrice = "3.0"),
                quote(observedAt = "20260813120100", level = VendorFeeLevel.MEDIUM, gasPrice = "20.0"),
                quote(observedAt = "20260813120100", level = VendorFeeLevel.HIGH, gasPrice = "30.0"),
            ),
        )
        insertSubmission()
        insertRootAndBoost()

        val submissionQuote = quotes.findForSubmission("wd-1")
        val boostQuote = quotes.findForBoost("tx-root", 1)

        assertThat(submissionQuote?.feeLevel).isEqualTo(VendorFeeLevel.MEDIUM)
        assertThat(submissionQuote?.gasPrice).isEqualByComparingTo("2.0")
        assertThat(boostQuote?.feeLevel).isEqualTo(VendorFeeLevel.HIGH)
        assertThat(boostQuote?.gasPrice).isEqualByComparingTo("3.0")
    }

    private fun insertSubmission() {
        jdbc.update(
            """
            INSERT INTO bcm_sbmt_l
              (ext_tx_id, req_hash, hash_vrsn, sbmt_stcd, tx_dvcd,
               snd_acnt_id, rcv_dvcd, rcv_vl, ntwk_cd, tkn_smbl, trsf_amt, req_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              ('wd-1', '${"a".repeat(64)}', 'v1', 'SUBMITTED', 'WITHDRAWAL',
               'acct-1', 'ADDRESS', '0xdest', 'ETHEREUM', 'USDC', 1, '20260813120000',
               'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
    }

    private fun insertRootAndBoost() {
        jdbc.update(
            """
            INSERT INTO bcm_tx_l
              (vndr_tx_id, actv_tx_id, acnt_id, ntwk_cd, tkn_smbl, last_pub_stcd, cnfm_cnt,
               frst_dtct_dttm, last_chng_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              ('tx-root', 'tx-root', 'acct-1', 'ETHEREUM', 'USDC', 'SUBMITTED', 0,
               '20260813110000', '20260813110000',
               'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO bcm_boost_l
              (orig_tx_id, try_seq, ext_tx_id, bst_stcd, rplc_tx_id, rplc_tx_hash,
               fee_lvl, gasless_yn, req_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              ('tx-root', 1, 'bst-1', 'REQUESTED', 'tx-root', '0xstuck',
               'HIGH', 'Y', '20260813120000',
               'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
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
