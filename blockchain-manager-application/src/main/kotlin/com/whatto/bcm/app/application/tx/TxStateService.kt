package com.whatto.bcm.app.application.tx

import com.whatto.bcm.domain.asset.VendorBlockchainCatalogRepository
import com.whatto.bcm.domain.tx.TxObservation
import com.whatto.bcm.domain.tx.TxObservationConsistency
import com.whatto.bcm.domain.tx.TxRecord
import com.whatto.bcm.domain.tx.TxRecordRepository
import com.whatto.bcm.domain.tx.TxStateChange
import com.whatto.bcm.domain.tx.TxStateMachine
import com.whatto.bcm.domain.tx.TxType
import org.springframework.stereotype.Service

/**
 * 거래 피처의 행 잠금·전이 판정·상태 저장을 한 경계로 묶는다. 호출자는 외부 트랜잭션 안에 있어야 한다.
 *
 * **관찰을 반영하는 길은 동일성 검사를 지나는 것 하나다**(03 V32) — 검사를 건너뛰는 입구는 두지 않는다.
 * 한 행이면 [observeConsistently], 한 트랜잭션에서 여러 행이면 [lockAndCheck] 전부 → [applyChecked] 전부다.
 */
@Service
class TxStateService(
    private val repository: TxRecordRepository,
    private val blockchains: VendorBlockchainCatalogRepository,
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

    /**
     * 관찰을 **잠그고 검사만** 한다 — 전이도 쓰기도 하지 않는다(03 V32 "충돌한 관찰은 통째로 격리한다").
     *
     * 검사와 쓰기를 가른 이유는 **한 트랜잭션이 여러 행을 다루는 경로** 때문이다(발신 좌표 적용).
     * 행마다 검사·쓰기를 붙여 돌면 뒤 행의 충돌로 격리할 때 앞 행의 전이가 같은 트랜잭션에 실려 함께 커밋된다.
     * 잠금은 트랜잭션 끝까지 남으므로 **전부 검사한 뒤** 쓰기를 시작해도 그 사이가 벌어지지 않는다.
     */
    fun lockAndCheck(
        rootVendorTransactionId: String,
        observation: TxObservation,
    ): TxObservationCheck {
        val previous = repository.findByVendorTxIdForUpdate(rootVendorTransactionId)
        // 주소 비교 규칙은 네트워크의 계정 모델이 가른다(03 `chain_mdl_dvcd`). 모르면 정확히 같을 때만 같다고 본다.
        val chainModel = blockchains.findByNetwork(observation.network)?.chainModel
        return when (val consistency = TxObservationConsistency.check(previous, observation, chainModel)) {
            is TxObservationConsistency.Result.Conflict -> TxObservationCheck.Conflict(consistency)
            TxObservationConsistency.Result.Consistent ->
                TxObservationCheck.Consistent(rootVendorTransactionId, previous, observation)
        }
    }

    /** [lockAndCheck]가 통과시킨 관찰만 반영한다 — 잠근 행을 그대로 넘겨 이중 조회와 그 사이의 틈을 없앤다. */
    fun applyChecked(
        checked: TxObservationCheck.Consistent,
        successEvidence: Boolean,
        deferFailure: Boolean = false,
        attributedType: TxType? = null,
    ): TxStateChange =
        stateMachine.observeLocked(
            previous = checked.previous,
            rootVendorTransactionId = checked.rootVendorTransactionId,
            observation = checked.observation,
            successEvidence = successEvidence,
            deferFailure = deferFailure,
            attributedType = attributedType,
        )

    /**
     * 한 행만 다루는 경로의 축약 — 잠금 → **동일성 검사** → 전이.
     *
     * 검사가 전이보다 **먼저**여야 한다 — 뒤에서 잡으면 상태·컨펌·hash가 이미 바뀐 뒤다.
     * 충돌을 인박스 격리로 옮기는 것은 호출자(웹훅 워커) 몫이다 — 이 경계는 인박스를 모른다.
     * 호출자의 **다른 쓰기도 이 호출 뒤에 와야 한다** — 앞서 쓰면 격리와 함께 커밋된다(같은 트랜잭션이다).
     */
    fun observeConsistently(
        rootVendorTransactionId: String,
        observation: TxObservation,
        successEvidence: Boolean,
        deferFailure: Boolean = false,
        attributedType: TxType? = null,
    ): TxObservationOutcome =
        when (val checked = lockAndCheck(rootVendorTransactionId, observation)) {
            is TxObservationCheck.Conflict -> TxObservationOutcome.Conflict(checked.detail)
            is TxObservationCheck.Consistent ->
                TxObservationOutcome.Applied(applyChecked(checked, successEvidence, deferFailure, attributedType))
        }
}

/** [TxStateService.lockAndCheck]의 결과. 충돌은 예외가 아니라 값이다 — 호출자가 격리로 번역한다. */
sealed interface TxObservationCheck {
    /** 받아들일 수 있다. 잠근 행을 그대로 들고 있어 [TxStateService.applyChecked]가 다시 읽지 않는다. */
    data class Consistent(
        val rootVendorTransactionId: String,
        val previous: TxRecord?,
        val observation: TxObservation,
    ) : TxObservationCheck

    data class Conflict(
        val detail: TxObservationConsistency.Result.Conflict,
    ) : TxObservationCheck
}

/** [TxStateService.observeConsistently]의 결과. */
sealed interface TxObservationOutcome {
    data class Applied(
        val change: TxStateChange,
    ) : TxObservationOutcome

    data class Conflict(
        val detail: TxObservationConsistency.Result.Conflict,
    ) : TxObservationOutcome
}
