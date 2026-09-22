package com.whatto.bcm.app.application.tx

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
 * 한 행이면 [TxStateService.observeConsistently], 여러 행이면 [TxStateService.observeAllConsistently],
 * 판정과 전이 사이에 호출자가 같은 행을 바꾸면 [TxStateService.observeConsistentlyAround]다.
 * **검사 결과를 밖으로 내보내지 않는다** — 토큰을 노출하면 호출자가 지어내 잠금·검사를 우회할 수 있다.
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
     * 잠금 → **동일성 검사** → 전이(03 V32 "충돌한 관찰은 통째로 격리한다").
     *
     * 검사가 전이보다 **먼저**여야 한다 — 뒤에서 잡으면 상태·컨펌·hash가 이미 바뀐 뒤다.
     * 충돌을 인박스 격리로 옮기는 것은 호출자(웹훅 워커) 몫이다 — 이 경계는 인박스를 모른다.
     * 호출자의 **다른 쓰기도 이 호출 뒤에 와야 한다** — 앞서 쓰면 격리와 함께 커밋된다(같은 트랜잭션이다).
     * 그 쓰기가 이 행을 바꾸는 경로는 [observeConsistentlyAround]다.
     */
    fun observeConsistently(
        rootVendorTransactionId: String,
        observation: TxObservation,
        successEvidence: Boolean,
        deferFailure: Boolean = false,
        attributedType: TxType? = null,
    ): TxObservationOutcome =
        when (val checked = lockAndCheck(rootVendorTransactionId, observation)) {
            is Checked.Conflict -> TxObservationOutcome.Conflict(checked.detail)
            is Checked.Consistent ->
                TxObservationOutcome.Applied(
                    stateMachine.observeLocked(
                        previous = checked.previous,
                        rootVendorTransactionId = rootVendorTransactionId,
                        observation = observation,
                        successEvidence = successEvidence,
                        deferFailure = deferFailure,
                        attributedType = attributedType,
                    ),
                )
        }

    /**
     * 판정 → **호출자의 쓰기** → 전이. 호출자가 판정과 전이 사이에 **같은 행을 바꾸는** 경로용이다.
     *
     * 부스트 결속(`markSubmittedByObservation`)은 결속만 하는 게 아니라 root의 활성 거래를 갈아 끼우고
     * `tx_hash`를 비운다(`switchRoot`). 판정 때 읽은 행으로 전이하면 갈아 끼우기 전 상태를 되살린다.
     *
     * 그래서 전이는 [between] 뒤의 행을 **다시 읽고 다시 검사한다.** `SELECT ... FOR UPDATE`는
     * **없는 행을 잠그지 않으므로**, 판정 때 행이 없었다면 그 사이 다른 트랜잭션이 같은 root를 만들어
     * 커밋했을 수 있다. 재검사 없이 적용하면 금액·주소가 다른 관찰이 상태·hash·outbox를 진행시킨다.
     *
     * 재검사가 어긋나면 [ConflictException]으로 **되돌린다** — [between]이 이미 썼으므로 그 자리에서
     * 격리할 수 없다. 트랜잭션을 통째로 물리면 아무것도 남지 않고, 워커가 새 트랜잭션에서 다시 판정한다.
     *
     * 충돌로 되돌아갈 때 [between]은 **부르지 않는다** — 그게 이 API의 존재 이유다.
     */
    fun observeConsistentlyAround(
        rootVendorTransactionId: String,
        observation: TxObservation,
        successEvidence: Boolean,
        deferFailure: Boolean = false,
        attributedType: TxType? = null,
        between: () -> Unit,
    ): TxObservationOutcome {
        val checked = lockAndCheck(rootVendorTransactionId, observation)
        if (checked is Checked.Conflict) return TxObservationOutcome.Conflict(checked.detail)
        between()
        val previous = repository.findByVendorTxIdForUpdate(rootVendorTransactionId)
        val consistency = check(previous, observation)
        if (consistency is TxObservationConsistency.Result.Conflict) {
            throw ConflictException("txObservation", consistency.field)
        }
        return TxObservationOutcome.Applied(
            stateMachine.observeLocked(
                previous = previous,
                rootVendorTransactionId = rootVendorTransactionId,
                observation = observation,
                successEvidence = successEvidence,
                deferFailure = deferFailure,
                attributedType = attributedType,
            ),
        )
    }

    /**
     * 한 트랜잭션이 **여러 행**을 다루는 경로 — 발신 좌표 적용처럼 한 hash의 거래 여럿에 같은 관찰을 편다.
     *
     * **전부 잠가 검사한 뒤에** 쓰기를 시작한다. 행마다 검사·쓰기를 붙여 돌면 뒤 행의 충돌로 격리할 때
     * 앞 행의 전이가 같은 트랜잭션에 실려 함께 커밋된다. 잠금은 트랜잭션 끝까지 남으므로 그 사이가 벌어지지 않는다.
     */
    fun observeAllConsistently(requests: List<TxObservationRequest>): TxObservationBatchOutcome {
        val checked =
            requests.map { request ->
                when (val result = lockAndCheck(request.rootVendorTransactionId, request.observation)) {
                    is Checked.Conflict -> return TxObservationBatchOutcome.Conflict(result.detail)
                    is Checked.Consistent -> request to result
                }
            }
        return TxObservationBatchOutcome.Applied(
            checked.map { (request, result) ->
                stateMachine.observeLocked(
                    previous = result.previous,
                    rootVendorTransactionId = request.rootVendorTransactionId,
                    observation = request.observation,
                    successEvidence = request.successEvidence,
                    deferFailure = request.deferFailure,
                    attributedType = request.attributedType,
                )
            },
        )
    }

    /**
     * 잠그고 검사한다. **결과는 이 클래스 밖으로 나가지 않는다** — 토큰을 내보내면 호출자가 지어낼 수 있고,
     * 그러면 "잠그고 검사했다"는 증거가 거짓이 된다. 잠금의 출처는 타입으로 증명할 수 없으므로 아예 노출하지 않는다.
     */
    private fun lockAndCheck(
        rootVendorTransactionId: String,
        observation: TxObservation,
    ): Checked {
        val previous = repository.findByVendorTxIdForUpdate(rootVendorTransactionId)
        return when (val consistency = check(previous, observation)) {
            is TxObservationConsistency.Result.Conflict -> Checked.Conflict(consistency)
            TxObservationConsistency.Result.Consistent -> Checked.Consistent(previous)
        }
    }

    private fun check(
        previous: TxRecord?,
        observation: TxObservation,
    ): TxObservationConsistency.Result {
        // 비교할 행이 없으면 규칙도 필요 없다 — 첫 관찰에 카탈로그를 읽지 않는다. 모델은 **한 번만** 읽는다.
        if (previous == null) return TxObservationConsistency.Result.Consistent
        val chainModel = blockchains.findByNetwork(observation.network)?.chainModel
        return TxObservationConsistency.check(previous, observation, chainModel)
    }

    private sealed interface Checked {
        data class Consistent(
            val previous: TxRecord?,
        ) : Checked

        data class Conflict(
            val detail: TxObservationConsistency.Result.Conflict,
        ) : Checked
    }
}

/** [TxStateService.observeAllConsistently]의 한 건. */
data class TxObservationRequest(
    val rootVendorTransactionId: String,
    val observation: TxObservation,
    val successEvidence: Boolean = false,
    val deferFailure: Boolean = false,
    val attributedType: TxType? = null,
)

/** 여러 행을 한 번에 다룬 결과 — 하나라도 어긋나면 **아무것도 적용하지 않는다**. */
sealed interface TxObservationBatchOutcome {
    data class Applied(
        val changes: List<TxStateChange>,
    ) : TxObservationBatchOutcome

    data class Conflict(
        val detail: TxObservationConsistency.Result.Conflict,
    ) : TxObservationBatchOutcome
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
