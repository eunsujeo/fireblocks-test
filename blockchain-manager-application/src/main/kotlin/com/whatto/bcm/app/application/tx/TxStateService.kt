package com.whatto.bcm.app.application.tx

import com.whatto.bcm.domain.tx.TxObservation
import com.whatto.bcm.domain.tx.TxRecordRepository
import com.whatto.bcm.domain.tx.TxStateChange
import com.whatto.bcm.domain.tx.TxStateMachine
import org.springframework.stereotype.Service

/** 거래 피처의 행 잠금·전이 판정·상태 저장을 한 경계로 묶는다. 호출자는 외부 트랜잭션 안에 있어야 한다. */
@Service
class TxStateService(
    private val repository: TxRecordRepository,
) {
    private val stateMachine = TxStateMachine(repository)

    /**
     * 온체인 hash로 거래 후보를 찾는다(V26 index). **hash는 유일하지 않으므로 목록이다** —
     * 단일 후보 판정은 호출자의 업무 규칙이다(계약13 발신 이동 대조).
     */
    fun findByNetworkAndTransactionHash(
        network: String,
        transactionHash: String,
    ) = repository.findByNetworkAndTransactionHash(network, transactionHash)

    fun observe(observation: TxObservation): TxStateChange = stateMachine.observe(observation)

    fun observeRoot(
        rootVendorTransactionId: String,
        observation: TxObservation,
        successEvidence: Boolean,
        deferFailure: Boolean = false,
    ): TxStateChange = stateMachine.observeRoot(rootVendorTransactionId, observation, successEvidence, deferFailure)
}
