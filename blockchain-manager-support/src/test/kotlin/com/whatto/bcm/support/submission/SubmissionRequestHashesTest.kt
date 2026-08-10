package com.whatto.bcm.support.submission

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SubmissionRequestHashesTest {
    @Test
    fun `canonical v1은 고정 7줄을 SHA-256 소문자 hex로 만든다`() {
        val fingerprint = fingerprint(amount = "1.5")

        assertThat(fingerprint.hashVersion).isEqualTo("v1")
        assertThat(fingerprint.normalizedAmount).isEqualTo("1.5")
        assertThat(fingerprint.requestHash)
            .isEqualTo("8bcc7c3cc9412d5a57957a0a5d211fd8568db04b71a49a2a8588a3b7fbb7d2ef")
    }

    @Test
    fun `금액 표기만 다른 1점50과 1점5는 같은 요청이다`() {
        assertThat(fingerprint(amount = "1.50"))
            .isEqualTo(fingerprint(amount = "1.5"))
    }

    @Test
    fun `금액 외 값은 대소문자를 바꾸지 않아 다른 원문이면 다른 해시다`() {
        assertThat(fingerprint(network = "ethereum"))
            .isNotEqualTo(fingerprint(network = "ETHEREUM"))
    }

    @Test
    fun `숫자가 아닌 금액은 해시를 만들지 않는다`() {
        assertThatThrownBy { fingerprint(amount = "not-a-number") }
            .isInstanceOf(NumberFormatException::class.java)
    }

    private fun fingerprint(
        network: String = "ETHEREUM",
        amount: String = "1.5",
    ) = SubmissionRequestHashes.v1(
        senderType = "ACCOUNT",
        senderAccountId = "acct_pool_02",
        recipientType = "ADDRESS",
        recipientValue = "0x9fE2",
        network = network,
        symbol = "USDC",
        amount = amount,
    )
}
