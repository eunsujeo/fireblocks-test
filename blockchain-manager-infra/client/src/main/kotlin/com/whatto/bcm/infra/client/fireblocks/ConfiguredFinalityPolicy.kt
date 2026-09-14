package com.whatto.bcm.infra.client.fireblocks

import com.whatto.bcm.domain.tx.FinalityPolicy
import com.whatto.bcm.domain.tx.FinalityPolicyConfigurationException
import com.whatto.bcm.infra.client.config.ConditionalOnFireblocksProtocol
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/** 배포 환경의 네트워크별 DCCP 임계값을 벤더 상태 번역에 제공한다. */
@Component
@ConditionalOnFireblocksProtocol
class ConfiguredFinalityPolicy(
    private val environment: Environment,
) : FinalityPolicy {
    override fun requiredConfirmations(network: String): Int {
        val confirmations =
            environment.getProperty(
                "bcm.finality-confirmations.${network.lowercase()}",
                Int::class.java,
            ) ?: throw FinalityPolicyConfigurationException(network)
        if (confirmations <= 0) throw FinalityPolicyConfigurationException(network)
        return confirmations
    }
}
