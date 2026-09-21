package com.whatto.bcm.domain.tx

/**
 * 거래 행의 구분(03 V32 `bcm_tx_l.tx_dvcd`). **공개 조회에 나올지를 이 값으로 가른다**(02 "거래 조회").
 *
 * `bcm_sbmt_l.tx_dvcd`와 값이 겹치지만 같은 enum을 쓰지 않는다 — 제출 원장은 **우리가 낸 건**만 담고
 * 입금은 제출 행이 아예 없다. 거래 원장에는 `DEPOSIT`이 필요하다.
 *
 * **관찰로 병합하는 값이 아니라 권위 있는 분류값**이다. 거래 행을 만들 때 확정하고,
 * 이미 있는 값과 다른 분류가 오면 덮지 않고 내부 원장 불일치로 거절한다.
 */
enum class TxType {
    DEPOSIT,
    WITHDRAWAL,
    INTERNAL,
    SWEEP_APPROVE,
    SWEEP_BATCH,
    BAND_S,

    ;

    /** 공개 조회·목록에 나오는 계열인가. sweep·밴드S는 공통 `ChainEvent`도 내지 않는 내부 운영 계열이다. */
    fun isPublic(): Boolean = this == DEPOSIT || this == WITHDRAWAL || this == INTERNAL
}
