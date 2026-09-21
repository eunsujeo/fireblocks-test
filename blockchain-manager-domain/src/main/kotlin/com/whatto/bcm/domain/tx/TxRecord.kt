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
    /**
     * 공개 조회의 금액 — **사람 단위 정규화 값**(03 V32). 최초값 불변이고, 다른 금액이 관찰되면 덮지 않고 그 관찰을 격리한다.
     * 체인에 오르기 전에도 제출이 아는 금액이 있으므로 제출 거래는 처음부터 채워진다.
     */
    val amount: String? = null,
    /** 발신 온체인 주소. 체인에 오르기 전에는 null이고 `null → 값`으로만 채운다(03 V32). */
    val sourceAddress: String? = null,
    /** 수신 온체인 주소. `sourceAddress`와 같은 규칙이다. */
    val destinationAddress: String? = null,
    /**
     * 관찰이 준 최소 단위 정수와 그때 환산에 쓴 정밀도(03 V34). 사람 단위 금액이 이미 오는 경로는 둘 다 null이다.
     * **재처리는 현재 매핑이 아니라 이 값으로 재환산한다** — 매핑이 제자리에서 바뀌어도 같은 금액이어야 한다.
     */
    val amountBaseUnits: String? = null,
    val amountDecimals: Int? = null,
    /** 공개 노출을 가르는 거래 구분(03 V32). 관찰로 병합하지 않는 **권위 있는 분류값**이다. */
    val transactionType: TxType? = null,
    /** 막힘 경보 일시 — 있으면 다음 주기 건너뜀, 해소 전이 시 null */
    val stallAlertedAt: String? = null,
    val firstDetectedAt: String,
    /** 마지막 갱신 일시 — 감소 금지, 막힘 점검의 기준 */
    val lastChangedAt: String,
    /**
     * 벤더 createdAt — 대사 단일 시간축, 최초값 보존.
     *
     * **첫 벤더 관찰 전에는 `null`이다**(03 V32). 제출 마감이 거래 행을 먼저 만들 때는 벤더 시각을 모른다 —
     * 제출 응답은 `txId`만 준다. BCM 수용 시각으로 대신 채우면 대사가 벤더 시각끼리 비교한다는 규칙이 깨진다.
     * `null → 값` 한 번만 채우고 그 뒤에는 바꾸지 않는다(set-once 예외).
     */
    val vendorCreatedAt: String? = null,
    /** 창 밖 미결 거래의 마지막 단건 조회 claim 시각 */
    val reconciliationCheckedAt: String? = null,
    /** 창 밖 미결 거래 단건 조회 횟수 — 영속 백오프 단계 */
    val reconciliationCheckCount: Int = 0,
    /** 최대 추적 나이 도달 시각 — 값이 있으면 자동 단건 조회 중단 */
    val reconciliationStoppedAt: String? = null,
)
