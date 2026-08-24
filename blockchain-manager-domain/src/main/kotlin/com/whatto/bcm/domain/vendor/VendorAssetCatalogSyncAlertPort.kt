package com.whatto.bcm.domain.vendor

fun interface VendorAssetCatalogSyncAlertPort {
    fun syncFailed(
        network: String,
        failureType: String,
    )
}
