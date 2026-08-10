package com.whatto.bcm.app.bat

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import java.time.ZoneId

class BcmBatApplicationTest {
    @Test
    fun `배치 시각 원천은 KST다`() {
        assertThat(BcmBatApplication().clock().zone).isEqualTo(ZoneId.of("Asia/Seoul"))
    }

    @Test
    fun `배치 런타임도 가상 스레드를 사용한다`() {
        val properties = YamlPropertySourceLoader().load("application", ClassPathResource("application.yaml")).single()
        val environment = StandardEnvironment().apply { propertySources.addFirst(properties) }

        assertThat(environment.getProperty("spring.threads.virtual.enabled", Boolean::class.java)).isTrue()
    }
}
