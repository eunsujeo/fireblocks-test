package com.whatto.bcm.domain.tx

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 지문은 "같은 관찰"을 가른다. 다른 관찰이 같은 지문을 내면 **남의 격리 행에 합쳐져 증적을 잃는다**(03 V37).
 */
class TxObservationQuarantineFingerprintTest {
    @Test
    fun `값의 경계가 다르면 지문도 다르다`() {
        // 이어 붙이기만 하면 ("a","bc") 와 ("ab","c") 가 같은 입력열이 된다.
        assertThat(fingerprint(recorded = "a", observed = "bc"))
            .isNotEqualTo(fingerprint(recorded = "ab", observed = "c"))
    }

    @Test
    fun `길이 접두사가 값과 섞여도 지문이 겹치지 않는다`() {
        // 10진 길이는 폭이 들쭉날쭉해, 값이 숫자로 시작하면 접두사와 구분되지 않는다.
        assertThat(fingerprint(recorded = "1", observed = "123"))
            .isNotEqualTo(fingerprint(recorded = "11", observed = "23"))
    }

    @Test
    fun `여러 바이트 문자도 바이트 길이로 센다`() {
        // String.length 는 UTF-16 문자 수라 뒤에 붙는 UTF-8 바이트 수와 다르다.
        assertThat(fingerprint(recorded = "é", observed = "x"))
            .isNotEqualTo(fingerprint(recorded = "e", observed = "x"))
        assertThat(fingerprint(recorded = "가", observed = ""))
            .isNotEqualTo(fingerprint(recorded = "", observed = "가"))
    }

    @Test
    fun `원문이 다르면 다른 관찰이다`() {
        assertThat(fingerprint(body = "[1]")).isNotEqualTo(fingerprint(body = "[2]"))
    }

    @Test
    fun `같은 입력은 같은 지문이다`() {
        assertThat(fingerprint()).isEqualTo(fingerprint())
    }

    private fun fingerprint(
        root: String = "tx-root",
        observedTransactionId: String = "tx-physical",
        field: String = "amount",
        recorded: String = "1.5",
        observed: String = "2.5",
        body: String = """[{"amount":"2.5"}]""",
    ) = TxObservationQuarantineFingerprint.of(
        rootVendorTransactionId = root,
        observedVendorTransactionId = observedTransactionId,
        field = field,
        recordedValue = recorded,
        observedValue = observed,
        body = body.toByteArray(),
    )
}
