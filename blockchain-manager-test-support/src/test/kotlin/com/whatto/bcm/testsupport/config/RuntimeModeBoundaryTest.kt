package com.whatto.bcm.testsupport.config

import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

class RuntimeModeBoundaryTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `허용된 세 실행 모드 조합만 시작할 수 있다`() {
        listOf(
            properties(VendorMode.STUB, ChainMode.LOCAL),
            properties(VendorMode.FIREBLOCKS, ChainMode.TESTNET),
            properties(VendorMode.FIREBLOCKS, ChainMode.MAINNET),
        ).forEach { candidate ->
            assertThatCode { RuntimeModeBoundary(candidate).validate() }.doesNotThrowAnyException()
        }
    }

    @Test
    fun `허용 목록 밖의 실행 모드 조합은 시작 전에 거부한다`() {
        listOf(
            properties(VendorMode.FIREBLOCKS, ChainMode.LOCAL),
            properties(VendorMode.STUB, ChainMode.TESTNET),
            properties(VendorMode.STUB, ChainMode.MAINNET),
        ).forEach { candidate ->
            assertThatThrownBy { RuntimeModeBoundary(candidate).validate() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("unsupported runtime mode")
        }
    }

    @Test
    fun `reset은 STUB LOCAL 조합에서만 활성화할 수 있다`() {
        assertThatCode { RuntimeModeBoundary(properties().copy(resetEnabled = true)).validate() }.doesNotThrowAnyException()

        listOf(
            properties(VendorMode.FIREBLOCKS, ChainMode.TESTNET).copy(resetEnabled = true),
            properties(VendorMode.FIREBLOCKS, ChainMode.MAINNET).copy(resetEnabled = true),
        ).forEach { candidate ->
            assertThatThrownBy { RuntimeModeBoundary(candidate).validate() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("reset requires STUB+LOCAL")
        }
    }

    @Test
    fun `STUB LOCAL은 외부 Fireblocks와 JWKS 및 EVM RPC 주소를 거부한다`() {
        listOf(
            properties().copy(fireblocksBaseUrl = "https://api.fireblocks.io"),
            properties().copy(fireblocksBaseUrl = "http://127.example.com:18080"),
            properties().copy(webhookJwksUrl = "https://keys.fireblocks.io/.well-known/jwks.json"),
            properties().copy(evmRpcUrl = "https://rpc.example.com"),
            properties().copy(evmRpcUrl = "http://fcorp.example.com:8545"),
        ).forEach { candidate ->
            assertThatThrownBy { RuntimeModeBoundary(candidate).validate() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("STUB+LOCAL")
        }
    }

    @Test
    fun `STUB LOCAL은 실 Fireblocks 자격으로 볼 수 있는 입력을 거부한다`() {
        val keyFile = temporaryDirectory.resolve("fireblocks.key")
        Files.writeString(keyFile, "local-test-key")

        listOf(
            properties().copy(fireblocksApiKey = "real-workspace-api-key"),
            properties().copy(fireblocksPrivateKeyPem = "-----BEGIN PRIVATE KEY-----real-----END PRIVATE KEY-----"),
            properties().copy(
                fireblocksPrivateKeyFile = keyFile.toString(),
                allowedLocalPrivateKeySha256 = "0".repeat(64),
            ),
        ).forEach { candidate ->
            assertThatThrownBy { RuntimeModeBoundary(candidate).validate() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("STUB+LOCAL")
        }
    }

    @Test
    fun `STUB LOCAL은 허용 fingerprint의 파일 기반 테스트 키만 받는다`() {
        val keyFile = temporaryDirectory.resolve("local-test.key")
        val bytes = "local-test-key".toByteArray()
        Files.write(keyFile, bytes)

        val candidate =
            properties().copy(
                fireblocksPrivateKeyFile = keyFile.toString(),
                allowedLocalPrivateKeySha256 = sha256Hex(bytes),
            )

        assertThatCode { RuntimeModeBoundary(candidate).validate() }.doesNotThrowAnyException()
    }

    @Test
    fun `STUB LOCAL은 내부 bind 주소와 고정 로컬 chain id만 받는다`() {
        listOf(
            properties().copy(serverAddress = "0.0.0.0"),
            properties().copy(managementAddress = "203.0.113.10"),
            properties().copy(evmChainId = 1),
        ).forEach { candidate ->
            assertThatThrownBy { RuntimeModeBoundary(candidate).validate() }
                .isInstanceOf(IllegalStateException::class.java)
                .hasMessageContaining("STUB+LOCAL")
        }
    }

    private fun properties(
        vendorMode: VendorMode = VendorMode.STUB,
        chainMode: ChainMode = ChainMode.LOCAL,
    ) = TestSupportProperties(
        vendorMode = vendorMode,
        chainMode = chainMode,
        serverAddress = "127.0.0.1",
        managementAddress = "127.0.0.1",
        fireblocksBaseUrl = "http://127.0.0.1:18080",
        fireblocksApiKey = "bcm-local-stub",
        webhookJwksUrl = "http://127.0.0.1:18080/.well-known/jwks.json",
        evmRpcUrl = "http://127.0.0.1:8545",
        evmChainId = 31337,
    )

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
