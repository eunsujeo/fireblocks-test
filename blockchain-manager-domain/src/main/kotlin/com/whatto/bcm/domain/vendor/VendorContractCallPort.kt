package com.whatto.bcm.domain.vendor

interface VendorContractCallPort {
    fun submitContractCall(request: VendorContractCallRequest): VendorTransactionSubmission

    fun contractCallByExternalTransactionId(externalTransactionId: String): VendorContractCall?
}

data class VendorContractCallRequest(
    val externalTransactionId: String,
    val network: String,
    val sourceVaultId: String,
    val contractAddress: String,
    val callData: String,
    val useGasless: Boolean,
)

data class VendorContractCall(
    val transactionId: String,
    val externalTransactionId: String?,
    val sourceVaultId: String?,
    val contractAddress: String?,
    val callData: String?,
)
