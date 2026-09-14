package com.whatto.bcm.infra.persistence.wallet.fixture

import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.vendor.NetworkWalletCreationRequest
import com.whatto.bcm.domain.vendor.NetworkWalletObservation
import com.whatto.bcm.domain.vendor.NetworkWalletOwnership
import com.whatto.bcm.domain.vendor.NetworkWalletScope
import com.whatto.bcm.domain.wallet.NetworkWalletCreationSeed
import com.whatto.bcm.domain.wallet.NetworkWalletRecoveryPage
import com.whatto.bcm.domain.wallet.NetworkWalletSubmissionSpec
import java.util.UUID

/** BCM 저장 계약의 내부 값이며 벤더 payload가 아니다. */
object NetworkWalletLedgerFixture {
    const val NOW = "20260914000000"

    fun seed(): NetworkWalletCreationSeed {
        val id = UUID.randomUUID().toString()
        return NetworkWalletCreationSeed(
            intentId = id,
            request =
                NetworkWalletCreationRequest(
                    NetworkWalletScope(
                        ProviderOrigin("wallet-ledger-origin", "fireblocks", "fireblocks", "test-instance", "test-org", "TESTNET"),
                        "acct_$id",
                        "ETHEREUM_TEST",
                    ),
                    "corr-$id",
                ),
            submission = NetworkWalletSubmissionSpec("a".repeat(64), "internal-v1", "internal-network"),
        )
    }

    fun wallet(request: NetworkWalletCreationRequest) =
        NetworkWalletObservation(
            request.scope.origin,
            request.scope.network,
            "wallet-fixture",
            request.correlationId,
            NetworkWalletOwnership.ORGANIZATION,
            "address-fixture",
        )

    fun page(
        scanId: String = "scan-1",
        cursor: String? = null,
        next: String? = null,
        candidates: List<NetworkWalletObservation> = emptyList(),
    ) = NetworkWalletRecoveryPage(
        UUID.randomUUID().toString(),
        scanId,
        cursor,
        next,
        candidates,
        "fixture://internal-observation",
        "b".repeat(64),
        NOW,
    )
}
