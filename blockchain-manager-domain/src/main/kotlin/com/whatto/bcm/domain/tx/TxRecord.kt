package com.whatto.bcm.domain.tx

/**
 * 거래 운영 상태 — 판단 워커가 알림에서 만들어 추적하는 행 (03-bcm-db bcm_tx_l).
 * 최초 vendor tx id 한 행을 논리 root로 유지하고, activeVendorTxId가 현재 RBF head 또는 먼저 채굴된 승자를 가리킨다.
 * 이벤트는 항상 root vendorTxId 기준으로 나간다.
 */
data class TxRecord(
    /** 벤더 tx id — PK */
    val vendorTxId: String,
    /** 현재 RBF head 또는 먼저 채굴된 승자. 최초에는 vendorTxId와 같다. */
    val activeVendorTxId: String = vendorTxId,
    /** 제출 건의 백엔드 요청 키 (UNIQUE — 재제출 중복 차단). 입금 감지 건은 null */
    val externalTxId: String? = null,
    /** 귀속 계정 — 이벤트 파티션 키 */
    val accountId: String,
    val network: String,
    val symbol: String,
    /** active 물리 거래의 온체인 hash. 같은 active 안에서는 set-once다. */
    val transactionHash: String? = null,
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
    /** 벤더 createdAt — 대사 단일 시간축, 최초값 보존 */
    val vendorCreatedAt: String = firstDetectedAt,
    /** 창 밖 미결 거래의 마지막 단건 조회 claim 시각 */
    val reconciliationCheckedAt: String? = null,
    /** 창 밖 미결 거래 단건 조회 횟수 — 영속 백오프 단계 */
    val reconciliationCheckCount: Int = 0,
    /** 최대 추적 나이 도달 시각 — 값이 있으면 자동 단건 조회 중단 */
    val reconciliationStoppedAt: String? = null,
)
