package com.whatto.bcm.domain.tx

import com.whatto.bcm.domain.asset.ChainModel
import java.math.BigDecimal

/**
 * 관찰이 이미 적힌 거래와 **같은 사실을 말하는가**를 본다(03 V32 "충돌한 관찰은 통째로 격리한다").
 *
 * 전이 표의 `Ignore`와 다른 판정축이다 — `Ignore`는 "발행할 상태가 없다"이고 여기는 "이 관찰을 받아들일 수 있는가"다.
 * 그래서 **[TxStateMachine]보다 먼저** 물어야 하고, 충돌이면 상태·컨펌·hash·주소·outbox 무엇도 적용하지 않는다.
 *
 * 금액만 빼고 상태를 진행시키면 `FINALIZED`를 수용하면서 공개 응답에는 실제 관찰과 다른 값을 내보내게 된다 —
 * 확정이 늦는 것보다 나쁜 내부 모순이다.
 */
object TxObservationConsistency {
    sealed interface Result {
        /** 받아들일 수 있다. */
        data object Consistent : Result

        /** 받아들일 수 없다 — 이 관찰 전체를 격리한다. */
        data class Conflict(
            val field: String,
            val recorded: String,
            val observed: String,
        ) : Result
    }

    /**
     * @param previous 잠근 뒤 읽은 현재 행. 없으면(첫 관찰) 비교할 것이 없다.
     * @param chainModel 주소 비교 규칙을 가른다. 모르면 정확히 같을 때만 같다고 본다([TxAddresses]).
     */
    fun check(
        previous: TxRecord?,
        observation: TxObservation,
        chainModel: ChainModel?,
    ): Result {
        if (previous == null) return Result.Consistent

        amountConflict(previous.amount, observation.observedAmount)?.let { return it }
        addressConflict("sourceAddress", previous.sourceAddress, observation.observedSourceAddress, chainModel)
            ?.let { return it }
        addressConflict("destinationAddress", previous.destinationAddress, observation.observedDestinationAddress, chainModel)
            ?.let { return it }
        return Result.Consistent
    }

    /** 금액은 최초값 불변이다. 표기가 달라도 같은 금액이면 같다 — `1`·`1.0`·`1.00`. */
    private fun amountConflict(
        recorded: String?,
        observed: String?,
    ): Result.Conflict? {
        if (recorded == null || observed == null) return null
        val same =
            try {
                BigDecimal(recorded).compareTo(BigDecimal(observed)) == 0
            } catch (exception: NumberFormatException) {
                // 파싱되지 않는 값은 같다고 볼 수 없다. 형식 오류를 조용히 통과시키지 않는다.
                throw IllegalStateException("cannot compare transaction amounts: recorded=$recorded observed=$observed", exception)
            }
        return if (same) null else Result.Conflict("amount", recorded, observed)
    }

    /** 주소는 `null → 값`만 허용한다. 이미 있는 값과 다르면 충돌이다. */
    private fun addressConflict(
        field: String,
        recorded: String?,
        observed: String?,
        chainModel: ChainModel?,
    ): Result.Conflict? {
        if (recorded == null || observed == null) return null
        return if (TxAddresses.sameAddress(recorded, observed, chainModel)) {
            null
        } else {
            Result.Conflict(field, recorded, observed)
        }
    }
}
