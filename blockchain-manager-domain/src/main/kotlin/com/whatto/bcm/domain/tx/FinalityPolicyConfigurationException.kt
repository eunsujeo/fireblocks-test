package com.whatto.bcm.domain.tx

/** 입력 payload가 아니라 배포 설정이 잘못되어 finality 판정을 수행할 수 없음을 나타낸다. */
class FinalityPolicyConfigurationException(
    network: String,
) : RuntimeException("finality policy is not configured correctly: network=$network")
