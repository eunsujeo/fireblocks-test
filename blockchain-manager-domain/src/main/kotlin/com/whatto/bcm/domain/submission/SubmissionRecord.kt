package com.whatto.bcm.domain.submission

import com.whatto.bcm.domain.event.EventType

data class SubmissionRecord(
    val externalTransactionId: String,
    val requestHash: String,
    val hashVersion: String,
    val status: SubmissionStatus,
    val claimId: String?,
    val claimExpiresAt: String?,
    val transactionType: SubmissionTransactionType,
    val vendorTransactionId: String?,
    val senderAccountId: String,
    val recipientType: SubmissionRecipientType,
    val recipientValue: String,
    val network: String,
    val symbol: String,
    val amount: String,
    val requestedAt: String,
    val respondedAt: String?,
    val sweepExecutionId: String? = null,
    val callData: String? = null,
    /**
     * 제출 시점에 확정한 벤더 canonical 값(03 V28). 넷은 한 벌이며 Dfns 회수가 **같은 본문**을 다시 만들 때만 쓴다 —
     * 회수 시점의 현재 자산 매핑·정밀도를 다시 읽으면 그 사이 교체된 값으로 다른 본문이 나갈 수 있다(계약13).
     * Fireblocks·로컬은 벤더 조회로 회수하므로 넷 다 null이다.
     */
    val vendorCanonical: SubmissionVendorCanonical? = null,
)

/** 제출 본문을 결정하는 벤더 쪽 값 한 벌. 하나라도 없으면 본문을 재구성할 수 없어 통째로 다룬다(03 V28의 CHECK와 같은 규약). */
data class SubmissionVendorCanonical(
    val vendorWalletId: String,
    val vendorAssetId: String,
    val amountBaseUnits: String,
    val decimals: Int,
) {
    init {
        require(vendorWalletId.isNotBlank() && vendorWalletId == vendorWalletId.trim()) { "Invalid vendor wallet id" }
        require(vendorAssetId.isNotBlank() && vendorAssetId == vendorAssetId.trim()) { "Invalid vendor asset id" }
        require(BASE_UNITS.matches(amountBaseUnits)) { "Invalid submission base units" }
        require(decimals in 0..255) { "Invalid submission decimals" }
    }

    private companion object {
        val BASE_UNITS = Regex("0|[1-9][0-9]*")
    }
}

enum class SubmissionStatus {
    REQUESTED,
    SUBMITTED,
    FAILED,
}

enum class SubmissionTransactionType {
    WITHDRAWAL,
    INTERNAL,
    SWEEP_APPROVE,
    SWEEP_BATCH,
    BAND_S,

    ;

    fun customerEventType(): EventType? =
        when (this) {
            WITHDRAWAL -> EventType.WITHDRAWAL
            INTERNAL -> EventType.INTERNAL
            SWEEP_APPROVE, SWEEP_BATCH, BAND_S -> null
        }
}

enum class SubmissionRecipientType {
    ADDRESS,
    ACCOUNT,
    WHITELISTED,

    ;

    fun transactionType(): SubmissionTransactionType =
        when (this) {
            ACCOUNT -> SubmissionTransactionType.INTERNAL
            ADDRESS, WHITELISTED -> SubmissionTransactionType.WITHDRAWAL
        }
}
