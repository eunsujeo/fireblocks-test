package com.whatto.bcm.infra.client.fireblocks.fixture

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.util.Base64

/**
 * 테스트 전용 일회용 RSA 키 — 매 실행 새로 생성하며 어떤 실키도 담지 않는다.
 * PEM 헤더 리터럴이 gitleaks private-key 룰에 오탐되므로 이 파일만 .gitleaks.toml allowlist 에 등재돼 있다 —
 * 실키/고정 키 문자열은 여기에도 절대 넣지 않는다 (CLAUDE.md 0절).
 */
object TestRsaKeyFixture {
    fun generateKeyPair(): KeyPair =
        KeyPairGenerator
            .getInstance("RSA")
            .apply { initialize(2048) }
            .generateKeyPair()

    fun toPkcs8Pem(keyPair: KeyPair): String =
        buildString {
            appendLine("-----BEGIN PRIVATE KEY-----")
            appendLine(Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded))
            appendLine("-----END PRIVATE KEY-----")
        }
}
