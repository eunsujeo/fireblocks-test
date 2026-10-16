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
    fun `가변 폭 10진 길이가 만들던 실제 충돌을 가른다`() {
        // 옛 인코딩(10진 길이 + 값)에서 이 둘은 **같은 바이트열**을 만들었다:
        //   A → "1" + "2"            + "20" + "aaaaaaaaaa9bbbbbbbbb"
        //   B → "12" + "20aaaaaaaaaa" + "9"  + "bbbbbbbbb"
        // 둘 다 "1220aaaaaaaaaa9bbbbbbbbb" 다. 다른 관찰이 남의 격리 행에 합쳐진다는 뜻이다.
        assertThat(fingerprint(recorded = "2", observed = "aaaaaaaaaa9bbbbbbbbb"))
            .isNotEqualTo(fingerprint(recorded = "20aaaaaaaaaa", observed = "bbbbbbbbb"))
    }

    @Test
    fun `여러 바이트 문자는 UTF-16 문자 수가 아니라 바이트 길이로 센다`() {
        // "가"는 UTF-16 1자이지만 UTF-8 3바이트다. 길이를 문자 수로 적으면 선언한 길이와 실제 바이트가 어긋난다.
        // 기대 지문을 **따로 계산해** 대조한다 — 구현을 그대로 옮겨 적으면 같이 틀린다.
        val expected =
            sha256(
                bigEndian(7) + "tx-root".toByteArray() +
                    bigEndian(11) + "tx-physical".toByteArray() +
                    bigEndian(6) + "amount".toByteArray() +
                    bigEndian(3) + "가".toByteArray() +
                    bigEndian(1) + "x".toByteArray() +
                    bigEndian(2) + "[]".toByteArray(),
            )

        assertThat(fingerprint(recorded = "가", observed = "x", body = "[]")).isEqualTo(expected)
    }

    private fun sha256(bytes: ByteArray) =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun bigEndian(value: Int) =
        byteArrayOf((value ushr 24).toByte(), (value ushr 16).toByte(), (value ushr 8).toByte(), value.toByte())

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
