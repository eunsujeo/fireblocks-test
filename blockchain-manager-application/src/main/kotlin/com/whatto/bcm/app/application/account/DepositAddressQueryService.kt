package com.whatto.bcm.app.application.account

import com.whatto.bcm.domain.account.DepositAddress
import com.whatto.bcm.domain.account.DepositAddressRepository
import org.springframework.stereotype.Service

/** 다른 피처가 주소 저장소를 직접 참조하지 않도록 사용 여부 조회를 캡슐화한다. */
@Service
class DepositAddressQueryService(
    private val repository: DepositAddressRepository,
) {
    fun existsByAsset(
        network: String,
        symbol: String,
    ): Boolean = repository.existsByAsset(network, symbol)

    fun findByAddress(
        address: String,
        network: String,
    ): DepositAddress? = repository.findByAddress(address, network)
}
