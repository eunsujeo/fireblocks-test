package com.whatto.bcm.domain.tx

import com.whatto.bcm.domain.exception.ConflictException

data class TxObservation(
    val vendorTransactionId: String,
    val externalTransactionId: String?,
    val accountId: String,
    val network: String,
    val symbol: String,
    val transactionHash: String?,
    val status: TxStatus,
    val confirmationCount: Int,
    val vendorSubStatus: String?,
    val vendorNetworkStatus: String?,
    val observedAt: String,
    val vendorCreatedAt: String = observedAt,
)

data class TxStateChange(
    val record: TxRecord,
    val statusesToPublish: List<TxStatus>,
)

/** root 한 행에 물리 RBF 계열의 관찰을 직렬화해 반영하는 도메인 서비스. */
class TxStateMachine(
    private val repository: TxRecordRepository,
) {
    fun observe(observation: TxObservation): TxStateChange =
        observeRoot(observation.vendorTransactionId, observation, successEvidence = false)

    fun observeRoot(
        rootVendorTransactionId: String,
        observation: TxObservation,
        successEvidence: Boolean,
        deferFailure: Boolean = false,
    ): TxStateChange {
        val previous = repository.findByVendorTxIdForUpdate(rootVendorTransactionId)
        if (previous == null) {
            check(rootVendorTransactionId == observation.vendorTransactionId) {
                "root transaction not found: rootVendorTransactionId=$rootVendorTransactionId"
            }
            return persistNew(observation)
        }
        if (previous.activeVendorTxId != observation.vendorTransactionId) {
            if (previous.lastPublishedStatus == TxStatus.FAILED) return TxStateChange(previous, emptyList())
            if (!successEvidence || previous.hasMinedWinner()) return TxStateChange(previous, emptyList())
            return adoptWinner(previous, observation)
        }
        if (
            deferFailure &&
            observation.status == TxStatus.FAILED &&
            previous.lastPublishedStatus in BoostPolicy.rootStatuses
        ) {
            val deferred =
                repository.update(
                    previous.copy(
                        stallAlertedAt = null,
                        lastChangedAt = maxOf(previous.lastChangedAt, observation.observedAt),
                        reconciliationCheckedAt = null,
                        reconciliationCheckCount = 0,
                        reconciliationStoppedAt = null,
                    ),
                )
            return TxStateChange(deferred, emptyList())
        }
        return persistActive(previous, observation)
    }

    private fun persistNew(observation: TxObservation): TxStateChange {
        val decision = TransitionTable.decide(null, observation.status)
        val record = repository.insert(candidate(null, observation, decision.statusToRecord(null, observation.status)))
        return TxStateChange(record, decision.publishedStatuses(observation.status))
    }

    private fun persistActive(
        previous: TxRecord,
        observation: TxObservation,
    ): TxStateChange {
        val decision = TransitionTable.decide(previous.lastPublishedStatus, observation.status)
        val candidate = candidate(previous, observation, decision.statusToRecord(previous.lastPublishedStatus, observation.status))
        val madeProgress = madeProgress(previous, candidate)
        val newerObservation = observation.observedAt > previous.lastChangedAt
        val record =
            repository.update(
                candidate.copy(
                    vendorTxId = previous.vendorTxId,
                    activeVendorTxId = previous.activeVendorTxId,
                    externalTxId = previous.externalTxId ?: candidate.externalTxId,
                    stallAlertedAt = previous.stallAlertedAt.takeUnless { madeProgress },
                    reconciliationCheckedAt = previous.reconciliationCheckedAt.takeUnless { newerObservation },
                    reconciliationCheckCount = previous.reconciliationCheckCount.takeUnless { newerObservation } ?: 0,
                    reconciliationStoppedAt = previous.reconciliationStoppedAt.takeUnless { newerObservation },
                ),
            )
        return TxStateChange(record, decision.publishedStatuses(observation.status))
    }

    private fun adoptWinner(
        previous: TxRecord,
        observation: TxObservation,
    ): TxStateChange {
        val decision = TransitionTable.decide(previous.lastPublishedStatus, observation.status)
        val candidate =
            candidate(
                previous.copy(transactionHash = null),
                observation,
                decision.statusToRecord(previous.lastPublishedStatus, observation.status),
            ).copy(
                vendorTxId = previous.vendorTxId,
                activeVendorTxId = observation.vendorTransactionId,
                externalTxId = previous.externalTxId,
                stallAlertedAt = null,
            )
        val record = repository.updatePhysicalWinner(candidate, previous.activeVendorTxId)
        return TxStateChange(record, decision.publishedStatuses(observation.status))
    }

    private fun candidate(
        previous: TxRecord?,
        observation: TxObservation,
        statusToRecord: TxStatus,
    ) = TxRecord(
        vendorTxId = observation.vendorTransactionId,
        externalTxId = observation.externalTransactionId,
        accountId = observation.accountId,
        network = observation.network,
        symbol = observation.symbol,
        transactionHash = mergedTransactionHash(previous, observation),
        lastPublishedStatus = statusToRecord,
        confirmationCount = observation.confirmationCount,
        vendorSubStatus = observation.vendorSubStatus,
        vendorNetworkStatus = observation.vendorNetworkStatus,
        firstDetectedAt = previous?.firstDetectedAt ?: observation.observedAt,
        lastChangedAt = observation.observedAt,
        vendorCreatedAt = previous?.vendorCreatedAt ?: observation.vendorCreatedAt,
    )

    private fun madeProgress(
        previous: TxRecord,
        candidate: TxRecord,
    ): Boolean =
        candidate.lastPublishedStatus != previous.lastPublishedStatus ||
            candidate.confirmationCount > previous.confirmationCount ||
            (previous.transactionHash == null && candidate.transactionHash != null)

    private fun mergedTransactionHash(
        previous: TxRecord?,
        observation: TxObservation,
    ): String? {
        val recorded = previous?.transactionHash
        val observed = observation.transactionHash
        if (recorded != null && observed != null && recorded != observed) {
            throw ConflictException("transactionHash", observation.vendorTransactionId)
        }
        return recorded ?: observed
    }

    private fun TxRecord.hasMinedWinner(): Boolean = confirmationCount > 0 || lastPublishedStatus == TxStatus.FINALIZED
}
