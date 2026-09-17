package com.whatto.bcm.app.webhook

import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.TypeExcludeFilter
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * 벤더 Webhook 수신·판단·outbox relay 전용 조립 지점. 수신 프로토콜과 판단 워커는 `BCM_PROVIDER`가 고르며
 * (`fireblocks`·`local`은 `WebhookDecisionTransaction`, `dfns`는 `DfnsWebhookDecisionTransaction`) 제공자마다 하나만 뜬다.
 */
@SpringBootApplication
@ComponentScan(
    basePackages = ["com.whatto.bcm.app.webhook", "com.whatto.bcm.infra", "com.whatto.bcm.support"],
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.CUSTOM, classes = [TypeExcludeFilter::class]),
        ComponentScan.Filter(type = FilterType.CUSTOM, classes = [AutoConfigurationExcludeFilter::class]),
    ],
)
@EnableScheduling
class BcmWebhookApplication

fun main(args: Array<String>) {
    runApplication<BcmWebhookApplication>(*args)
}
