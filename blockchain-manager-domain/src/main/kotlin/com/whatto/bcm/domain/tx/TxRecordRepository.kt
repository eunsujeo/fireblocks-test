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

    /**
     * `(network, transactionHash)` 단위 직렬화 경계를 현재 트랜잭션 동안 잡는다.
     *
     * 후보 조회는 잠금 없는 조회이고 `(ntwk_cd, tx_hash)`에는 유일 제약이 없다 — 조회와 전이 사이에 다른 트랜잭션이
     * 같은 hash의 행을 **새로 삽입**할 수 있고, 기존 행에 `FOR UPDATE`를 걸어도 그 삽입은 막히지 않는다.
     * 그래서 거래를 만드는 쪽과 좌표를 적용하는 쪽이 같은 경계를 잡고, 그 안에서 후보를 다시 읽어야 한다.
     */
    fun lockNetworkTransactionHash(
        network: String,
        transactionHash: String,
    )

    /**
     * 온체인 hash로 거래를 찾는다(V26 index). **hash는 유일하지 않다** — 한 트랜잭션에 여러 이동이 있을 수 있고
     * 네트워크가 다르면 같은 hash도 다른 거래다. 그래서 목록으로 돌려주고 **어느 행을 다룰지는 호출자가 정한다**(계약13).
     */
    fun findByNetworkAndTransactionHash(
        network: String,
        transactionHash: String,
    ): List<TxRecord>
}
