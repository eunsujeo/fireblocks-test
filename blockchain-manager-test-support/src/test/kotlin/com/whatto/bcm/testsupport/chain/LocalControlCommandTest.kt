package com.whatto.bcm.testsupport.chain

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

class LocalControlCommandTest {
    @TempDir
    lateinit var temporaryDirectory: Path

    private var server: HttpServer? = null

    @AfterEach
    fun stopServer() {
        server?.stop(0)
    }

    @Test
    fun `health chain은 loopback chain id와 bootstrap 파일을 함께 확인한다`() {
        val http =
            startServer { exchange ->
                exchange.requestBody.readAllBytes()
                val body = """{"jsonrpc":"2.0","id":1,"result":"0x7a69"}"""
                exchange.sendResponseHeaders(200, body.length.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }
        val manifest = Files.writeString(temporaryDirectory.resolve("manifest.json"), "{}")
        val keys = Files.writeString(temporaryDirectory.resolve("evm-keys.json"), "{}")

        val result =
            LocalControlCommand.execute(
                "health-chain",
                mapOf(
                    "BCM_EVM_RPC_URL" to "http://127.0.0.1:${http.address.port}",
                    "BCM_LOCAL_CHAIN_MANIFEST_FILE" to manifest.toString(),
                    "BCM_LOCAL_CHAIN_KEY_FILE" to keys.toString(),
                ),
            )

        assertThat(result).isEqualTo("CHAIN_UP")
    }

    @Test
    fun `reset은 loopback Stub의 제어 endpoint에 POST 한 번만 보낸다`() {
        val observed = AtomicReference<String>()
        val http =
            startServer { exchange ->
                observed.set("${exchange.requestMethod} ${exchange.requestURI}")
                val body = """{"reset":["STUB","ANVIL"]}"""
                exchange.sendResponseHeaders(200, body.length.toLong())
                exchange.responseBody.use { it.write(body.toByteArray()) }
            }

        val result =
            LocalControlCommand.execute(
                "reset",
                mapOf("BCM_FIREBLOCKS_BASE_URL" to "http://127.0.0.1:${http.address.port}"),
            )

        assertThat(result).contains("STUB", "ANVIL")
        assertThat(observed.get()).isEqualTo("POST /__stub/reset")
    }

    @Test
    fun `제어 명령은 외부 endpoint를 거부한다`() {
        assertThatThrownBy {
            LocalControlCommand.execute(
                "reset",
                mapOf("BCM_FIREBLOCKS_BASE_URL" to "https://api.fireblocks.io"),
            )
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("loopback")
    }

    private fun startServer(handler: com.sun.net.httpserver.HttpHandler): HttpServer =
        HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { http ->
            http.createContext("/", handler)
            http.start()
            server = http
        }
}
