package com.whatto.bcm.infra.client.fireblocks

/**
 * 벤더 응답 매핑 — 필드명은 fireblocks-openapi-spec 스키마 그대로 (VaultAccount·CreateVaultAssetResponse·VaultAsset).
 * 전 필드 nullable 로 받고 도메인 변환 시 필수값을 검증한다 (벤더 응답 결손을 매핑 시점에 드러내기 위함).
 */
internal data class VaultAccountResponse(
    val id: String? = null,
    val name: String? = null,
)

internal data class CreateVaultAssetResponse(
    val id: String? = null,
    val address: String? = null,
    /** Tag/Memo 계열 자산만 내려온다 */
    val tag: String? = null,
)

internal data class VaultAssetResponse(
    val id: String? = null,
    val total: String? = null,
    val available: String? = null,
    val pending: String? = null,
    val frozen: String? = null,
    val lockedAmount: String? = null,
)

internal data class BlockchainListResponse(
    val data: List<BlockchainResponse> = emptyList(),
    val next: String? = null,
)

internal data class BlockchainResponse(
    val id: String? = null,
    val legacyId: String? = null,
    val displayName: String? = null,
    val nativeAssetId: String? = null,
    val onchain: BlockchainOnchainResponse? = null,
    val metadata: BlockchainMetadataResponse? = null,
)

internal data class BlockchainMetadataResponse(
    val deprecated: Boolean? = null,
)

internal data class BlockchainOnchainResponse(
    val protocol: String? = null,
    val chainId: String? = null,
    val test: Boolean? = null,
    val signingAlgo: String? = null,
)

internal data class AssetListResponse(
    val data: List<AssetResponse> = emptyList(),
    val next: String? = null,
)

internal data class AssetResponse(
    val id: String? = null,
    val legacyId: String? = null,
    val blockchainId: String? = null,
    val displayName: String? = null,
    val displaySymbol: String? = null,
    val decimals: Int? = null,
    val assetClass: String? = null,
    val onchain: AssetOnchainResponse? = null,
)

internal data class AssetOnchainResponse(
    val address: String? = null,
    val decimals: Int? = null,
)

internal data class CreateTransactionResponse(
    val id: String? = null,
    val status: String? = null,
)

internal data class TransactionResponse(
    val id: String? = null,
    val externalTxId: String? = null,
    val status: String? = null,
    val subStatus: String? = null,
    val txHash: String? = null,
    val assetId: String? = null,
    val source: TransactionPeerResponse? = null,
    val sourceAddress: String? = null,
    val destination: TransactionPeerResponse? = null,
    val destinationAddress: String? = null,
    val amountInfo: TransactionAmountInfoResponse? = null,
    val createdAt: Long? = null,
    val lastUpdated: Long? = null,
    val numOfConfirmations: Int? = null,
    val operation: String? = null,
    val extraParameters: TransactionExtraParametersResponse? = null,
    val networkRecords: List<TransactionNetworkRecordResponse>? = null,
)

internal data class TransactionNetworkRecordResponse(
    val type: String? = null,
    val source: TransactionPeerResponse? = null,
    val destination: TransactionPeerResponse? = null,
    val destinationAddress: String? = null,
    val txHash: String? = null,
    val assetId: String? = null,
    val netAmount: String? = null,
    val isDropped: Boolean? = null,
)

internal data class TransactionExtraParametersResponse(
    val contractCallData: String? = null,
)

internal data class TransactionPeerResponse(
    val type: String? = null,
    val id: String? = null,
)

internal data class TransactionAmountInfoResponse(
    val amount: String? = null,
)

internal data class EstimatedNetworkFeeResponse(
    val low: NetworkFeeResponse? = null,
    val medium: NetworkFeeResponse? = null,
    val high: NetworkFeeResponse? = null,
)

internal data class NetworkFeeResponse(
    val feePerByte: String? = null,
    val gasPrice: String? = null,
    val networkFee: String? = null,
    val baseFee: String? = null,
    val priorityFee: String? = null,
)
