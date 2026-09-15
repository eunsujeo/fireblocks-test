package com.whatto.bcm.domain.wallet

import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.ProvisioningPendingException
import com.whatto.bcm.domain.vendor.NetworkWalletCreationRequest
import com.whatto.bcm.domain.vendor.NetworkWalletObservation
import com.whatto.bcm.domain.vendor.NetworkWalletRecoveryDecision
import com.whatto.bcm.domain.vendor.NetworkWalletScope

/** 변경은 호출자 트랜잭션과 독립적으로 커밋한다. 반환된 최초 제출 승자만 외부 create를 호출할 수 있다. */
interface NetworkWalletProvisioningRepository {
    fun reserve(
        seed: NetworkWalletCreationSeed,
        now: String,
    ): NetworkWalletCreationIntent

    fun find(scope: NetworkWalletScope): NetworkWalletCreationIntent?

    fun claimSubmission(
        scope: NetworkWalletScope,
        expectedRevision: Long,
        now: String,
    ): NetworkWalletCreationIntent?

    fun startRecovery(
        scope: NetworkWalletScope,
        expectedRevision: Long,
        scanId: String,
        now: String,
    ): NetworkWalletCreationIntent

    fun recordPage(
        scope: NetworkWalletScope,
        expectedRevision: Long,
        page: NetworkWalletRecoveryPage,
        now: String,
    ): NetworkWalletCreationIntent

    fun findWallet(scope: NetworkWalletScope): NetworkWalletObservation?
}

data class NetworkWalletSubmissionSpec(
    val requestHash: String,
    val requestVersion: String,
    val vendorNetwork: String,
) {
    init {
        require(requestHash.matches(Regex("[0-9a-f]{64}"))) { "Invalid wallet request hash" }
        requireLedgerValue(requestVersion, 32)
        requireLedgerValue(vendorNetwork, 64)
    }
}

data class NetworkWalletCreationSeed(
    val intentId: String,
    val request: NetworkWalletCreationRequest,
    val submission: NetworkWalletSubmissionSpec,
) {
    init {
        requireLedgerValue(intentId, 64)
        requireLedgerValue(request.scope.accountId, 64)
        requireLedgerValue(request.scope.network, 20)
        requireLedgerValue(request.correlationId, 64)
    }
}

data class NetworkWalletCreationIntent(
    val intentId: String,
    val request: NetworkWalletCreationRequest,
    val submission: NetworkWalletSubmissionSpec,
    val status: NetworkWalletCreationStatus,
    val revision: Long,
    val preparedAt: String?,
    val knownWalletId: String?,
    val scanId: String?,
    val nextCursor: String?,
    val scanComplete: Boolean,
    val lastReason: String?,
    val registeredAt: String,
    val lastChangedAt: String,
) {
    fun canClaimSubmission(expectedRevision: Long): Boolean = status == NetworkWalletCreationStatus.PREPARED && revision == expectedRevision

    /**
     * 공개 계약으로 번역하기 직전의 판정 — 완료면 연결된 wallet ID를 돌려주고, 진행 중이면 보류, 충돌이면 확정 오류를 던진다.
     * 보류는 재생성·키 회전 허가가 아니라 같은 요청의 조회 재개 신호다. retryAfterSeconds는 호출자 정책이며 벤더 보장이 아니다.
     */
    fun requireCompleted(retryAfterSeconds: Long): String =
        when (status) {
            NetworkWalletCreationStatus.COMPLETED ->
                checkNotNull(
                    knownWalletId,
                ) { "Completed network wallet intent without wallet id: $intentId" }
            NetworkWalletCreationStatus.CONFLICT -> throw ConflictException("networkWallet", intentId)
            NetworkWalletCreationStatus.PREPARED, NetworkWalletCreationStatus.SUBMITTING, NetworkWalletCreationStatus.RECOVERING ->
                throw ProvisioningPendingException(intentId, lastReason ?: status.name, retryAfterSeconds)
        }

    fun requireRecovery(expectedRevision: Long) {
        if (revision != expectedRevision ||
            status !in setOf(NetworkWalletCreationStatus.SUBMITTING, NetworkWalletCreationStatus.RECOVERING)
        ) {
            throw ConflictException("networkWalletRecoveryState", intentId)
        }
    }

    fun requirePage(
        expectedRevision: Long,
        page: NetworkWalletRecoveryPage,
    ) {
        requireRecovery(expectedRevision)
        if (status != NetworkWalletCreationStatus.RECOVERING || scanId != page.scanId || scanComplete || nextCursor != page.cursor) {
            throw ConflictException("networkWalletRecoveryPage", intentId)
        }
    }

    fun recoveryResult(
        decision: NetworkWalletRecoveryDecision,
        candidates: List<NetworkWalletObservation>,
        walletAvailable: Boolean,
    ): NetworkWalletRecoveryResult =
        when (decision) {
            is NetworkWalletRecoveryDecision.Pending ->
                NetworkWalletRecoveryResult(
                    NetworkWalletCreationStatus.RECOVERING,
                    candidates.distinct().singleOrNull()?.vendorWalletId ?: knownWalletId,
                    decision.reason.name,
                )
            is NetworkWalletRecoveryDecision.Conflict ->
                NetworkWalletRecoveryResult(NetworkWalletCreationStatus.CONFLICT, knownWalletId, decision.reason.name)
            is NetworkWalletRecoveryDecision.Ready ->
                if (walletAvailable) {
                    NetworkWalletRecoveryResult(NetworkWalletCreationStatus.COMPLETED, decision.wallet.vendorWalletId, null)
                } else {
                    NetworkWalletRecoveryResult(NetworkWalletCreationStatus.CONFLICT, knownWalletId, "WALLET_ALREADY_BOUND")
                }
        }
}

data class NetworkWalletRecoveryResult(
    val status: NetworkWalletCreationStatus,
    val knownWalletId: String?,
    val reason: String?,
)

enum class NetworkWalletCreationStatus {
    PREPARED,
    SUBMITTING,
    RECOVERING,
    COMPLETED,
    CONFLICT,
}

/** 성공적으로 읽은 페이지. 오류/미지원 조회를 빈 페이지로 만들어 전달해서는 안 된다. */
data class NetworkWalletRecoveryPage(
    val pageId: String,
    val scanId: String,
    val cursor: String?,
    val next: String?,
    val candidates: List<NetworkWalletObservation>,
    val evidenceReference: String,
    val evidenceHash: String,
    val observedAt: String,
) {
    init {
        requireLedgerValue(pageId, 64)
        requireLedgerValue(scanId, 64)
        cursor?.let { requireLedgerValue(it) }
        next?.let { requireLedgerValue(it) }
        requireLedgerValue(evidenceReference, 512)
        require(evidenceHash.matches(Regex("[0-9a-f]{64}"))) { "Invalid wallet observation hash" }
        require(observedAt.matches(Regex("[0-9]{14}"))) { "Invalid wallet observation time" }
        candidates.forEach {
            requireLedgerValue(it.network, 20)
            requireLedgerValue(it.vendorWalletId, 64)
            it.correlationId?.let { value -> requireLedgerValue(value, 64) }
            it.address?.let { value -> requireLedgerValue(value, 256) }
        }
    }
}

private fun requireLedgerValue(
    value: String,
    maximumLength: Int = Int.MAX_VALUE,
) {
    require(value.isNotBlank() && value == value.trim() && value.length <= maximumLength) { "Invalid network wallet ledger value" }
}
