package com.whatto.bcm.app.application.webhook

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "bcm.webhook-worker", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class WebhookDecisionJob(
    private val processor: WebhookDecisionProcessor,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${bcm.webhook-worker.fixed-delay-millis:500}")
    fun processNext() {
        try {
            processor.processNext()
        } catch (exception: RuntimeException) {
            logger.error("Webhook decision worker failed", exception)
        }
    }
}
