package com.whatto.bcm.domain.vendor

import com.whatto.bcm.domain.submission.SubmissionRecipientType
import com.whatto.bcm.domain.submission.SubmissionRecord
import com.whatto.bcm.domain.submission.SubmissionStatus
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.submission.SubmissionVendorCanonical
import com.whatto.bcm.domain.tx.TxRecord
import com.whatto.bcm.domain.tx.TxStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 발신 이동을 기존 거래에 붙이는 판정(계약13 "논리 거래 식별자").
 * `tx_hash`는 유일하지 않으므로 **후보가 정확히 하나이고 제출 원장에 대응할 때만** 붙인다 —
 * 02의 "제출 원장이 기준"을 hash 추정으로 바꾸지 않는다.
 */
class NetworkChainOutgoingAttachmentTest {
    @Test
    fun `후보가 하나이고 제출 원장에 대응하면 그 거래에 붙인다`() {
        val record = txRecord()
        val submission = submission()

        val result = NetworkChainOutgoingAttachment.attach(observation(), listOf(record)) { submission }

        assertThat(result).isEqualTo(NetworkChainAttachmentResult.Attach(record, submission))
    }

    @Test
    fun `후보가 없으면 거래를 만들지 않는다`() {
        // 전송 알림이 아직 안 왔을 수 있다 — 여기서 만들면 알림이 나중에 와서 같은 자금의 거래가 둘이 된다.
        val result = NetworkChainOutgoingAttachment.attach(observation(), emptyList()) { submission() }

        assertThat(result).isEqualTo(NetworkChainAttachmentResult.NoCandidate)
    }

    @Test
    fun `후보가 여럿이면 하나를 고르지 않고 중단한다`() {
        // hash만으로 어느 것이 이 이동인지 가릴 수 없다. 규칙을 지어내면 다른 거래에 남의 확정이 붙는다.
        val result =
            NetworkChainOutgoingAttachment.attach(
                observation(),
                listOf(txRecord(), txRecord(vendorTxId = "xfr-2")),
            ) { submission() }

        assertThat(result).isEqualTo(NetworkChainAttachmentResult.Ambiguous(2))
    }

    @Test
    fun `후보는 하나인데 제출 원장에 대응이 없으면 붙이지 않는다`() {
        // 우리 지갑에서 나간 이동인데 우리가 낸 제출이 아니라는 뜻이다 — hash 일치로 제출 원장 기준을 대체하지 않는다.
        val record = txRecord()

        val result = NetworkChainOutgoingAttachment.attach(observation(), listOf(record)) { null }

        assertThat(result).isEqualTo(NetworkChainAttachmentResult.NoSubmission(record))
    }

    @Test
    fun `대응은 후보 거래의 벤더 전송 ID로 찾는다`() {
        val looked = mutableListOf<String>()

        NetworkChainOutgoingAttachment.attach(observation(), listOf(txRecord(vendorTxId = "xfr-9"))) {
            looked += it
            submission()
        }

        assertThat(looked).containsExactly("xfr-9")
    }

    @Test
    fun `후보가 하나여도 관찰이 그 제출의 값과 다르면 붙이지 않는다`() {
        // 한 트랜잭션에 우리 이동 A·B가 있고 A의 전송 알림만 먼저 와 있으면, B의 사건이 A에 붙어 남의 확정이 된다.
        val record = txRecord()
        val submission = submission()

        assertThat(
            NetworkChainOutgoingAttachment.attach(observation(amountBaseUnits = "2000000"), listOf(record)) { submission },
        ).isEqualTo(NetworkChainAttachmentResult.Mismatched(record, submission))
        assertThat(
            NetworkChainOutgoingAttachment.attach(observation(toAddress = OTHER_ADDRESS), listOf(record)) { submission },
        ).isEqualTo(NetworkChainAttachmentResult.Mismatched(record, submission))
        assertThat(
            NetworkChainOutgoingAttachment.attach(observation(vendorWalletId = "wa-other"), listOf(record)) { submission },
        ).isEqualTo(NetworkChainAttachmentResult.Mismatched(record, submission))
    }

    @Test
    fun `제출 시점 값이 없는 행은 대응을 증명할 수 없어 붙이지 않는다`() {
        // 증명하지 못한 채 붙이는 것이 곧 다른 거래에 남의 확정을 붙이는 일이다.
        val record = txRecord()
        val submission = submission(canonical = null)

        assertThat(NetworkChainOutgoingAttachment.attach(observation(), listOf(record)) { submission })
            .isEqualTo(NetworkChainAttachmentResult.Mismatched(record, submission))
    }

    private fun observation(
        vendorWalletId: String = "wa-1",
        amountBaseUnits: String = "1000000",
        toAddress: String? = ADDRESS,
    ) = NetworkChainTransfer(
        network = "ETHEREUM_SEPOLIA",
        vendorWalletId = vendorWalletId,
        vendorWalletAddress = "0x9999999999999999999999999999999999999999",
        vendorAssetId = "EthereumSepolia:Native",
        vendorAssetKind = "NativeTransfer",
        direction = NetworkChainDirection.OUT,
        status = NetworkChainTransferStatus.CONFIRMED,
        amountBaseUnits = amountBaseUnits,
        fromAddress = "0x9999999999999999999999999999999999999999",
        toAddress = toAddress,
        transactionHash = HASH,
        blockNumber = 100,
        eventIndex = "0",
        observedAt = "2026-09-17T09:00:00Z",
    )

    private fun txRecord(vendorTxId: String = "xfr-1") =
        TxRecord(
            vendorTxId = vendorTxId,
            accountId = "acct-1",
            network = "ETHEREUM_SEPOLIA",
            symbol = "USDC",
            transactionHash = HASH,
            lastPublishedStatus = TxStatus.SUBMITTED,
            confirmationCount = 0,
            firstDetectedAt = "20260917090000",
            lastChangedAt = "20260917090000",
        )

    private fun submission(canonical: SubmissionVendorCanonical? = CANONICAL) =
        SubmissionRecord(
            externalTransactionId = "ext-1",
            requestHash = "0".repeat(64),
            hashVersion = "v1",
            status = SubmissionStatus.SUBMITTED,
            claimId = null,
            claimExpiresAt = null,
            transactionType = SubmissionTransactionType.WITHDRAWAL,
            vendorTransactionId = "xfr-1",
            senderAccountId = "acct-1",
            recipientType = SubmissionRecipientType.ADDRESS,
            recipientValue = ADDRESS,
            network = "ETHEREUM_SEPOLIA",
            symbol = "USDC",
            amount = "1",
            requestedAt = "20260917090000",
            respondedAt = null,
            vendorCanonical = canonical,
        )

    private companion object {
        const val ADDRESS = "0x1111111111111111111111111111111111111111"
        const val OTHER_ADDRESS = "0x2222222222222222222222222222222222222222"
        val HASH = "0x" + "a".repeat(64)
        val CANONICAL =
            SubmissionVendorCanonical(
                vendorWalletId = "wa-1",
                vendorAssetId = "EthereumSepolia:Native",
                amountBaseUnits = "1000000",
                decimals = 6,
            )
    }
}
