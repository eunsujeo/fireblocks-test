package com.whatto.bcm.domain.wallet

import com.whatto.bcm.domain.vendor.NetworkWalletScope

/** 보호된 저장소에 원문을 durable 보관한 뒤 참조/hash를 반환한다. 기본 성공 구현을 제공하지 않는다. */
fun interface NetworkWalletEvidenceStore {
    fun store(
        context: NetworkWalletEvidenceContext,
        body: ByteArray,
    ): StoredNetworkWalletEvidence
}

data class NetworkWalletEvidenceContext(
    val intentId: String,
    val scope: NetworkWalletScope,
    val correlationId: String,
    val requestHash: String,
    val operation: NetworkWalletEvidenceOperation,
    val observedAt: String,
    val cursor: String?,
    val knownWalletId: String?,
)

enum class NetworkWalletEvidenceOperation {
    CREATE,
    READ,
    DISCOVER,
}

data class StoredNetworkWalletEvidence(
    val reference: String,
    val hash: String,
)
