package com.whatto.bcm.admin.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration

@ConfigurationProperties("bcm.admin")
data class AdminProperties(
    val mode: String = "FUNCTION_TEST",
    val targetBaseUrl: String = "http://127.0.0.1:8080",
    val connectTimeoutMillis: Long = 1_000,
    val readTimeoutMillis: Long = 3_000,
)

@Configuration
class AdminConfig {
    @Bean
    fun adminClock(): Clock = Clock.systemUTC()

    @Bean
    fun adminHttpClient(properties: AdminProperties): HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofMillis(properties.connectTimeoutMillis))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
}
