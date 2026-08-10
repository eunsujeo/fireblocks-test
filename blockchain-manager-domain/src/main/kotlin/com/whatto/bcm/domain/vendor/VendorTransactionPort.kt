package com.whatto.bcm.domain.vendor

import com.whatto.bcm.domain.exception.VendorApiException

interface VendorTransactionPort {
    fun submitTransaction(request: VendorTransactionRequest): VendorTransactionSubmission

    fun transaction(transactionId: String): VendorTransaction?

    fun transactionByExternalTransactionId(externalTransactionId: String): VendorTransaction?

    fun transactions(request: VendorTransactionPageRequest): VendorPage<VendorTransaction>
}

data class VendorTransactionRequest(
    val externalTransactionId: String,
    val vendorAssetId: String,
    val sourceVaultId: String,
    val destination: VendorTransactionDestination,
    val amount: String,
    val note: String?,
    val travelRuleMessage: Map<String, Any?>?,
    val useGasless: Boolean,
)

sealed interface VendorTransactionDestination {
    data class Address(
        val address: String,
    ) : VendorTransactionDestination

    data class Account(
        val vaultId: String,
    ) : VendorTransactionDestination

    data class Whitelisted(
        val walletId: String,
    ) : VendorTransactionDestination
}

sealed interface VendorTransactionSubmission {
    data class Accepted(
        val transactionId: String,
    ) : VendorTransactionSubmission

    /**
     * 벤더가 `400` 으로 거절했다. 중복 제출인지 그 밖의 검증 실패인지는 응답만으로 가르지 않는다 —
     * `externalTxId` 조회로 실재하는 거래를 확인한 뒤에야 판정한다 (02-bcm-flow 벤더 응답별 처리).
     */
    data class BadRequestNeedsLookup(
        val rejection: VendorApiException,
    ) : VendorTransactionSubmission
}

data class VendorTransactionPageRequest(
    val sourceVaultId: String,
    val afterEpochMillis: Long? = null,
    val beforeEpochMillis: Long? = null,
    val vendorStatus: String? = null,
    val order: VendorTransactionOrder = VendorTransactionOrder.DESC,
    val limit: Int = 200,
    val cursor: String? = null,
) {
    init {
        require(limit in 1..500) { "transaction page limit must be between 1 and 500" }
    }
}

enum class VendorTransactionOrder {
    ASC,
    DESC,
}

data class VendorTransaction(
    val transactionId: String,
    val externalTransactionId: String?,
    val vendorAssetId: String,
    val rawStatus: String,
    val subStatus: String?,
    val transactionHash: String?,
    val source: VendorTransactionPeer,
    val destination: VendorTransactionPeer,
    val sourceAddress: String?,
    val destinationAddress: String?,
    val amount: String,
    val confirmationCount: Int,
    val createdAtEpochMillis: Long,
    val lastUpdatedEpochMillis: Long,
)

data class VendorTransactionPeer(
    val type: String,
    val id: String?,
)
