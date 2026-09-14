package com.whatto.bcm.app.bat

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.ConfigurableApplicationContext

class ProviderStartupTest {
    @ParameterizedTest
    @CsvSource("'', BCM_PROVIDER must be one of", "unknown, BCM_PROVIDER must be one of", "dfns, BCM_PROVIDER=dfns is not implemented")
    fun `미지원 선택은 DB와 벤더 및 업무 스케줄러 생성 전에 기동을 거절한다`(
        provider: String,
        message: String,
    ) {
        lateinit var source: ConfigurableApplicationContext
        assertThatThrownBy {
            SpringApplicationBuilder(BcmBatApplication::class.java)
                .web(WebApplicationType.NONE)
                .initializers({ source = it })
                .run("--bcm.provider=$provider", "--bcm.fireblocks.private-key-file=/must-not-read/test.pem")
                .close()
        }.hasStackTraceContaining(message)
        assertThat(source.beanFactory.singletonNames).noneMatch {
            it == "dataSource" || it == "fireblocksClient" || it.endsWith("Job")
        }
        assertThat(source.beanFactory.beanDefinitionNames).noneMatch {
            it.contains("fireblocks", ignoreCase = true)
        }
    }
}
