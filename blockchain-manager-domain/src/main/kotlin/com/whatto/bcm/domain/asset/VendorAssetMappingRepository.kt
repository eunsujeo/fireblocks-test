package com.whatto.bcm.domain.asset

interface VendorAssetMappingRepository {
    fun find(
        network: String,
        symbol: String,
    ): VendorAssetMapping?

    fun findCurrent(
        network: String,
        symbol: String,
    ): VendorAssetMapping? = find(network, symbol)

    /** 수신 웹훅의 벤더 assetId를 우리 (network, symbol)로 되돌린다. */
    fun findByVendorAssetId(vendorAssetId: String): VendorAssetMapping?

    fun findAll(
        network: String? = null,
        symbol: String? = null,
    ): List<VendorAssetMapping>

    fun existsByNetwork(network: String): Boolean

    fun save(
        mapping: VendorAssetMapping,
        requestId: String,
    ): VendorAssetMapping = insert(mapping)

    fun insert(mapping: VendorAssetMapping): VendorAssetMapping

    fun deactivate(
        network: String,
        symbol: String,
        employeeNo: String,
        branchCode: String,
        requestId: String,
        changedAt: String,
    ) = delete(network, symbol)

    /** 이전 테스트 구현체 호환용이다. 실제 영속성 구현은 물리 삭제 대신 [deactivate]를 구현한다. */
    fun delete(
        network: String,
        symbol: String,
    ) {
        error("physical asset mapping deletion is not supported")
    }
}
