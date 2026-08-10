package com.whatto.bcm.app.application.webhook

import com.whatto.bcm.domain.tx.FinalityPolicy
import com.whatto.bcm.domain.webhook.WebhookPayloadException
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/** 배포 환경의 네트워크별 DCCP 임계값을 도메인 정책 포트로 제공한다. */
@Component
class ConfiguredFinalityPolicy(
    private val environment: Environment,
) : FinalityPolicy {
    override fun requiredConfirmations(network: String): Int {
        val confirmations =
            environment.getProperty(
                "bcm.finality-confirmations.${network.lowercase()}",
                Int::class.java,
            ) ?: throw WebhookPayloadException("missing finality policy")
        if (confirmations <= 0) throw WebhookPayloadException("invalid finality policy")
        return confirmations
    }
}
