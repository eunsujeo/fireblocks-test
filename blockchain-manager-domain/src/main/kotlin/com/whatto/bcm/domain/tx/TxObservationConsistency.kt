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
        ) : Result {
            /**
             * 인박스에 남길 사유. **어긋난 항목만 말하고 값은 담지 않는다** — 금액·주소는 격리 사유로 적지 않는다.
             * 무엇이 어긋났는지는 [recorded]·[observed]로 호출자가 안에서 다룬다.
             *
             * `field`는 접근자 안에서 뒷받침 필드를 가리키는 소프트 키워드다 — 생성자 프로퍼티는 `this.`로 짚는다.
             */
            val safeReason: String
                get() = "observation conflicts with recorded transaction: field=${this.field}"
        }
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

        amountConflict(previous, observation)?.let { return it }
        addressConflict("sourceAddress", previous.sourceAddress, observation.observedSourceAddress, chainModel)
            ?.let { return it }
        addressConflict("destinationAddress", previous.destinationAddress, observation.observedDestinationAddress, chainModel)
            ?.let { return it }
        return Result.Consistent
    }

    /**
     * 금액은 최초값 불변이다. 표기가 달라도 같은 금액이면 같다 — `1`·`1.0`·`1.00`.
     *
     * **환산 근거가 양쪽에 있으면 최소 단위로 비교한다**(03 V34 "재처리는 현재 매핑이 아니라 이 값을 쓴다").
     * 사람 단위 금액은 *처리 당시* 매핑 정밀도로 만든 파생값이라, 매핑이 바뀐 뒤 같은 사건을 재처리하면
     * 같은 최소 단위에서 다른 금액이 나온다. 그걸로 비교하면 **잘못이 없는 재처리를 격리**하게 된다 —
     * V34가 이 원장에 근거를 남긴 이유가 그것이다.
     */
    private fun amountConflict(
        previous: TxRecord,
        observation: TxObservation,
    ): Result.Conflict? {
        val recordedBaseUnits = previous.amountBaseUnits
        val observedBaseUnits = observation.observedAmountBaseUnits
        if (recordedBaseUnits != null && observedBaseUnits != null) {
            // 같은 최소 단위면 같은 관찰이다 — 정밀도가 바뀌어 사람 단위 금액이 달라져도 격리하지 않는다.
            return if (recordedBaseUnits == observedBaseUnits) {
                null
            } else {
                Result.Conflict("amountBaseUnits", recordedBaseUnits, observedBaseUnits)
            }
        }
        val recorded = previous.amount ?: return null
        val observed = observation.observedAmount ?: return null
        val same =
            try {
                BigDecimal(recorded).compareTo(BigDecimal(observed)) == 0
            } catch (exception: NumberFormatException) {
                // 파싱되지 않는 값은 같다고 볼 수 없다. 형식 오류를 조용히 통과시키지 않는다.
                // **값은 메시지에 담지 않는다** — 이 예외는 로그로 나가고 금액은 로그에 남길 값이 아니다.
                throw IllegalStateException("cannot compare transaction amounts", exception)
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
