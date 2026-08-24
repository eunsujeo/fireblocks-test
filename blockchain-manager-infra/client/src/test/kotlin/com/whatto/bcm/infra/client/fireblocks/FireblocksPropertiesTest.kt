package com.whatto.bcm.infra.client.fireblocks

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FireblocksPropertiesTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `일반 API와 JWKS 주소를 기본값으로 사용한다`() {
        val properties = FireblocksProperties()

        assertThat(properties.baseUrl).isEqualTo("https://api.fireblocks.io")
        assertThat(properties.webhookJwksUrl).isEqualTo("https://keys.fireblocks.io/.well-known/jwks.json")
    }

    @Test
    fun `private key 파일을 지정하면 PEM 본문을 읽는다`() {
        val keyFile = tempDir.resolve("fireblocks-api-private-key.pem")
        Files.writeString(keyFile, "private-key-pem")

        val properties = FireblocksProperties(privateKeyFile = keyFile.toString())

        assertThat(properties.resolvePrivateKeyPem()).isEqualTo("private-key-pem")
    }

    @Test
    fun `PEM 본문과 private key 파일을 함께 지정하면 거부한다`() {
        assertThatThrownBy {
            FireblocksProperties(
                privateKeyPem = "private-key-pem",
                privateKeyFile = tempDir.resolve("fireblocks-api-private-key.pem").toString(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `private key 파일을 읽을 수 없으면 설정 오류로 실패한다`() {
        val properties = FireblocksProperties(privateKeyFile = tempDir.resolve("missing.pem").toString())

        assertThatThrownBy { properties.resolvePrivateKeyPem() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("bcm.fireblocks.private-key-file을 읽을 수 없습니다")
    }
}
