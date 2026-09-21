package com.whatto.bcm.app.application.tx

import com.whatto.bcm.domain.asset.ChainModel
import com.whatto.bcm.domain.tx.TxObservation
import com.whatto.bcm.domain.tx.TxObservationConsistency
import com.whatto.bcm.domain.tx.TxRecordRepository
import com.whatto.bcm.domain.tx.TxStateChange
import com.whatto.bcm.domain.tx.TxStateMachine
import com.whatto.bcm.domain.tx.TxType
import org.springframework.stereotype.Service

/** 거래 피처의 행 잠금·전이 판정·상태 저장을 한 경계로 묶는다. 호출자는 외부 트랜잭션 안에 있어야 한다. */
@Service
class TxStateService(
    private val repository: TxRecordRepository,
) {
    private val stateMachine = TxStateMachine(repository)

    /**
     * 온체인 hash로 거래를 찾는다(V26 index). **hash는 유일하지 않으므로 목록이다** —
     * 그중 어느 행에 사건의 블록 좌표를 적용할지는 호출자의 업무 규칙이다(계약13 발신 확정의 블록 좌표).
     */
    fun findByNetworkAndTransactionHash(
        network: String,
        transactionHash: String,
    ) = repository.findByNetworkAndTransactionHash(network, transactionHash)

    /** `(network, transactionHash)` 직렬화 경계 — 거래를 만드는 쪽과 좌표를 적용하는 쪽이 같은 경계를 잡는다(계약13). */
    fun lockNetworkTransactionHash(
        network: String,
        transactionHash: String,
    ) = repository.lockNetworkTransactionHash(network, transactionHash)

    fun observe(
        observation: TxObservation,
        attributedType: TxType? = null,
    ): TxStateChange = stateMachine.observe(observation, attributedType)

    fun observeRoot(
        rootVendorTransactionId: String,
        observation: TxObservation,
        successEvidence: Boolean,
        deferFailure: Boolean = false,
        attributedType: TxType? = null,
    ): TxStateChange = stateMachine.observeRoot(rootVendorTransactionId, observation, successEvidence, deferFailure, attributedType)

    /**
     * 잠금 → **동일성 검사** → 전이 순서로 관찰을 반영한다(03 V32 "충돌한 관찰은 통째로 격리한다").
     *
     * 검사가 전이보다 **먼저**여야 한다 — 뒤에서 잡으면 상태·컨펌·hash가 이미 바뀐 뒤다.
     * 잠근 행을 그대로 상태 머신에 넘겨 이중 조회와 그 사이의 틈을 없앤다.
     * 충돌을 인박스 격리로 옮기는 것은 호출자(웹훅 워커) 몫이다 — 이 경계는 인박스를 모른다.
     */
    fun observeConsistently(
        rootVendorTransactionId: String,
        observation: TxObservation,
        chainModel: ChainModel?,
        successEvidence: Boolean,
        deferFailure: Boolean = false,
        attributedType: TxType? = null,
    ): TxObservationOutcome {
        val previous = repository.findByVendorTxIdForUpdate(rootVendorTransactionId)
        return when (val consistency = TxObservationConsistency.check(previous, observation, chainModel)) {
            is TxObservationConsistency.Result.Conflict -> TxObservationOutcome.Conflict(consistency)
            TxObservationConsistency.Result.Consistent ->
                TxObservationOutcome.Applied(
                    stateMachine.observeLocked(
                        previous,
                        rootVendorTransactionId,
                        observation,
                        successEvidence,
                        deferFailure,
                        attributedType,
                    ),
                )
        }
    }
}

/** [TxStateService.observeConsistently]의 결과. 충돌은 예외가 아니라 값이다 — 호출자가 격리로 번역한다. */
sealed interface TxObservationOutcome {
    data class Applied(
        val change: TxStateChange,
    ) : TxObservationOutcome

    data class Conflict(
        val detail: TxObservationConsistency.Result.Conflict,
    ) : TxObservationOutcome
}
