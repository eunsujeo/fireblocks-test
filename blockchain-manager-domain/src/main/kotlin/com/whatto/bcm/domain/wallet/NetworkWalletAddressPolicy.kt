package com.whatto.bcm.domain.wallet

/**
 * 네트워크 지갑 주소를 토큰 수신 주소로 쓰는 운영 정책 — 코드가 체인의 수신 계정 모델을 추정하지 않고 운영 설정이 확인한 네트워크만 허용한다(계약13).
 * `provisioningRetryAfterSeconds`는 지갑 준비 중일 때 호출자에게 안내하는 BCM 폴링 정책이며 벤더 보장이 아니다.
 */
data class NetworkWalletAddressPolicy(
    /** 지갑 주소가 곧 토큰 수신 주소인(EVM 계정 모델·tag/memo 없음) BCM 네트워크 코드. */
    val accountAddressNetworks: Set<String>,
    val provisioningRetryAfterSeconds: Long,
) {
    init {
        require(accountAddressNetworks.all { it.isNotBlank() && it == it.trim() }) { "accountAddressNetworks must not contain blank codes" }
        require(provisioningRetryAfterSeconds >= 1) { "provisioningRetryAfterSeconds must be positive" }
    }

    fun allowsAccountAddress(network: String): Boolean = network in accountAddressNetworks
}
