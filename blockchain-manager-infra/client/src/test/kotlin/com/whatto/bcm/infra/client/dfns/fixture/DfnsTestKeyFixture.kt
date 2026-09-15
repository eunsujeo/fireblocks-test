package com.whatto.bcm.infra.client.dfns.fixture

import com.whatto.bcm.infra.client.fireblocks.fixture.TestRsaKeyFixture
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/** 테스트 전용 일회용 credential 키 — 매 실행 새로 생성하며 실키를 담지 않는다. PEM 변환은 기존 fixture를 재사용한다. */
object DfnsTestKeyFixture {
    const val CREDENTIAL_ID = "cr-test0-test0-test0test0test0"

    fun ecKeyPair(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    fun ed25519KeyPair(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()

    fun rsaKeyPair(): KeyPair = TestRsaKeyFixture.generateKeyPair()

    fun pem(keyPair: KeyPair): String = TestRsaKeyFixture.toPkcs8Pem(keyPair)

    fun verifies(
        keyPair: KeyPair,
        clientDataBase64Url: String,
        signatureBase64Url: String,
    ): Boolean {
        val algorithm =
            when (keyPair.public.algorithm) {
                "EC" -> "SHA256withECDSA"
                "RSA" -> "SHA256withRSA"
                else -> "Ed25519"
            }
        return Signature.getInstance(algorithm).run {
            initVerify(keyPair.public)
            update(Base64.getUrlDecoder().decode(clientDataBase64Url))
            verify(Base64.getUrlDecoder().decode(signatureBase64Url))
        }
    }
}
