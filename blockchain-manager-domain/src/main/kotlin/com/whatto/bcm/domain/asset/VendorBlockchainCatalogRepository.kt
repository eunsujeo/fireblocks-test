package com.whatto.bcm.domain.asset

interface VendorBlockchainCatalogRepository {
    fun findAll(
        query: String? = null,
        chainId: Long? = null,
        adopted: Boolean? = null,
        testnet: Boolean? = null,
    ): List<VendorBlockchainCatalog>

    fun findByCandidateId(candidateId: String): VendorBlockchainCatalog?

    fun findByNetwork(network: String): VendorBlockchainCatalog?

    fun insert(catalog: VendorBlockchainCatalog): VendorBlockchainCatalog

    fun updateSnapshot(catalog: VendorBlockchainCatalog): VendorBlockchainCatalog

    /** 채택은 계정·자산 모델을 함께 정한다 — 채택된 행은 모델 없이 존재할 수 없다(03 V35). */
    fun adopt(
        candidateId: String,
        network: String,
        chainModel: ChainModel,
        employeeNo: String,
        branchCode: String,
    ): VendorBlockchainCatalog

    fun release(
        network: String,
        employeeNo: String,
        branchCode: String,
    ): Boolean
}
