package com.whatto.bcm.app.application.tx

import com.whatto.bcm.domain.tx.TxObservation
import com.whatto.bcm.domain.tx.TxRecordRepository
import com.whatto.bcm.domain.tx.TxStateChange
import com.whatto.bcm.domain.tx.TxStateMachine
import org.springframework.stereotype.Service

/** 거래 피처의 행 잠금·전이 판정·상태 저장을 한 경계로 묶는다. 호출자는 외부 트랜잭션 안에 있어야 한다. */
@Service
class TxStateService(
    repository: TxRecordRepository,
) {
    private val stateMachine = TxStateMachine(repository)

    fun observe(observation: TxObservation): TxStateChange = stateMachine.observe(observation)

    fun observeRoot(
        rootVendorTransactionId: String,
        observation: TxObservation,
        successEvidence: Boolean,
    ): TxStateChange = stateMachine.observeRoot(rootVendorTransactionId, observation, successEvidence)
}
