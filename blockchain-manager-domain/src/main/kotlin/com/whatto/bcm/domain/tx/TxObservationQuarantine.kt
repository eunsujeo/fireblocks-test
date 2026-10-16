package com.whatto.bcm.domain.tx

import com.whatto.bcm.domain.vendor.VendorLookup

/**
 * 격리한 관찰 하나(03 V37). **거래가 아니라 관찰을 격리한다** — 거래 상태와 고객 outbox 는 그대로 둔다.
 *
 * 웹훅 경로는 인박스 행을 `F` 로 돌려 이 자리를 삼지만 BAT(막힘 점검·tx 대사)에는 인박스 행이 없다.
 */
data class TxObservationQuarantine(
    val quarantineId: String,
    /** 논리 root 와 실제로 관찰된 물리 거래. RBF 계열이면 둘이 다르다. */
    val rootVendorTransactionId: String,
    val observedVendorTransactionId: String,
    val network: String,
    val source: TxObservationQuarantineSource,
    val status: TxObservationQuarantineStatus,
    /** 어긋난 항목과 양쪽 값. **값은 보호 대상이라 경보·로그에는 항목 이름만 나간다.** */
    val field: String,
    val recordedValue: String,
    val observedValue: String,
    val evidence: TxObservationQuarantineEvidence,
    /** 같은 관찰의 반복 발견을 한 행으로 합치는 열쇠. */
    val fingerprint: String,
    val detectionCount: Int,
    val firstDetectedAt: String,
    val lastChangedAt: String,
)

/** 판정에 쓴 응답 원문. 목록 조회면 **페이지 전체**이고 [indexInBody] 가 이 거래의 위치다. */
data class TxObservationQuarantineEvidence(
    val body: ByteArray,
    val contentType: String?,
    val lookup: VendorLookup,
    val indexInBody: Int?,
) {
    /** `ByteArray` 는 참조 비교라 data class 의 기본 equals 가 거짓음성을 낸다. */
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is TxObservationQuarantineEvidence &&
                    body.contentEquals(other.body) &&
                    contentType == other.contentType &&
                    lookup == other.lookup &&
                    indexInBody == other.indexInBody
            )

    override fun hashCode(): Int =
        listOf(body.contentHashCode(), contentType, lookup, indexInBody).fold(0) { acc, part ->
            31 * acc + part.hashCode()
        }
}

/** 어느 작업이 찾았나. 같은 handler 를 두 작업이 쓰므로 유입 경로를 남긴다. */
enum class TxObservationQuarantineSource {
    STALL,
    RECONCILIATION,
}

enum class TxObservationQuarantineStatus {
    /** 아직 사람이 보지 않았다. 해소 책임이 이 행에 있다. */
    OPEN,

    /** 사람이 보고 닫았다 — 재처리 없이 종결한 경우다. */
    RESOLVED,

    /**
     * 저장한 같은 관찰을 다시 검사해 **수용한 뒤** 전이한다. 적용·outbox·이 전이는 같은 트랜잭션이다.
     * 단순 무시·다른 RBF 승자 때문에 적용 불가·새 조회 성공만으로는 여기 오지 않는다.
     */
    REPLAYED,
}

interface TxObservationQuarantineRepository {
    /**
     * 열린 격리를 남긴다. 같은 관찰이 다시 발견되면 **행을 늘리지 않고** 발견 횟수만 올린다 —
     * 재조회할 때마다 행이 늘면 운영이 같은 사실을 여러 번 본다.
     */
    fun open(
        quarantine: TxObservationQuarantine,
        observedAt: String,
    ): TxObservationQuarantine

    fun findOpenByRoot(rootVendorTransactionId: String): List<TxObservationQuarantine>
}
