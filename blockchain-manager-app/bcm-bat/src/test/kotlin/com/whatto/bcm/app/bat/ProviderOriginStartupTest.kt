package com.whatto.bcm.app.bat

import com.sun.net.httpserver.HttpServer
import com.whatto.bcm.app.bat.support.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.builder.SpringApplicationBuilder
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.sql.DriverManager
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class ProviderOriginStartupTest : IntegrationTestSupport() {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "id",
            "platform-instance-id",
            "vendor-organization-id",
            "chain-mode",
            "provider",
            "missing-table",
            "missing-binding",
            "lazy-mismatch",
        ],
    )
    fun `원천 불일치는 앱 기동과 외부 작업 전에 차단한다`(field: String) {
        val requests = AtomicInteger()
        val created = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            requests.incrementAndGet()
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()
        val key =
            KeyPairGenerator
                .getInstance("RSA")
                .apply { initialize(2048) }
                .generateKeyPair()
                .private
        val body = Base64.getEncoder().encodeToString(key.encoded)
        val keyFile = Files.createTempFile("bcm-origin-startup-", ".pem")
        Files.writeString(keyFile, "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----")
        try {
            if (field == "missing-binding") {
                DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                    connection.autoCommit = false
                    connection.createStatement().use { statement ->
                        statement.execute("CREATE SCHEMA origin_unregistered")
                        statement.execute("SET LOCAL search_path TO origin_unregistered")
                        val sql =
                            requireNotNull(javaClass.getResourceAsStream("/db/migration/V21__provider_origin_binding.sql"))
                                .bufferedReader()
                                .use { it.readText() }
                        statement.execute(sql)
                    }
                    connection.commit()
                }
            }
            val properties =
                linkedMapOf(
                    "server.port" to "0",
                    "management.server.port" to "0",
                    "spring.datasource.url" to postgres.jdbcUrl,
                    "spring.datasource.username" to postgres.username,
                    "spring.datasource.password" to postgres.password,
                    "spring.datasource.hikari.maximum-pool-size" to "1",
                    "spring.batch.jdbc.initialize-schema" to "never",
                    "bcm.provider" to "fireblocks",
                    "bcm.chain-mode" to "TESTNET",
                    "bcm.origin.id" to "test-fireblocks-origin",
                    "bcm.origin.platform-instance-id" to "test-platform",
                    "bcm.origin.vendor-organization-id" to "test-organization",
                    "bcm.fireblocks.api-key" to "bcm-local-stub",
                    "bcm.fireblocks.private-key-pem" to "",
                    "bcm.fireblocks.private-key-file" to keyFile.toString(),
                    "bcm.fireblocks.base-url" to "http://127.0.0.1:${server.address.port}",
                    "bcm.fireblocks.webhook-jwks-url" to "http://127.0.0.1:${server.address.port}/jwks",
                )
            when (field) {
                "chain-mode" -> properties["bcm.chain-mode"] = "MAINNET"
                "provider" -> {
                    properties["bcm.provider"] = "local"
                    properties["bcm.chain-mode"] = "LOCAL"
                }
                "lazy-mismatch" -> {
                    properties["spring.main.lazy-initialization"] = "true"
                    properties["bcm.origin.id"] = "another-origin"
                }
                "missing-binding" -> properties["spring.datasource.hikari.schema"] = "origin_unregistered"
                "missing-table" -> properties["spring.datasource.hikari.schema"] = "pg_catalog"
                else -> properties["bcm.origin.$field"] = "another-origin"
            }
            assertThatThrownBy {
                SpringApplicationBuilder(BcmBatApplication::class.java)
                    .initializers({ context ->
                        context.beanFactory.addBeanPostProcessor(
                            object : BeanPostProcessor {
                                override fun postProcessBeforeInitialization(
                                    bean: Any,
                                    beanName: String,
                                ): Any {
                                    created += beanName
                                    return bean
                                }
                            },
                        )
                    })
                    .run(*properties.map { (key, value) -> "--$key=$value" }.toTypedArray())
                    .close()
            }.hasStackTraceContaining(
                when (field) {
                    "missing-table" -> "bcm_prvd_bndg_m"
                    "missing-binding" -> "Provider origin binding is missing"
                    else -> "Provider origin mismatch"
                },
            )
            assertThat(requests.get()).isZero()
            assertThat(created).noneMatch {
                it == "fireblocksClient" ||
                    it == "fireblocksJwtSigner" ||
                    it.endsWith("Job") ||
                    it.endsWith("Controller") ||
                    it == "outboxRelayProcessor"
            }
        } finally {
            if (field == "missing-binding") {
                DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                    connection.createStatement().use { it.execute("DROP SCHEMA origin_unregistered CASCADE") }
                }
            }
            server.stop(0)
            Files.deleteIfExists(keyFile)
        }
    }
}
