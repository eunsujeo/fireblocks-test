package com.whatto.bcm.app.application.wallet

import com.whatto.bcm.app.application.account.AccountQueryService
import com.whatto.bcm.domain.exception.VendorApiException
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
        val observed = observe(claimed, NetworkWalletEvidenceOperation.CREATE) { vendor.create(claimed.request, claimed.submission) }
        val scan = repository.startRecovery(claimed.request.scope, claimed.revision, UUID.randomUUID().toString(), CoreDateTimes.now(clock))
        return record(scan, listOf(observed.response.value), null, observed.stored, observed.observedAt)
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
            val observed = observe(scan, NetworkWalletEvidenceOperation.READ) { vendor.read(scan.request.scope, knownId) }
            record(scan, listOfNotNull(observed.response.value), null, observed.stored, observed.observedAt)
        } else {
            val observed = observe(scan, NetworkWalletEvidenceOperation.DISCOVER) { vendor.candidates(scan.request, scan.nextCursor) }
            record(scan, observed.response.value.data, observed.response.value.next, observed.stored, observed.observedAt)
        }
    }

    /**
     * 벤더 호출과 원문 보관을 묶는다. 성공 응답은 보관 뒤 hash를 대조하고, 실패 응답도 수신 바이트가 있으면 같은 작업 종류로 먼저 보관한 뒤
     * 오류를 전파한다 — 원장 페이지/cursor는 전진하지 않는다(계약13·03 V24). 응답을 받지 못한 실패(연결·timeout)는 보관할 바이트가 없다.
     */
    private fun <T> observe(
        intent: NetworkWalletCreationIntent,
        operation: NetworkWalletEvidenceOperation,
        call: () -> NetworkWalletResponse<T>,
    ): ObservedResponse<T> {
        val response =
            try {
                call()
            } catch (failure: VendorApiException) {
                failure.responseBody()?.let { body ->
                    try {
                        store(intent, operation, body, CoreDateTimes.now(clock))
                    } catch (storeFailure: RuntimeException) {
                        failure.addSuppressed(storeFailure)
                    }
                }
                throw failure
            }
        val observedAt = CoreDateTimes.now(clock)
        return ObservedResponse(response, store(intent, operation, response.bodyBytes(), observedAt), observedAt)
    }

    private fun store(
        intent: NetworkWalletCreationIntent,
        operation: NetworkWalletEvidenceOperation,
        body: ByteArray,
        observedAt: String,
    ): StoredNetworkWalletEvidence {
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

    private class ObservedResponse<T>(
        val response: NetworkWalletResponse<T>,
        val stored: StoredNetworkWalletEvidence,
        val observedAt: String,
    )

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
