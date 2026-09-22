package com.whatto.bcm.app.application.tx

import com.whatto.bcm.domain.asset.ChainModel
import com.whatto.bcm.domain.asset.VendorBlockchainCatalogRepository
import com.whatto.bcm.domain.exception.ConflictException
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
 * 판정과 전이 사이에 호출자가 같은 행을 바꾸는 경로는 [lockAndCheck] → 그 쓰기 → [applyCheckedReread]다.
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
        return when (val consistency = check(previous, observation)) {
            is TxObservationConsistency.Result.Conflict -> TxObservationCheck.Conflict(consistency)
            TxObservationConsistency.Result.Consistent ->
                TxObservationCheck.Consistent(rootVendorTransactionId, previous, observation, chainModelOf(previous, observation))
        }
    }

    private fun check(
        previous: TxRecord?,
        observation: TxObservation,
    ): TxObservationConsistency.Result = TxObservationConsistency.check(previous, observation, chainModelOf(previous, observation))

    /** 비교할 행이 없으면 규칙도 필요 없다 — 첫 관찰에 카탈로그를 읽지 않는다. */
    private fun chainModelOf(
        previous: TxRecord?,
        observation: TxObservation,
    ): ChainModel? = previous?.let { blockchains.findByNetwork(observation.network)?.chainModel }

    /** [lockAndCheck]가 통과시킨 관찰만 반영한다 — 잠근 행을 그대로 넘겨 이중 조회와 그 사이의 틈을 없앤다. */
    fun applyChecked(
        checked: TxObservationCheck.Consistent,
        successEvidence: Boolean,
        deferFailure: Boolean = false,
        attributedType: TxType? = null,
    ): TxStateChange {
        checked.requireChecked()
        return stateMachine.observeLocked(
            previous = checked.previous,
            rootVendorTransactionId = checked.rootVendorTransactionId,
            observation = checked.observation,
            successEvidence = successEvidence,
            deferFailure = deferFailure,
            attributedType = attributedType,
        )
    }

    /**
     * [lockAndCheck]로 이미 판정한 관찰을, 그 사이 **호출자 자신이 바꾼 행** 위에 반영한다.
     *
     * 부스트 결속(`markSubmittedByObservation`)은 결속만 하는 게 아니라 root의 활성 거래를 갈아 끼우므로,
     * 판정 때 읽은 행으로 전이하면 갈아 끼우기 전 상태를 되살린다. 그래서 여기서는 다시 읽는다.
     *
     * **다시 읽으면 다시 검사해야 한다.** `SELECT ... FOR UPDATE`는 **없는 행을 잠그지 않으므로**,
     * 판정 때 행이 없었다면 그 사이 다른 트랜잭션이 같은 root를 만들어 커밋했을 수 있다.
     * 그때 재검사 없이 적용하면 금액·주소가 다른 관찰이 상태·hash·outbox를 진행시킨다.
     *
     * 재검사에서 어긋나면 **되돌린다** — 여기까지 온 호출자는 이미 결속을 썼으므로 그 자리에서 격리할 수 없다.
     * 트랜잭션을 통째로 물리면 아무것도 남지 않고, 인박스가 다시 처리해 이번에는 처음부터 충돌로 판정한다.
     */
    fun applyCheckedReread(
        checked: TxObservationCheck.Consistent,
        successEvidence: Boolean,
        deferFailure: Boolean = false,
        attributedType: TxType? = null,
    ): TxStateChange {
        checked.requireChecked()
        val previous = repository.findByVendorTxIdForUpdate(checked.rootVendorTransactionId)
        val consistency = check(previous, checked.observation)
        if (consistency is TxObservationConsistency.Result.Conflict) {
            throw ConflictException("txObservation", consistency.field)
        }
        return stateMachine.observeLocked(
            previous = previous,
            rootVendorTransactionId = checked.rootVendorTransactionId,
            observation = checked.observation,
            successEvidence = successEvidence,
            deferFailure = deferFailure,
            attributedType = attributedType,
        )
    }

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
    /**
     * 받아들일 수 있다. 잠근 행을 그대로 들고 있어 [TxStateService.applyChecked]가 다시 읽지 않는다.
     *
     * **`data class`가 아니다** — `copy`로 root만 바꾸면 "이 행을 잠그고 검사했다"는 증거가 거짓이 된다.
     * 생성자를 막는 대신 **쓰는 자리에서 다시 확인한다**([requireChecked]) — 토큰이 스스로 들고 있는 값으로
     * 다시 물으므로 DB를 더 읽지 않고, 밖에서 지어낸 토큰도 같은 잣대로 걸린다.
     */
    class Consistent(
        val rootVendorTransactionId: String,
        val previous: TxRecord?,
        val observation: TxObservation,
        /** 판정에 쓴 주소 비교 규칙. 토큰이 들고 다녀 재확인이 카탈로그를 다시 읽지 않는다. */
        val chainModel: ChainModel? = null,
    ) : TxObservationCheck {
        /**
         * 이 토큰이 정말 "검사를 통과한 관찰"인지 다시 본다. [TxStateService.lockAndCheck]가 만든 토큰은 늘 참이다.
         *
         * 두 가지를 본다 — 잠근 행과 root 키가 한 쌍인가(이름은 B인데 갱신은 A에 나가는 일), 그리고
         * 그 행과 관찰이 실제로 같은 사실을 말하는가. 순수 비교라 DB를 읽지 않는다.
         */
        internal fun requireChecked() {
            val recorded = previous ?: return
            check(recorded.vendorTxId == rootVendorTransactionId) {
                "checked observation is not bound to its locked row: root=$rootVendorTransactionId recorded=${recorded.vendorTxId}"
            }
            val consistency = TxObservationConsistency.check(recorded, observation, chainModel)
            check(consistency !is TxObservationConsistency.Result.Conflict) {
                "observation was never checked against its locked row: field=${(consistency as TxObservationConsistency.Result.Conflict).field}"
            }
        }
    }

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
