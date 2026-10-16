package com.whatto.bcm.domain.vendor

/**
 * 관찰과 **그 관찰을 만든 응답 원문**을 함께 돌려주는 조회(03 V37).
 *
 * BAT 가 쓴다. 조회로 받은 관찰이 원장과 어긋나면 그 관찰을 격리하는데, 격리 증적은 **우리가 변환한 값이 아니라
 * 벤더가 보낸 본문**이어야 원인(매핑·어댑터)을 고친 뒤 다시 해석할 수 있다. 어댑터는 빈 문자열을 `null` 로,
 * 누락 컨펌을 `0` 으로 바꾸므로 변환 뒤 스냅샷만으로는 복구 범위가 모자란다.
 *
 * 공개 조회(API)는 이 포트를 쓰지 않는다 — 증적을 남길 이유가 없고, 본문을 들고 다닐 이유도 없다.
 */
interface VendorTransactionEvidencePort {
    fun observedTransaction(transactionId: String): ObservedVendorTransaction?

    fun observedTransactionByExternalTransactionId(externalTransactionId: String): ObservedVendorTransaction?

    fun observedTransactions(request: VendorTransactionPageRequest): VendorPage<ObservedVendorTransaction>
}

/** 하나의 관찰과 그것을 만든 응답. */
data class ObservedVendorTransaction(
    val transaction: VendorTransaction,
    val evidence: VendorResponseEvidence,
)

/**
 * 파싱 전 응답 본문과 그 조회의 맥락.
 *
 * **목록 조회면 [body] 는 페이지 전체**다 — 거래 하나만 잘라 내면 그것도 우리가 가공한 값이 된다.
 * 어느 거래의 증적인지는 [indexInBody] 가 잇는다.
 */
data class VendorResponseEvidence(
    /** 파싱 전 응답 본문. 민감값이 들어 있을 수 있어 보호 저장소에만 둔다. */
    val body: ByteArray,
    val contentType: String?,
    val lookup: VendorLookup,
    /**
     * 목록 응답에서 이 거래의 위치. 단건 조회는 `null`.
     *
     * 조회 **시각**은 여기 담지 않는다 — 격리 행이 판정 시각을 남기고, 둘은 같은 작업 실행 안이다.
     * 시각 하나 때문에 벤더 클라이언트에 시계를 심지 않는다.
     */
    val indexInBody: Int? = null,
) {
    /** `ByteArray` 는 참조 비교라 data class 의 기본 equals 가 거짓음성을 낸다. */
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is VendorResponseEvidence &&
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

/** 어느 조회가 이 관찰을 만들었나 — 같은 거래라도 조회 경로마다 응답 모양이 다르다. */
enum class VendorLookup {
    /** 거래 ID 단건 조회 */
    BY_TRANSACTION_ID,

    /** 제출 키 회수 조회 */
    BY_EXTERNAL_TRANSACTION_ID,

    /** 기간 목록 조회 — 본문은 페이지 전체다 */
    PAGE,
}
