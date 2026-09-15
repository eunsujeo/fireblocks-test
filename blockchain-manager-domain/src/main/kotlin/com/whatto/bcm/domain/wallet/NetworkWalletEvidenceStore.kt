package com.whatto.bcm.domain.wallet

import com.whatto.bcm.domain.vendor.NetworkWalletScope

/**
 * 보호된 저장소에 원문을 durable 보관한 뒤 참조/hash를 반환한다. 기본 성공 구현을 제공하지 않는다.
 * 반환 hash는 저장소가 실제 보관한 바이트에서 계산한 값이어야 하며, 호출자는 자신이 전달한 바이트의 SHA-256과 대조한다.
 * 보관 실패는 예외로 전파한다. 실패를 참조 없는 성공이나 빈 hash로 바꾸지 않는다.
 */
fun interface NetworkWalletEvidenceStore {
    fun store(
        context: NetworkWalletEvidenceContext,
        body: ByteArray,
    ): StoredNetworkWalletEvidence
}

/**
 * 보관된 증적의 메타데이터 조회. 원문 바이트는 반환하지 않는다 — 원문 열람은 애플리케이션 역할이 아닌 감사 역할의 권한이다.
 * 참조 형식은 저장 어댑터가 정하며 domain은 불투명 문자열로만 다룬다.
 */
interface NetworkWalletEvidenceArchive {
    /** null은 해당 참조의 증적이 없다는 뜻이다. 어댑터가 발급하지 않은 형식의 참조는 거절한다. */
    fun find(reference: String): NetworkWalletEvidenceRecord?
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

/** 원문을 제외한 증적 행 — 의도·scope·작업 종류·조회 위치와 저장소가 계산한 길이/hash. */
data class NetworkWalletEvidenceRecord(
    val reference: String,
    val intentId: String,
    val originId: String,
    val accountId: String,
    val network: String,
    val correlationId: String,
    val requestHash: String,
    val operation: NetworkWalletEvidenceOperation,
    val cursor: String?,
    val knownWalletId: String?,
    val length: Int,
    val hash: String,
    val observedAt: String,
)
