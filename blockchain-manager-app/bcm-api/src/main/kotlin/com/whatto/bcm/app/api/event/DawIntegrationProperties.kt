package com.whatto.bcm.app.api.event

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@ConfigurationProperties("bcm.daw-integration")
data class DawIntegrationProperties(
    val enabled: Boolean = false,
)

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DawIntegrationProperties::class)
class DawIntegrationConfig
