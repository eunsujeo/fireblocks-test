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
    /**
     * 관찰이 말하는 금액 — **사람 단위로 이미 환산된 값**이다. 환산은 판단 단계가 한 번만 하고
     * 저장은 다시 읽지 않는다(03 V34). 모르면 null이다.
     */
    val observedAmount: String? = null,
    /** 환산 근거. 최소 단위로 관찰된 경로만 채운다 — 사람 단위가 이미 오는 경로는 남길 근거가 없다. */
    val observedAmountBaseUnits: String? = null,
    val observedAmountDecimals: Int? = null,
    /** 관찰이 말하는 발신·수신 온체인 주소. 체인에 오르기 전에는 null이다. */
    val observedSourceAddress: String? = null,
    val observedDestinationAddress: String? = null,
)

data class TxStateChange(
    val record: TxRecord,
    val statusesToPublish: List<TxStatus>,
)

/** root 한 행에 물리 RBF 계열의 관찰을 직렬화해 반영하는 도메인 서비스. */
class TxStateMachine(
    private val repository: TxRecordRepository,
) {
    fun observe(
        observation: TxObservation,
        attributedType: TxType? = null,
    ): TxStateChange = observeRoot(observation.vendorTransactionId, observation, successEvidence = false, attributedType = attributedType)

    fun observeRoot(
        rootVendorTransactionId: String,
        observation: TxObservation,
        successEvidence: Boolean,
        deferFailure: Boolean = false,
        attributedType: TxType? = null,
    ): TxStateChange =
        observeLocked(
            previous = repository.findByVendorTxIdForUpdate(rootVendorTransactionId),
            rootVendorTransactionId = rootVendorTransactionId,
            observation = observation,
            successEvidence = successEvidence,
            deferFailure = deferFailure,
            attributedType = attributedType,
        )

    /**
     * **이미 잠근 행**으로 전이를 반영한다. 호출자가 잠근 뒤 [TxObservationConsistency]로 먼저 묻고
     * 일치한 관찰만 여기 넘긴다 — 여기서 다시 읽으면 이중 조회이거나 그 사이가 벌어진다(TOCTOU).
     *
     * 이 함수는 **격리를 모른다.** 충돌 판정과 인박스 격리는 호출자 쪽 책임이다.
     */
    fun observeLocked(
        previous: TxRecord?,
        rootVendorTransactionId: String,
        observation: TxObservation,
        successEvidence: Boolean,
        deferFailure: Boolean = false,
        attributedType: TxType? = null,
    ): TxStateChange {
        if (previous == null) {
            check(rootVendorTransactionId == observation.vendorTransactionId) {
                "root transaction not found: rootVendorTransactionId=$rootVendorTransactionId"
            }
            return persistNew(observation, attributedType)
        }
        if (previous.activeVendorTxId != observation.vendorTransactionId) {
            if (previous.lastPublishedStatus == TxStatus.FAILED) return TxStateChange(previous, emptyList())
            if (!successEvidence || previous.hasMinedWinner()) return TxStateChange(previous, emptyList())
            return adoptWinner(previous, observation, attributedType)
        }
        if (
            deferFailure &&
            observation.status == TxStatus.FAILED &&
            previous.lastPublishedStatus in BoostPolicy.rootStatuses
        ) {
            val newerObservation = observation.observedAt > previous.lastChangedAt
            val deferred =
                repository.update(
                    previous.copy(
                        stallAlertedAt = null,
                        // 이 분기는 candidate()를 거치지 않는다 — 병합을 여기서도 해야 첫 관찰이 이 경로일 때 값이 남는다.
                        vendorCreatedAt = previous.vendorCreatedAt ?: observation.vendorCreatedAt,
                        amount = previous.amount ?: observation.observedAmount,
                        amountBaseUnits = previous.amountBaseUnits ?: observation.observedAmountBaseUnits,
                        amountDecimals = previous.amountDecimals ?: observation.observedAmountDecimals,
                        sourceAddress = previous.sourceAddress ?: observation.observedSourceAddress,
                        destinationAddress = previous.destinationAddress ?: observation.observedDestinationAddress,
                        lastChangedAt = maxOf(previous.lastChangedAt, observation.observedAt),
                        reconciliationCheckedAt = previous.reconciliationCheckedAt.takeUnless { newerObservation },
                        reconciliationCheckCount = previous.reconciliationCheckCount.takeUnless { newerObservation } ?: 0,
                        reconciliationStoppedAt = previous.reconciliationStoppedAt.takeUnless { newerObservation },
                    ),
                )
            return TxStateChange(deferred, emptyList())
        }
        return persistActive(previous, observation, attributedType)
    }

    private fun persistNew(
        observation: TxObservation,
        attributedType: TxType?,
    ): TxStateChange {
        val decision = TransitionTable.decide(null, observation.status)
        val record =
            repository.insert(candidate(null, observation, decision.statusToRecord(null, observation.status), attributedType))
        return TxStateChange(record, decision.publishedStatuses(observation.status))
    }

    private fun persistActive(
        previous: TxRecord,
        observation: TxObservation,
        attributedType: TxType?,
    ): TxStateChange {
        val decision = TransitionTable.decide(previous.lastPublishedStatus, observation.status)
        val candidate =
            candidate(
                previous,
                observation,
                decision.statusToRecord(previous.lastPublishedStatus, observation.status),
                attributedType,
            )
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
        attributedType: TxType?,
    ): TxStateChange {
        val decision = TransitionTable.decide(previous.lastPublishedStatus, observation.status)
        val candidate =
            candidate(
                previous.copy(transactionHash = null),
                observation,
                decision.statusToRecord(previous.lastPublishedStatus, observation.status),
                attributedType,
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
        attributedType: TxType?,
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
        // 금액·주소는 최초값을 보존하고 null 자리만 채운다. 다른 값이 오는 경우는 여기 오기 전에
        // [TxObservationConsistency]가 걸러 관찰 전체를 격리한다(03 V32).
        amount = previous?.amount ?: observation.observedAmount,
        amountBaseUnits = previous?.amountBaseUnits ?: observation.observedAmountBaseUnits,
        amountDecimals = previous?.amountDecimals ?: observation.observedAmountDecimals,
        sourceAddress = previous?.sourceAddress ?: observation.observedSourceAddress,
        destinationAddress = previous?.destinationAddress ?: observation.observedDestinationAddress,
        // 관찰로 정하지 않는다 — 거래 행을 만든 쪽이 확정한 권위 값이다. 최초에 정하고 그 뒤로는 바꾸지 않는다.
        transactionType = mergedTransactionType(previous, attributedType),
    )

    /**
     * 권위 분류는 최초값을 지킨다. 이미 있는 값과 다른 분류가 오면 **우리 원장끼리 어긋난 것**이라
     * 격리가 아니라 그 자리에서 드러낸다 — 벤더가 준 값이 아니므로 재시도로 해소되지 않는다.
     */
    private fun mergedTransactionType(
        previous: TxRecord?,
        attributedType: TxType?,
    ): TxType? {
        val recorded = previous?.transactionType ?: return attributedType
        check(attributedType == null || attributedType == recorded) {
            "transaction type mismatch: recorded=$recorded attributed=$attributedType"
        }
        return recorded
    }

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
