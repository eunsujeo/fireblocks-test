package com.whatto.bcm.app.application.wallet

import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.wallet.NetworkWalletProvisioningRepository
import org.springframework.stereotype.Service

/** 다른 피처가 네트워크 지갑 저장소를 직접 참조하지 않도록 조회를 캡슐화한다(`docs/standards/architecture.md`). */
@Service
class NetworkWalletQueryService(
    private val repository: NetworkWalletProvisioningRepository,
) {
    /**
     * 그 주소가 우리 네트워크 지갑의 주소인가. 계정·자산을 묻지 않고 **소유권만** 본다 —
     * 내부이체의 수신측 판정이 쓴다(계약13 "내부이체").
     */
    fun ownsAddress(
        origin: ProviderOrigin,
        network: String,
        address: String,
    ): Boolean = repository.ownsWalletAddress(origin, network, address)
}
