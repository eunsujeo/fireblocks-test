package com.whatto.bcm.testsupport.chain

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

internal object LocalControlCommand {
    fun requested(args: Array<String>): Boolean = args.size == 2 && args[0] == COMMAND && args[1] in OPERATIONS

    fun execute(
        operation: String,
        environment: Map<String, String> = System.getenv(),
    ): String =
        when (operation) {
            HEALTH_CHAIN -> healthChain(environment)
            HEALTH_STUB -> healthStub(environment)
            RESET -> reset(environment)
            else -> throw IllegalArgumentException("unsupported local control operation: $operation")
        }

    private fun healthChain(environment: Map<String, String>): String {
        val endpoint = loopbackUri(environment.value("BCM_EVM_RPC_URL", "http://127.0.0.1:8545"))
        val manifest = Path.of(environment.value("BCM_LOCAL_CHAIN_MANIFEST_FILE", DEFAULT_MANIFEST_FILE))
        val keys = Path.of(environment.value("BCM_LOCAL_CHAIN_KEY_FILE", DEFAULT_KEY_FILE))
        return awaitHealth("local chain") {
            check(Files.isRegularFile(manifest) && Files.size(manifest) > 0) { "local chain manifest is not ready" }
            check(Files.isRegularFile(keys) && Files.size(keys) > 0) { "local chain key file is not ready" }
            val response = post(endpoint, CHAIN_ID_REQUEST)
            check(response.contains("\"result\":\"0x7a69\"")) { "local chain id is not 31337" }
            "CHAIN_UP"
        }
    }

    private fun healthStub(environment: Map<String, String>): String {
        val base = loopbackUri(environment.value("BCM_STUB_MANAGEMENT_URL", "http://127.0.0.1:18090"))
        val endpoint = base.resolve("/actuator/health")
        return awaitHealth("Fireblocks Stub") {
            val response = get(endpoint)
            check(response.contains("\"status\":\"UP\"")) { "Stub health is not UP" }
            "STUB_UP"
        }
    }

    private fun reset(environment: Map<String, String>): String {
        val base = loopbackUri(environment.value("BCM_FIREBLOCKS_BASE_URL", "http://127.0.0.1:18080"))
        return post(base.resolve("/__stub/reset"), "")
    }

    private fun awaitHealth(
        label: String,
        checkHealth: () -> String,
    ): String {
        var lastFailure: Exception? = null
        repeat(HEALTH_ATTEMPTS) { attempt ->
            try {
                return checkHealth()
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IllegalStateException("$label health wait was interrupted", exception)
            } catch (exception: Exception) {
                lastFailure = exception
                if (attempt + 1 < HEALTH_ATTEMPTS) Thread.sleep(HEALTH_RETRY_MILLIS)
            }
        }
        throw IllegalStateException("$label did not become ready", lastFailure)
    }

    private fun get(uri: URI): String = send(HttpRequest.newBuilder(uri).GET().build())

    private fun post(
        uri: URI,
        body: String,
    ): String =
        send(
            HttpRequest
                .newBuilder(uri)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
        )

    private fun send(request: HttpRequest): String {
        val response =
            HTTP_CLIENT.send(
                request,
                HttpResponse.BodyHandlers.ofString(),
            )
        check(response.statusCode() in 200..299) { "local control HTTP status=${response.statusCode()}" }
        return response.body()
    }

    private fun loopbackUri(value: String): URI {
        val uri = URI.create(value)
        val normalizedHost =
            uri.host
                ?.lowercase()
                ?.removePrefix("[")
                ?.removeSuffix("]")
        check(uri.scheme == "http" && normalizedHost in LOOPBACK_HOSTS && uri.userInfo == null) {
            "local control endpoint must be loopback HTTP"
        }
        return uri
    }

    private fun Map<String, String>.value(
        key: String,
        defaultValue: String,
    ): String = get(key)?.takeIf(String::isNotBlank) ?: defaultValue

    private const val COMMAND = "control"
    private const val HEALTH_CHAIN = "health-chain"
    private const val HEALTH_STUB = "health-stub"
    private const val RESET = "reset"
    private const val DEFAULT_MANIFEST_FILE = "/var/lib/blockchain-manager-local/chain/manifest.json"
    private const val DEFAULT_KEY_FILE = "/var/lib/blockchain-manager-local/chain/evm-keys.json"
    private const val HEALTH_ATTEMPTS = 120
    private const val HEALTH_RETRY_MILLIS = 1_000L
    private val OPERATIONS = setOf(HEALTH_CHAIN, HEALTH_STUB, RESET)
    private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")
    private val HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val CHAIN_ID_REQUEST = """{"jsonrpc":"2.0","id":1,"method":"eth_chainId","params":[]}"""
}
