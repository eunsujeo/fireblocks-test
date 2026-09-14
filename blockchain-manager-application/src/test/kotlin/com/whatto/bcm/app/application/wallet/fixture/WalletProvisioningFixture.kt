package com.whatto.bcm.app.application.wallet.fixture

import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountModel
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.vendor.NetworkWalletCreationRequest
import com.whatto.bcm.domain.vendor.NetworkWalletObservation
import com.whatto.bcm.domain.vendor.NetworkWalletOwnership
import com.whatto.bcm.domain.vendor.NetworkWalletResponse
import com.whatto.bcm.domain.vendor.NetworkWalletScope
import com.whatto.bcm.domain.wallet.NetworkWalletCreationIntent
import com.whatto.bcm.domain.wallet.NetworkWalletCreationSeed
import com.whatto.bcm.domain.wallet.NetworkWalletCreationStatus
import com.whatto.bcm.domain.wallet.NetworkWalletSubmissionSpec
import com.whatto.bcm.domain.wallet.StoredNetworkWalletEvidence
import java.security.MessageDigest

object WalletProvisioningFixture {
    const val NOW = "20260914000000"

    fun origin() = ProviderOrigin("origin-fixture", "dfns", "dfns", "instance-fixture", "org-fixture", "TESTNET")

    fun account() = Account("acct_fixture", AccountType.CUSTOMER, "ref-fixture", null, NOW, AccountModel.LOGICAL)

    fun seed() =
        NetworkWalletCreationSeed(
            "intent-fixture",
            NetworkWalletCreationRequest(NetworkWalletScope(origin(), account().accountId, "ETHEREUM_TEST"), "correlation-fixture"),
            NetworkWalletSubmissionSpec("a".repeat(64), "internal-v1", "internal-network"),
        )

    fun intent(
        status: NetworkWalletCreationStatus = NetworkWalletCreationStatus.PREPARED,
        revision: Long = 0,
    ) = NetworkWalletCreationIntent(
        seed().intentId,
        seed().request,
        seed().submission,
        status,
        revision,
        if (status == NetworkWalletCreationStatus.PREPARED) null else NOW,
        null,
        null,
        null,
        false,
        null,
        NOW,
        NOW,
    )

    fun wallet() =
        NetworkWalletObservation(
            origin(),
            seed().request.scope.network,
            "wallet-fixture",
            seed().request.correlationId,
            NetworkWalletOwnership.ORGANIZATION,
            "address-fixture",
        )

    // 정규화 포트/보관 계약용 바이트이며 Dfns wire fixture가 아니다.
    fun body() = byteArrayOf(10, 20, 30, 40)

    fun <T> response(value: T) = NetworkWalletResponse(value, body())

    fun evidence() = StoredNetworkWalletEvidence("fixture://protected-observation", sha256(body()))

    fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
