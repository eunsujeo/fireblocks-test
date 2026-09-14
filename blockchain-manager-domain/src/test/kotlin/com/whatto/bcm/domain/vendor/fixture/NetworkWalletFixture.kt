package com.whatto.bcm.domain.vendor.fixture

import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.vendor.NetworkWalletCreationRequest
import com.whatto.bcm.domain.vendor.NetworkWalletObservation
import com.whatto.bcm.domain.vendor.NetworkWalletOwnership
import com.whatto.bcm.domain.vendor.NetworkWalletScope

/** BCM 내부 정규화 계약용 fixture. Dfns wire 응답을 재현하지 않는다. */
object NetworkWalletFixture {
    fun request() =
        NetworkWalletCreationRequest(
            scope =
                NetworkWalletScope(
                    origin = ProviderOrigin("origin-test", "dfns", "dfns", "instance-test", "organization-test", "TESTNET"),
                    accountId = "acct_test",
                    network = "ETH_SEPOLIA",
                ),
            correlationId = "intent-test",
        )

    fun observation(request: NetworkWalletCreationRequest = request()) =
        NetworkWalletObservation(
            origin = request.scope.origin,
            network = request.scope.network,
            vendorWalletId = "wallet-test",
            correlationId = request.correlationId,
            ownership = NetworkWalletOwnership.ORGANIZATION,
            address = "address-test",
        )
}
