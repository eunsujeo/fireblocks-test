package com.whatto.bcm.app.application.transaction.fixture

import com.whatto.bcm.domain.vendor.VendorTransaction
import com.whatto.bcm.domain.vendor.VendorTransactionPeer

object VendorTransactionFixture {
    fun fixture(
        transactionId: String = "tx-91c",
        externalTransactionId: String? = "wd-260713-0042",
        vendorAssetId: String = "asset-uuid",
        rawStatus: String = "COMPLETED",
        subStatus: String? = "CONFIRMED",
        confirmationCount: Int = 12,
        createdAtEpochMillis: Long = 1_786_068_306_789,
        sourceAddress: String? = "0xFrom",
        destinationAddress: String? = "0x9fE2",
    ): VendorTransaction =
        VendorTransaction(
            transactionId = transactionId,
            externalTransactionId = externalTransactionId,
            vendorAssetId = vendorAssetId,
            rawStatus = rawStatus,
            subStatus = subStatus,
            transactionHash = "0xabc",
            source = VendorTransactionPeer("VAULT_ACCOUNT", "71"),
            destination = VendorTransactionPeer("ONE_TIME_ADDRESS", null),
            sourceAddress = sourceAddress,
            destinationAddress = destinationAddress,
            amount = "1.50",
            confirmationCount = confirmationCount,
            createdAtEpochMillis = createdAtEpochMillis,
            lastUpdatedEpochMillis = 1_786_068_370_120,
        )
}
