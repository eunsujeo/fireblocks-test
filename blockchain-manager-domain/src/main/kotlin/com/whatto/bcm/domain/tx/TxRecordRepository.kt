package com.whatto.bcm.domain.tx

/** 거래 운영 상태 저장소 포트 — 구현은 infra/persistence (DIP) */
interface TxRecordRepository {
    /** 신규 거래 저장 — externalTxId 충돌(재제출)은 DB UNIQUE 가 막는다 */
    fun insert(txRecord: TxRecord): TxRecord

    /** 상태 전이 반영 — confirmationCount·lastChangedAt 감소와 최초 기록 덮어쓰기를 DB 갱신문이 방어한다 */
    fun update(txRecord: TxRecord): TxRecord

    /** active가 아닌 같은 RBF 계열의 성공 증거를 먼저 채굴된 승자로 채택한다. */
    fun updatePhysicalWinner(
        txRecord: TxRecord,
        previousActiveVendorTxId: String,
    ): TxRecord

    fun findByVendorTxId(vendorTxId: String): TxRecord?

    fun findByActiveVendorTxId(activeVendorTxId: String): TxRecord?

    /** 상태 전이 판정용 행 잠금. 호출자는 반드시 같은 트랜잭션 안에서 판정·갱신까지 끝내야 한다. */
    fun findByVendorTxIdForUpdate(vendorTxId: String): TxRecord?

    fun findByActiveVendorTxIdForUpdate(activeVendorTxId: String): TxRecord?

    fun findByExternalTxId(externalTxId: String): TxRecord?
}
