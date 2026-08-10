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

    fun adopt(
        candidateId: String,
        network: String,
        employeeNo: String,
        branchCode: String,
    ): VendorBlockchainCatalog

    fun release(
        network: String,
        employeeNo: String,
        branchCode: String,
    ): Boolean
}
