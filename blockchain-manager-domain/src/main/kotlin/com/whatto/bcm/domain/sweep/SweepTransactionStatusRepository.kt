package com.whatto.bcm.domain.sweep

import com.whatto.bcm.domain.tx.TxStatus

/** sweep 선정 중 잔액 관찰과 입금 확정의 경합을 확인하는 전용 조회 포트. */
interface SweepTransactionStatusRepository {
    /** tx → execution 잠금 순서로 성공 대사와 FAILED 관찰을 직렬화한다. */
    fun findBatchStatusForUpdate(vendorTransactionId: String): TxStatus?

    /** 현재 FINALIZED인 귀속 입금 tx 집합. 잔액 관찰 중 새 확정 입금 유무를 비교한다. */
    fun finalizedDepositIds(key: SweepTargetKey): Set<String>
}
