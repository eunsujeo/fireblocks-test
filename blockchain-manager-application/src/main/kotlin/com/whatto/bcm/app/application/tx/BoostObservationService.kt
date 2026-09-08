package com.whatto.bcm.app.application.tx

import com.whatto.bcm.domain.tx.BoostAttempt
import com.whatto.bcm.domain.tx.BoostAttemptRepository
import org.springframework.stereotype.Service

/** 교체 거래 관찰을 논리 root에 연결한다. 기록은 호출자의 트랜잭션에 참여한다. */
@Service
class BoostObservationService(
    private val repository: BoostAttemptRepository,
) {
    fun findByExternalTransactionId(id: String): BoostAttempt? = repository.findByExternalTransactionId(id)

    fun findByNewVendorTransactionId(id: String): BoostAttempt? = repository.findByNewVendorTransactionId(id)

    fun findLatestViableByRoot(id: String): BoostAttempt? = repository.findLatestViableByRoot(id)

    fun markSubmittedByObservation(
        externalTransactionId: String,
        vendorTransactionId: String,
        observedAt: String,
    ): BoostAttempt = repository.markSubmittedByObservation(externalTransactionId, vendorTransactionId, observedAt)
}
