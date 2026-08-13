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

    @Test
    fun `contract call canonical은 주소와 calldata 대소문자 및 금액 표기를 정규화한다`() {
        val fingerprint = contractCallFingerprint("100.00", "0xAbCdEf", "0x095EA7B3Aa")

        assertThat(fingerprint.hashVersion).isEqualTo("cc-v1")
        assertThat(fingerprint.normalizedAmount).isEqualTo("100")
        assertThat(fingerprint.normalizedCallData).isEqualTo("0x095ea7b3aa")
        assertThat(fingerprint.requestHash)
            .isEqualTo("8df565f666cbdfc0eb0fa45092c0abdcd664035679450e09a7c144575f13804b")
        assertThat(fingerprint)
            .isEqualTo(contractCallFingerprint("100", "0xabcdef", "0x095ea7b3aa"))
    }

    @Test
    fun `contract call calldata가 다르면 같은 external id에 재사용할 수 없는 다른 요청이다`() {
        assertThat(contractCallFingerprint("100", "0xabcdef", "0x095ea7b3aa"))
            .isNotEqualTo(contractCallFingerprint("100", "0xabcdef", "0x095ea7b3bb"))
    }

    @Test
    fun `contract call 의미 금액은 NUMERIC 36 18에 반올림 없이 저장할 수 있어야 한다`() {
        assertThatThrownBy {
            contractCallFingerprint("0.0000000000000000001", "0xabcdef", "0x095ea7b3aa")
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `contract call calldata는 비어 있지 않은 짝수 바이트 hex여야 한다`() {
        listOf("", "0x", "0x0", "0xzz").forEach { invalidCallData ->
            assertThatThrownBy {
                contractCallFingerprint("100", "0xabcdef", invalidCallData)
            }.isInstanceOf(IllegalArgumentException::class.java)
        }
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

    private fun contractCallFingerprint(
        amount: String,
        contractAddress: String,
        callData: String,
    ) = SubmissionRequestHashes.contractCallV1(
        senderAccountId = "acct_pool_02",
        contractAddress = contractAddress,
        network = "ETHEREUM",
        symbol = "USDC",
        amount = amount,
        callData = callData,
    )
}
