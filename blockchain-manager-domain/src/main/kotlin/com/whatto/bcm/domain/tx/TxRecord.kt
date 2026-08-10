package com.whatto.bcm.domain.tx

/**
 * 거래 운영 상태 — 판단 워커가 알림에서 만들어 추적하는 행 (03-bcm-db bcm_tx_l).
 * tx 식별은 boost 의 txId 접기를 전제한다 — 대체 거래(새 txId)는 originTxId 로 원 tx 를 가리키고,
 * 이벤트는 원 tx 기준으로 나간다 (Phase 7 에서 회수).
 */
data class TxRecord(
    /** 벤더 tx id — PK */
    val vendorTxId: String,
    /** boost 대체 건이면 원 tx — 백엔드에는 원 tx 로 접어 흘린다 */
    val originTxId: String? = null,
    /** 제출 건의 백엔드 요청 키 (UNIQUE — 재제출 중복 차단). 입금 감지 건은 null */
    val externalTxId: String? = null,
    /** 귀속 계정 — 이벤트 파티션 키 */
    val accountId: String,
    val network: String,
    val symbol: String,
    /** 마지막으로 발행한 TxStatus — 전이 표의 "직전 상태" */
    val lastPublishedStatus: TxStatus,
    /** 마지막으로 본 confirmation 수 — 큰 값으로만 갱신 (감소 금지) */
    val confirmationCount: Int,
    /** 마지막 알림의 벤더 subStatus 원어 — 운영 조사용, 이벤트 미탑재 */
    val vendorSubStatus: String? = null,
    /** 마지막 알림의 벤더 networkStatus 원어 — 운영 조사용, 이벤트 미탑재 */
    val vendorNetworkStatus: String? = null,
    /** 막힘 경보 일시 — 있으면 다음 주기 건너뜀, 해소 전이 시 null */
    val stallAlertedAt: String? = null,
    val firstDetectedAt: String,
    /** 마지막 갱신 일시 — 감소 금지, 막힘 점검의 기준 */
    val lastChangedAt: String,
)
