package com.whatto.bcm.app.application.wallet

import com.whatto.bcm.app.application.account.AccountQueryService
import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.vendor.NetworkWalletObservation
import com.whatto.bcm.domain.vendor.NetworkWalletProvisioningPort
import com.whatto.bcm.domain.vendor.NetworkWalletResponse
import com.whatto.bcm.domain.wallet.NetworkWalletCreationIntent
import com.whatto.bcm.domain.wallet.NetworkWalletCreationSeed
import com.whatto.bcm.domain.wallet.NetworkWalletCreationStatus
import com.whatto.bcm.domain.wallet.NetworkWalletEvidenceContext
import com.whatto.bcm.domain.wallet.NetworkWalletEvidenceOperation
import com.whatto.bcm.domain.wallet.NetworkWalletEvidenceStore
import com.whatto.bcm.domain.wallet.NetworkWalletProvisioningRepository
import com.whatto.bcm.domain.wallet.NetworkWalletRecoveryPage
import com.whatto.bcm.domain.wallet.StoredNetworkWalletEvidence
import com.whatto.bcm.support.time.CoreDateTimes
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID

/** 내부 조립용 유스케이스. 실제 벤더/증적 어댑터 수용 전 기본 실행 빈과 공개 API에는 연결하지 않는다. */
class NetworkWalletProvisioningService(
    private val repository: NetworkWalletProvisioningRepository,
    private val vendor: NetworkWalletProvisioningPort,
    private val evidence: NetworkWalletEvidenceStore,
    private val accounts: AccountQueryService,
    private val origin: ProviderOrigin,
    private val clock: Clock,
) {
    fun provision(seed: NetworkWalletCreationSeed): NetworkWalletCreationIntent {
        require(origin.protocolProvider == "dfns") { "Network wallet provisioning requires the Dfns origin" }
        origin.requireMatch(seed.request.scope.origin)
        accounts.requiredAccount(seed.request.scope.accountId).requireLogical()
        val intent = repository.reserve(seed, CoreDateTimes.now(clock))
        return when (intent.status) {
            NetworkWalletCreationStatus.COMPLETED, NetworkWalletCreationStatus.CONFLICT -> intent
            NetworkWalletCreationStatus.PREPARED -> createOnce(intent)
            NetworkWalletCreationStatus.SUBMITTING, NetworkWalletCreationStatus.RECOVERING -> recoverPage(intent)
        }
    }

    private fun createOnce(intent: NetworkWalletCreationIntent): NetworkWalletCreationIntent {
        val claimed =
            repository.claimSubmission(intent.request.scope, intent.revision, CoreDateTimes.now(clock))
                ?: return checkNotNull(repository.find(intent.request.scope))
        val response = vendor.create(claimed.request, claimed.submission)
        val observedAt = CoreDateTimes.now(clock)
        val stored = capture(claimed, NetworkWalletEvidenceOperation.CREATE, response, observedAt)
        val scan = repository.startRecovery(claimed.request.scope, claimed.revision, UUID.randomUUID().toString(), CoreDateTimes.now(clock))
        return record(scan, listOf(response.value), null, stored, observedAt)
    }

    private fun recoverPage(intent: NetworkWalletCreationIntent): NetworkWalletCreationIntent {
        val scan =
            if (intent.status == NetworkWalletCreationStatus.SUBMITTING || intent.scanComplete) {
                repository.startRecovery(intent.request.scope, intent.revision, UUID.randomUUID().toString(), CoreDateTimes.now(clock))
            } else {
                intent
            }
        val knownId = scan.knownWalletId
        return if (knownId != null && scan.nextCursor == null) {
            val response = vendor.read(scan.request.scope, knownId)
            val observedAt = CoreDateTimes.now(clock)
            val stored = capture(scan, NetworkWalletEvidenceOperation.READ, response, observedAt)
            record(scan, listOfNotNull(response.value), null, stored, observedAt)
        } else {
            val response = vendor.candidates(scan.request, scan.nextCursor)
            val observedAt = CoreDateTimes.now(clock)
            val stored = capture(scan, NetworkWalletEvidenceOperation.DISCOVER, response, observedAt)
            record(scan, response.value.data, response.value.next, stored, observedAt)
        }
    }

    private fun capture(
        intent: NetworkWalletCreationIntent,
        operation: NetworkWalletEvidenceOperation,
        response: NetworkWalletResponse<*>,
        observedAt: String,
    ): StoredNetworkWalletEvidence {
        val body = response.bodyBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val context =
            NetworkWalletEvidenceContext(
                intent.intentId,
                intent.request.scope,
                intent.request.correlationId,
                intent.submission.requestHash,
                operation,
                observedAt,
                intent.nextCursor,
                intent.knownWalletId,
            )
        return evidence.store(context, body).also {
            check(it.hash == hash) { "Stored network wallet evidence hash mismatch: ${intent.intentId}" }
        }
    }

    private fun record(
        scan: NetworkWalletCreationIntent,
        candidates: List<NetworkWalletObservation>,
        next: String?,
        stored: StoredNetworkWalletEvidence,
        observedAt: String,
    ): NetworkWalletCreationIntent =
        repository.recordPage(
            scan.request.scope,
            scan.revision,
            NetworkWalletRecoveryPage(
                UUID.randomUUID().toString(),
                checkNotNull(scan.scanId),
                scan.nextCursor,
                next,
                candidates,
                stored.reference,
                stored.hash,
                observedAt,
            ),
            CoreDateTimes.now(clock),
        )
}
