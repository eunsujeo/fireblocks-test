package com.whatto.bcm.app.bat

import com.whatto.bcm.app.bat.support.IntegrationTestSupport
import com.whatto.bcm.app.bat.sweep.SweepBatchExecutionCommand
import com.whatto.bcm.app.bat.sweep.SweepBatchReconciliationService
import com.whatto.bcm.domain.sweep.SweepEventPublisher
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import java.time.Clock
import java.time.ZoneOffset

@SpringBootTest(
    classes = [BcmBatApplication::class],
    properties = [
        "bcm.fireblocks.base-url=http://127.0.0.1:9",
        "bcm.fireblocks.webhook-jwks-url=http://127.0.0.1:9/jwks",
        "bcm.catalog-sync.cron=-",
        "bcm.asset-catalog-sync.cron=-",
    ],
)
class BcmBatRuntimeIntegrationTest : IntegrationTestSupport() {
    @Autowired
    lateinit var context: ApplicationContext

    @Test
    fun `배치 실행과 대사 및 outbox 발행을 하나의 UTC clock으로 조립한다`() {
        assertThat(context.getBeansOfType(Clock::class.java).values.map { it.zone }).containsExactly(ZoneOffset.UTC)
        assertThat(context.getBeansOfType(SweepBatchExecutionCommand::class.java)).hasSize(1)
        assertThat(context.getBeansOfType(SweepBatchReconciliationService::class.java)).hasSize(1)
        assertThat(context.getBeansOfType(SweepEventPublisher::class.java)).hasSize(1)
        assertThat(context.beanDefinitionNames).noneMatch { it.contains("webhookDecision") || it.contains("outboxRelay") }
    }
}
