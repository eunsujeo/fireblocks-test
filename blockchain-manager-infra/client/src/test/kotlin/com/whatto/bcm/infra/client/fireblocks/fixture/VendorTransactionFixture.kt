package com.whatto.bcm.infra.client.fireblocks.fixture

import com.whatto.bcm.domain.vendor.VendorTransactionDestination
import com.whatto.bcm.domain.vendor.VendorTransactionRequest

object VendorTransactionFixture {
    fun request(destination: VendorTransactionDestination = VendorTransactionDestination.Address("0x9fE2")) =
        VendorTransactionRequest(
            externalTransactionId = "wd-260713-0042",
            vendorAssetId = "asset-uuid",
            sourceVaultId = "71",
            destination = destination,
            amount = "1.5",
            note = "approved withdrawal",
            travelRuleMessage = mapOf("encrypted" to "cipher-text"),
            useGasless = true,
        )

    val responseJson =
        """
        {
          "id":"tx-91c",
          "externalTxId":"wd-260713-0042",
          "status":"COMPLETED",
          "subStatus":"CONFIRMED",
          "txHash":"0xabc",
          "assetId":"asset-uuid",
          "source":{"type":"VAULT_ACCOUNT","id":"71"},
          "sourceAddress":"0xFrom",
          "destination":{"type":"ONE_TIME_ADDRESS"},
          "destinationAddress":"0x9fE2",
          "amountInfo":{"amount":"1.50"},
          "createdAt":1786068306789,
          "lastUpdated":1786068370120,
          "numOfConfirmations":12
        }
        """.trimIndent()
}
