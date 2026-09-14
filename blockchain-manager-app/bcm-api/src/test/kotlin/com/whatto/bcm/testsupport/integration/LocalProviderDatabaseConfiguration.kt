package com.whatto.bcm.testsupport.integration

import com.whatto.bcm.testsupport.integration.IntegrationTestSupport
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary

@TestConfiguration(proxyBeanMethods = false)
class LocalProviderDatabaseConfiguration {
    @Bean
    @Primary
    fun localConnectionDetails(): JdbcConnectionDetails =
        object : JdbcConnectionDetails {
            override fun getJdbcUrl(): String = IntegrationTestSupport.localJdbcUrl

            override fun getUsername(): String = IntegrationTestSupport.postgres.username

            override fun getPassword(): String = IntegrationTestSupport.postgres.password
        }
}
