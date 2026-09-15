package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.infra.client.dfns.fixture.DfnsTestKeyFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * Key credential 서명 계약 — 공식 "Credentials data"의 clientData 형식(알파벳순 키·공백 없는 구분자·base64url)과
 * 공식 서명 흐름의 기본 서명(EC SHA-256/DER·RSA SHA-256·Ed25519)을 고정한다. 실키·실호출 없음.
 */
class DfnsCredentialSignerTest {
    @Test
    fun `clientData는 challenge와 key_get 타입만 알파벳순·공백 없이 담고 base64url 패딩 없이 인코딩한다`() {
        val signer = DfnsCredentialSigner(DfnsTestKeyFixture.CREDENTIAL_ID, DfnsTestKeyFixture.pem(DfnsTestKeyFixture.ecKeyPair()))

        val assertion = signer.sign("Y2gtNzloaHQtbXJlb2stOGFwOHFtMmVpZWZ0amxhZw")

        assertThat(assertion.credId).isEqualTo(DfnsTestKeyFixture.CREDENTIAL_ID)
        assertThat(assertion.clientData).doesNotContain("=", "+", "/")
        assertThat(String(Base64.getUrlDecoder().decode(assertion.clientData)))
            .isEqualTo("""{"challenge":"Y2gtNzloaHQtbXJlb2stOGFwOHFtMmVpZWZ0amxhZw","type":"key.get"}""")
        assertThat(assertion.signature).doesNotContain("=", "+", "/")
    }

    @Test
    fun `EC·RSA·Ed25519 개인키의 서명은 같은 clientData 바이트에 대해 공개키로 검증된다`() {
        listOf(DfnsTestKeyFixture.ecKeyPair(), DfnsTestKeyFixture.rsaKeyPair(), DfnsTestKeyFixture.ed25519KeyPair()).forEach { keyPair ->
            val signer = DfnsCredentialSigner(DfnsTestKeyFixture.CREDENTIAL_ID, DfnsTestKeyFixture.pem(keyPair))

            val assertion = signer.sign("challenge-token")

            assertThat(DfnsTestKeyFixture.verifies(keyPair, assertion.clientData, assertion.signature))
                .describedAs(keyPair.public.algorithm)
                .isTrue()
            assertThat(DfnsTestKeyFixture.verifies(keyPair, assertion.clientData, signer.sign("other-challenge").signature)).isFalse()
        }
    }

    @Test
    fun `challenge에 JSON을 깨뜨리는 문자가 있으면 서명하지 않는다`() {
        val signer = DfnsCredentialSigner(DfnsTestKeyFixture.CREDENTIAL_ID, DfnsTestKeyFixture.pem(DfnsTestKeyFixture.ecKeyPair()))
        listOf("", " ", "a\"b", "a\\b", "a b").forEach { challenge ->
            assertThatThrownBy { signer.sign(challenge) }.isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `개인키 미설정·credential ID 공백은 생성 시점에 거절한다`() {
        assertThatThrownBy { DfnsCredentialSigner(DfnsTestKeyFixture.CREDENTIAL_ID, "") }.isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { DfnsCredentialSigner(" ", DfnsTestKeyFixture.pem(DfnsTestKeyFixture.ecKeyPair())) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
