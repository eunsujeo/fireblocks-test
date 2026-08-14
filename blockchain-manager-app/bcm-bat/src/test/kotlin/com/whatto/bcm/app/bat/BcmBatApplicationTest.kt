package com.whatto.bcm.app.bat

import com.whatto.bcm.app.bat.sweep.LoggingSweepExecutionAlertAdapter
import com.whatto.bcm.domain.sweep.SweepExecutionAlertPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.time.ZoneOffset

class BcmBatApplicationTest {
    @Test
    fun `sweep 실행 경보 포트는 운영 빈으로 조립된다`() {
        assertThat(LoggingSweepExecutionAlertAdapter::class.java).hasAnnotation(Component::class.java)

        ApplicationContextRunner()
            .withUserConfiguration(LoggingSweepExecutionAlertAdapter::class.java)
            .run { context -> assertThat(context).hasSingleBean(SweepExecutionAlertPort::class.java) }
    }

    @Test
    fun `배치 절대시각 원천은 UTC다`() {
        assertThat(BcmBatApplication().clock().zone).isEqualTo(ZoneOffset.UTC)
    }

    @Test
    fun `배치 런타임도 가상 스레드를 사용한다`() {
        val properties = YamlPropertySourceLoader().load("application", ClassPathResource("application.yaml")).single()
        val environment = StandardEnvironment().apply { propertySources.addFirst(properties) }

        assertThat(environment.getProperty("spring.threads.virtual.enabled", Boolean::class.java)).isTrue()
    }

    @Test
    fun `sweep 출시 게이트의 배포 기본값은 모두 닫혀 있다`() {
        val properties = YamlPropertySourceLoader().load("application", ClassPathResource("application.yaml")).single()
        val environment = StandardEnvironment().apply { propertySources.addFirst(properties) }
        val gates =
            listOf(
                "normal-approval-enabled",
                "emergency-revocation-enabled",
                "batch-submission-enabled",
                "tap-approval-policy-verified",
                "tap-revocation-policy-verified",
                "tap-batch-policy-verified",
                "callback-verified",
                "universal-gasless-verified",
                "sweep-contract-verified",
            )

        gates.forEach { gate ->
            assertThat(environment.getProperty("bcm.sweep.security.$gate", Boolean::class.java)).isFalse()
        }
    }
}
