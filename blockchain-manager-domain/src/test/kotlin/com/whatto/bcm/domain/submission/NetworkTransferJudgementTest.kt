package com.whatto.bcm.domain.submission

import com.whatto.bcm.domain.vendor.NetworkTransferObservation
import com.whatto.bcm.domain.vendor.NetworkTransferStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 전송 알림의 계열 분류(02 "웹훅 계열 분류 — 제출 원장이 기준이다"·계약13).
 * 우리가 낸 전송인지는 **제출 원장으로만** 가른다 — 알림 종류·금액·주소 추정으로 가르지 않는다.
 */
class NetworkTransferJudgementTest {
    @Test
    fun `벤더 전송 ID로 원장을 찾으면 우리 전송이고 이미 연결된 상태다`() {
        val record = record(vendorTransactionId = TRANSFER_ID)
        val lookup = lookup(byVendorId = mapOf(TRANSFER_ID to record))

        val judged = NetworkTransferJudgement.judge(observation(), lookup)

        assertThat(judged).isEqualTo(NetworkTransferJudgementResult.Ours(record, linked = true))
    }

    @Test
    fun `벤더 응답보다 웹훅이 먼저 와도 제출 키로 같은 행에 닿는다`() {
        // 02 — 응답을 못 받아 vndr_tx_id가 비어 있어도 알림이 돌려준 externalId로 회수한다.
        val record = record(vendorTransactionId = null)
        val lookup = lookup(byExternalId = mapOf(EXTERNAL_ID to record))

        val judged = NetworkTransferJudgement.judge(observation(), lookup)

        assertThat(judged).isEqualTo(NetworkTransferJudgementResult.Ours(record, linked = false))
    }

    @Test
    fun `한 제출 키에 다른 전송 ID가 이미 붙어 있으면 충돌로 올린다`() {
        // 재시도로 풀리는 문제가 아니다 — 한 요청에 전송이 둘 붙은 상태라 사람이 봐야 한다.
        val record = record(vendorTransactionId = "xfr-other")
        val lookup = lookup(byExternalId = mapOf(EXTERNAL_ID to record))

        val judged = NetworkTransferJudgement.judge(observation(), lookup)

        assertThat(judged).isEqualTo(NetworkTransferJudgementResult.Conflicting(record, TRANSFER_ID))
    }

    @Test
    fun `원장에 없는 전송은 우리 것이 아니므로 계정·계열을 지어내지 않는다`() {
        val judged = NetworkTransferJudgement.judge(observation(), lookup())

        assertThat(judged).isEqualTo(NetworkTransferJudgementResult.Unknown(TRANSFER_ID))
    }

    @Test
    fun `제출 키가 없는 알림은 두 번째 경로를 쓸 수 없어 미확인이다`() {
        // externalId 없이 vndr_tx_id 조회도 빗나가면 원장에 닿을 길이 없다 — 추정하지 않는다.
        val judged = NetworkTransferJudgement.judge(observation(externalId = null), lookup())

        assertThat(judged).isEqualTo(NetworkTransferJudgementResult.Unknown(TRANSFER_ID))
    }

    private fun lookup(
        byVendorId: Map<String, SubmissionRecord> = emptyMap(),
        byExternalId: Map<String, SubmissionRecord> = emptyMap(),
    ): NetworkTransferJudgement.SubmissionLookup =
        object : NetworkTransferJudgement.SubmissionLookup {
            override fun byVendorTransactionId(vendorTransactionId: String): SubmissionRecord? = byVendorId[vendorTransactionId]

            override fun byExternalTransactionId(externalTransactionId: String): SubmissionRecord? = byExternalId[externalTransactionId]
        }

    private fun record(vendorTransactionId: String?): SubmissionRecord =
        SubmissionRecord(
            externalTransactionId = EXTERNAL_ID,
            requestHash = "0".repeat(64),
            hashVersion = "v1",
            status = if (vendorTransactionId == null) SubmissionStatus.REQUESTED else SubmissionStatus.SUBMITTED,
            claimId = null,
            claimExpiresAt = null,
            transactionType = SubmissionTransactionType.WITHDRAWAL,
            vendorTransactionId = vendorTransactionId,
            senderAccountId = "acct-1",
            recipientType = SubmissionRecipientType.ADDRESS,
            recipientValue = "0x1111111111111111111111111111111111111111",
            network = "ETHEREUM_SEPOLIA",
            symbol = "USDC",
            amount = "1",
            requestedAt = "20260917090000",
            respondedAt = null,
        )

    private fun observation(externalId: String? = EXTERNAL_ID): NetworkTransferObservation =
        NetworkTransferObservation(
            transferId = TRANSFER_ID,
            network = "EthereumSepolia",
            vendorWalletId = "wa-1",
            vendorAssetId = "EthereumSepolia:Native",
            destinationAddress = "0x1111111111111111111111111111111111111111",
            amountBaseUnits = "1000000",
            status = NetworkTransferStatus.BROADCASTED,
            externalId = externalId,
            transactionHash = "0x" + "a".repeat(64),
            requestedAt = "2026-09-17T09:00:00Z",
            failureReason = null,
        )

    private companion object {
        const val TRANSFER_ID = "xfr-1"
        const val EXTERNAL_ID = "ext-1"
    }
}
