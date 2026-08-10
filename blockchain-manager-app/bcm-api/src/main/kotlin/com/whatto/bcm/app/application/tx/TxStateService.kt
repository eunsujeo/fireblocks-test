package com.whatto.bcm.app.application.tx

import com.whatto.bcm.domain.tx.TransitionTable
import com.whatto.bcm.domain.tx.TxRecord
import com.whatto.bcm.domain.tx.TxRecordRepository
import com.whatto.bcm.domain.tx.TxStatus
import org.springframework.stereotype.Service

data class TxObservation(
    val vendorTransactionId: String,
    val externalTransactionId: String?,
    val accountId: String,
    val network: String,
    val symbol: String,
    val status: TxStatus,
    val confirmationCount: Int,
    val vendorSubStatus: String?,
    val vendorNetworkStatus: String?,
    val observedAt: String,
)

data class TxStateChange(
    val record: TxRecord,
    val statusesToPublish: List<TxStatus>,
)

/** 거래 피처의 행 잠금·전이 판정·상태 저장을 한 경계로 묶는다. 호출자는 외부 트랜잭션 안에 있어야 한다. */
@Service
class TxStateService(
    private val repository: TxRecordRepository,
) {
    fun observe(observation: TxObservation): TxStateChange {
        val previous = repository.findByVendorTxIdForUpdate(observation.vendorTransactionId)
        val decision = TransitionTable.decide(previous?.lastPublishedStatus, observation.status)
        val record = persist(previous, observation, decision.statusToRecord(previous?.lastPublishedStatus, observation.status))
        return TxStateChange(record, decision.publishedStatuses(observation.status))
    }

    private fun persist(
        previous: TxRecord?,
        observation: TxObservation,
        statusToRecord: TxStatus,
    ): TxRecord {
        val candidate =
            TxRecord(
                vendorTxId = observation.vendorTransactionId,
                externalTxId = observation.externalTransactionId,
                accountId = observation.accountId,
                network = observation.network,
                symbol = observation.symbol,
                lastPublishedStatus = statusToRecord,
                confirmationCount = observation.confirmationCount,
                vendorSubStatus = observation.vendorSubStatus,
                vendorNetworkStatus = observation.vendorNetworkStatus,
                firstDetectedAt = previous?.firstDetectedAt ?: observation.observedAt,
                lastChangedAt = observation.observedAt,
            )
        return if (previous == null) {
            repository.insert(candidate)
        } else {
            repository.update(
                candidate.copy(
                    originTxId = previous.originTxId,
                    externalTxId = previous.externalTxId ?: candidate.externalTxId,
                    stallAlertedAt = previous.stallAlertedAt,
                ),
            )
        }
    }
}
