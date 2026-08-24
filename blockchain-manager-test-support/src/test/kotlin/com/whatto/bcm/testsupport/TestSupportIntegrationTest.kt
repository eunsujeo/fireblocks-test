package com.whatto.bcm.testsupport

import com.whatto.bcm.domain.monitoring.NoOpOperationalMetricsPort
import com.whatto.bcm.infra.client.fireblocks.FireblocksClient
import com.whatto.bcm.infra.client.fireblocks.FireblocksJwtSigner
import com.whatto.bcm.infra.client.fireblocks.FireblocksProperties
import com.whatto.bcm.infra.client.fireblocks.PooledFireblocksRestClientFactory
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalManagementPort
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.web.client.RestClient
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyPairGenerator
import java.time.Clock
import java.util.Base64

@SpringBootTest(
    classes = [TestSupportApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "management.server.port=0",
        "bcm.test-support.vendor-mode=STUB",
        "bcm.test-support.chain-mode=LOCAL",
        "bcm.test-support.server-address=127.0.0.1",
        "bcm.test-support.management-address=127.0.0.1",
        "bcm.test-support.fireblocks-base-url=http://127.0.0.1:18080",
        "bcm.test-support.fireblocks-api-key=bcm-local-stub",
        "bcm.test-support.webhook-jwks-url=http://127.0.0.1:18080/.well-known/jwks.json",
        "bcm.test-support.evm-rpc-url=http://127.0.0.1:8545",
        "bcm.test-support.evm-chain-id=31337",
    ],
)
class TestSupportIntegrationTest {
    @LocalServerPort
    var serverPort: Int = 0

    @LocalManagementPort
    var managementPort: Int = 0

    @Test
    fun `독립 test-support 프로세스는 내부 health endpoint를 제공한다`() {
        val response = get("http://127.0.0.1:$managementPort/actuator/health")

        assertThat(response.statusCode()).isEqualTo(200)
        assertThat(response.body()).contains("\"status\":\"UP\"")
    }

    @Test
    fun `기존 FireblocksClient는 코드 분기 없이 Stub Base URL을 호출한다`() {
        val privateKeyPem = testPrivateKeyPem()
        val properties =
            FireblocksProperties(
                baseUrl = "http://127.0.0.1:$serverPort",
                apiKey = "bcm-local-stub",
                privateKeyPem = privateKeyPem,
            )
        val client =
            FireblocksClient(
                restClientBuilder = RestClient.builder(),
                properties = properties,
                signer = FireblocksJwtSigner(properties.apiKey, privateKeyPem, Clock.systemUTC()),
                metrics = NoOpOperationalMetricsPort,
                restClientFactory = PooledFireblocksRestClientFactory(),
            )

        val blockchains = client.blockchains()
        val blockchain = blockchains.data.single()

        assertThat(blockchains.next).isNull()
        assertThat(blockchain.id).isEqualTo("local-evm")
        assertThat(blockchain.displayName).isEqualTo("Local EVM")
        assertThat(blockchain.onchain?.chainId).isEqualTo("31337")
        assertThat(blockchain.onchain?.test).isTrue()
    }

    private fun get(url: String): HttpResponse<String> =
        HttpClient
            .newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )

    private fun testPrivateKeyPem(): String {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val body = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(keyPair.private.encoded)
        return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----"
    }
}
