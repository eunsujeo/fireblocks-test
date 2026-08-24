package com.whatto.bcm.app.webhook

import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.TypeExcludeFilter
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.scheduling.annotation.EnableScheduling

/** Fireblocks PUBLIC Webhook 수신·판단·outbox relay 전용 조립 지점. */
@SpringBootApplication
@ComponentScan(
    basePackages = ["com.whatto.bcm"],
    excludeFilters = [
        ComponentScan.Filter(type = FilterType.CUSTOM, classes = [TypeExcludeFilter::class]),
        ComponentScan.Filter(type = FilterType.CUSTOM, classes = [AutoConfigurationExcludeFilter::class]),
        ComponentScan.Filter(type = FilterType.REGEX, pattern = ["com\\.whatto\\.bcm\\.testsupport\\..*"]),
    ],
)
@EnableScheduling
class BcmWebhookApplication

fun main(args: Array<String>) {
    runApplication<BcmWebhookApplication>(*args)
}
