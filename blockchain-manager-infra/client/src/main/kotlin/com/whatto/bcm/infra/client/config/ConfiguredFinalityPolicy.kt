package com.whatto.bcm.infra.client.config

import com.whatto.bcm.domain.tx.FinalityPolicy
import com.whatto.bcm.domain.tx.FinalityPolicyConfigurationException
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/**
 * 배포 환경의 네트워크별 확정 임계값을 상태 번역에 제공한다. **제공자와 무관한 BCM 설정**이다 —
 * Fireblocks는 벤더 `numOfConfirmations`를, Dfns는 블록 깊이를 이 값과 비교한다(02·CLAUDE.md 3절).
 */
@Component
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
