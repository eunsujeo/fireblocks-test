package com.whatto.bcm.app.config

import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.provider.ProviderOriginRepository
import org.springframework.beans.factory.config.BeanFactoryPostProcessor
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.core.env.Environment

@Configuration(proxyBeanMethods = false)
class ProviderOriginConfiguration {
    @Bean
    @Lazy(false)
    fun verifiedProviderOrigin(
        environment: Environment,
        repository: ProviderOriginRepository,
    ): ProviderOrigin {
        val mode = required(environment, "bcm.provider")
        val expected =
            ProviderOrigin(
                required(environment, "bcm.origin.id"),
                mode,
                if (mode == "local") "fireblocks" else mode,
                required(environment, "bcm.origin.platform-instance-id"),
                required(environment, "bcm.origin.vendor-organization-id"),
                required(environment, "bcm.chain-mode"),
            )
        expected.requireMatch(repository.findBinding())
        return expected
    }

    private fun required(
        environment: Environment,
        key: String,
    ): String = checkNotNull(environment.getProperty(key)?.takeIf(String::isNotBlank)) { "Required provider origin configuration: $key" }

    companion object {
        private const val GUARD = "verifiedProviderOrigin"
        private val guardedPackages = listOf("com.whatto.bcm.app.", "com.whatto.bcm.infra.client.", "com.whatto.bcm.infra.messaging.")
        private val configurationExceptions =
            setOf(ProviderOriginConfiguration::class.java.name, "com.whatto.bcm.infra.client.config.ProviderConfiguration")

        /** 정의만 변경한다. 이 단계에서 DB나 일반 빈을 조기 생성하지 않는다. */
        @Bean
        @JvmStatic
        fun providerOriginDependencies(): BeanFactoryPostProcessor =
            BeanFactoryPostProcessor { factory ->
                factory.beanDefinitionNames.forEach { name ->
                    val definition = factory.getBeanDefinition(name)
                    val owner = definition.factoryBeanName?.let { factory.getBeanDefinition(it).beanClassName } ?: definition.beanClassName
                    if (name != GUARD && owner != null && owner !in configurationExceptions && guardedPackages.any(owner::startsWith)) {
                        val dependencies = definition.dependsOn?.toList().orEmpty() + GUARD
                        definition.setDependsOn(*dependencies.distinct().toTypedArray())
                    }
                }
            }
    }
}
