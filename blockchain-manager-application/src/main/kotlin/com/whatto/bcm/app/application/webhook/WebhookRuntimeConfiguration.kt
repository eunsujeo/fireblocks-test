package com.whatto.bcm.app.application.webhook

import com.whatto.bcm.app.application.event.OutboxRelayJob
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Configuration

/** Webhook 수신 뒤 판단·발행 런타임을 한 조립 경계로 가져오는 설정. */
@Configuration(proxyBeanMethods = false)
@ComponentScan(
    basePackageClasses = [WebhookDecisionJob::class, OutboxRelayJob::class],
)
class WebhookRuntimeConfiguration
