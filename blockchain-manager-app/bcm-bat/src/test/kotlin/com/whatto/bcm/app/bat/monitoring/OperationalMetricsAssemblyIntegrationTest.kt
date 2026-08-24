package com.whatto.bcm.app.bat.monitoring

import com.whatto.bcm.app.bat.BcmBatApplication
import com.whatto.bcm.app.bat.support.IntegrationTestSupport
import com.whatto.bcm.domain.job.JobStateRepository
import com.whatto.bcm.domain.monitoring.OperationalAlertChannel
import com.whatto.bcm.support.monitoring.OperationalAlertProperties
import com.whatto.bcm.support.monitoring.OperationalMetricsPublisher
import io.micrometer.core.instrument.MeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import javax.management.MBeanServer
import javax.management.ObjectName

@SpringBootTest(
    classes = [BcmBatApplication::class],
    properties = [
        "bcm.operational-metrics.initial-delay-millis=3600000",
        "bcm.webhook-recovery.enabled=true",
        "spring.jmx.enabled=true",
    ],
)
class OperationalMetricsAssemblyIntegrationTest : IntegrationTestSupport() {
    @Autowired
    lateinit var publisher: OperationalMetricsPublisher

    @Autowired
    lateinit var registry: MeterRegistry

    @Autowired
    lateinit var jobs: JobStateRepository

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var mBeanServer: MBeanServer

    @Autowired
    lateinit var operationalAlertChannel: OperationalAlertChannel

    @Autowired
    lateinit var operationalAlertProperties: OperationalAlertProperties

    @AfterEach
    fun tearDown() {
        jdbc.update("DELETE FROM bcm_job_m")
        publisher.refresh()
    }

    @Test
    fun `배치 애플리케이션도 작업 heartbeat gauge를 조립한다`() {
        jobs.markStarted("stall-check", "20260817010400")
        jobs.markSucceeded("stall-check", "20260817010500")

        publisher.refresh()

        assertThat(
            registry
                .get("bcm.job.last.success.timestamp.seconds")
                .tag("job", "stall-check")
                .gauge()
                .value(),
        ).isEqualTo(1_786_928_700.0)
    }

    @Test
    fun `웹훅 복구 수동 조작면은 BAT JMX MBean으로 등록된다`() {
        val requestObjectName = ObjectName("org.springframework.boot:type=Endpoint,name=WebhookRecovery")
        val executionObjectName = ObjectName("org.springframework.boot:type=Endpoint,name=WebhookRecoveryExecution")

        assertThat(mBeanServer.isRegistered(requestObjectName)).isTrue()
        assertThat(mBeanServer.getMBeanInfo(requestObjectName).operations.map { it.name })
            .contains("status", "request")
        assertThat(mBeanServer.isRegistered(executionObjectName)).isTrue()
        assertThat(mBeanServer.getMBeanInfo(executionObjectName).operations.map { it.name })
            .contains("execute")
    }

    @Test
    fun `BAT 애플리케이션도 같은 운영 경보 채널을 기본 비활성으로 조립한다`() {
        assertThat(operationalAlertChannel).isNotNull
        assertThat(operationalAlertProperties.enabled).isFalse()
        assertThat(operationalAlertProperties.endpoint).isEmpty()
        assertThat(operationalAlertProperties.bearerToken).isEmpty()
    }
}
