package com.whatto.bcm.app.application.submission

import com.whatto.bcm.domain.submission.SubmissionRecord
import com.whatto.bcm.domain.submission.SubmissionRecordRepository
import org.springframework.stereotype.Service

/** 서명 검증된 관찰을 제출 원장에 연결한다. 기록은 호출자의 트랜잭션에 참여한다. */
@Service
class SubmissionObservationService(
    private val repository: SubmissionRecordRepository,
) {
    fun findByExternalTransactionId(id: String): SubmissionRecord? = repository.findByExternalTransactionId(id)

    fun findByVendorTransactionId(id: String): SubmissionRecord? = repository.findByVendorTransactionId(id)

    fun markSubmitted(
        externalTransactionId: String,
        vendorTransactionId: String,
        observedAt: String,
    ): SubmissionRecord = repository.markSubmitted(externalTransactionId, vendorTransactionId, observedAt)
}
