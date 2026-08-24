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
    val vendorMode: String = "FIREBLOCKS",
    val chainMode: String = "TESTNET",
    val dataSet: String = "fireblocks",
    val targetBaseUrl: String = "http://127.0.0.1:8080",
    val webhookManagementBaseUrl: String = "http://127.0.0.1:9091",
    val connectTimeoutMillis: Long = 1_000,
    val readTimeoutMillis: Long = 3_000,
    val systemTest: SystemTestProperties = SystemTestProperties(),
    val localScenario: LocalScenarioProperties = LocalScenarioProperties(),
    val localAssetManagement: LocalAssetManagementProperties = LocalAssetManagementProperties(),
)

data class SystemTestProperties(
    val enabled: Boolean = false,
    val stateDirectory: String = "",
    val staleAfterSeconds: Long = 900,
) {
    init {
        require(staleAfterSeconds > 0) { "system-test staleAfterSeconds must be positive" }
    }
}

data class LocalScenarioProperties(
    val enabled: Boolean = false,
    val repositoryDirectory: String = "",
)

data class LocalAssetManagementProperties(
    val enabled: Boolean = false,
    val employeeNo: String = "LOCAL",
    val branchCode: String = "9999",
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
