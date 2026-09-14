package com.whatto.bcm.domain.vendor

/**
 * 응답 유실 뒤 동일 자원을 연결할 수 있는지만 판정한다. 새 POST/키 회전을 허가하는 결과는 없다.
 * scanComplete는 조회 범위를 끝까지 성공적으로 읽었다는 뜻이며 지갑 부재·동시 생성 부재의 증명이 아니다.
 * 이 정책은 DB 제출 권한 CAS·관찰 증적 저장·시간에 따른 재조회 스케줄을 대신하지 않는다.
 */
class NetworkWalletRecoveryPolicy {
    fun evaluate(
        request: NetworkWalletCreationRequest,
        candidates: List<NetworkWalletObservation>,
        scanComplete: Boolean,
        knownWalletId: String? = null,
    ): NetworkWalletRecoveryDecision {
        knownWalletId?.let { requireWalletIdentifier("knownWalletId", it) }
        val observations = candidates.distinct()
        val mismatch =
            when {
                observations.any { it.origin != request.scope.origin } -> NetworkWalletConflictReason.ORIGIN_MISMATCH
                observations.any { it.network != request.scope.network } -> NetworkWalletConflictReason.NETWORK_MISMATCH
                observations.any { it.correlationId != request.correlationId } -> NetworkWalletConflictReason.CORRELATION_MISMATCH
                observations.any { it.ownership != NetworkWalletOwnership.ORGANIZATION } ->
                    NetworkWalletConflictReason.OWNERSHIP_NOT_VERIFIED
                knownWalletId != null && observations.any { it.vendorWalletId != knownWalletId } ->
                    NetworkWalletConflictReason.KNOWN_WALLET_MISMATCH
                observations.map { it.vendorWalletId }.distinct().size > 1 -> NetworkWalletConflictReason.MULTIPLE_WALLETS
                observations.size > 1 -> NetworkWalletConflictReason.INCONSISTENT_OBSERVATIONS
                else -> null
            }
        if (mismatch != null) return NetworkWalletRecoveryDecision.Conflict(mismatch)
        if (!scanComplete) return NetworkWalletRecoveryDecision.Pending(NetworkWalletPendingReason.INCOMPLETE_SCAN)
        val wallet = observations.singleOrNull() ?: return NetworkWalletRecoveryDecision.Pending(NetworkWalletPendingReason.NOT_OBSERVED)
        if (wallet.address == null) return NetworkWalletRecoveryDecision.Pending(NetworkWalletPendingReason.ADDRESS_NOT_READY)
        return NetworkWalletRecoveryDecision.Ready(wallet)
    }
}

sealed interface NetworkWalletRecoveryDecision {
    data class Ready(
        val wallet: NetworkWalletObservation,
    ) : NetworkWalletRecoveryDecision

    data class Pending(
        val reason: NetworkWalletPendingReason,
    ) : NetworkWalletRecoveryDecision

    data class Conflict(
        val reason: NetworkWalletConflictReason,
    ) : NetworkWalletRecoveryDecision
}

enum class NetworkWalletPendingReason {
    INCOMPLETE_SCAN,
    NOT_OBSERVED,
    ADDRESS_NOT_READY,
}

enum class NetworkWalletConflictReason {
    ORIGIN_MISMATCH,
    NETWORK_MISMATCH,
    CORRELATION_MISMATCH,
    OWNERSHIP_NOT_VERIFIED,
    KNOWN_WALLET_MISMATCH,
    MULTIPLE_WALLETS,
    INCONSISTENT_OBSERVATIONS,
}
