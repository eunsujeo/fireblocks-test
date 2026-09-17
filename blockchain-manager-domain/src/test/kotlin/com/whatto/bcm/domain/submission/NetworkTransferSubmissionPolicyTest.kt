package com.whatto.bcm.domain.submission

import com.whatto.bcm.domain.exception.InvalidRequestException
import com.whatto.bcm.domain.exception.UnprocessableRequestException
import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.vendor.NetworkTransferObservation
import com.whatto.bcm.domain.vendor.NetworkTransferRequest
import com.whatto.bcm.domain.vendor.NetworkTransferStatus
import com.whatto.bcm.domain.vendor.NetworkWalletScope
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Dfns 출금 제출의 판단 규칙(계약13 "출금 제출 유스케이스"). 회수 수단이 조회가 아니라 멱등 재제출이라
 * 원장 상태에서 무엇을 할지가 Fireblocks 경로와 달라지는 지점을 고정한다.
 */
class NetworkTransferSubmissionPolicyTest {
    @Test
    fun `제출 키가 벤더 한계를 넘으면 자르지 않고 거절해 원장에 제출 불가능한 행을 만들지 않는다`() {
        val limit = NetworkTransferRequest.EXTERNAL_ID_MAX_LENGTH

        NetworkTransferSubmissionPolicy.requireSubmittableKey("a".repeat(limit))

        assertThatThrownBy { NetworkTransferSubmissionPolicy.requireSubmittableKey("a".repeat(limit + 1)) }
            .isInstanceOfSatisfying(InvalidRequestException::class.java) {
                assertThat(it.field).isEqualTo("externalTxId")
            }
        // 원장 폭(128)까지는 저장되지만 벤더에는 나갈 수 없다 — 그 구간이 정확히 거절 대상이다.
        assertThatThrownBy { NetworkTransferSubmissionPolicy.requireSubmittableKey("a".repeat(128)) }
            .isInstanceOf(InvalidRequestException::class.java)
    }

    @Test
    fun `행이 없으면 제출, SUBMITTED는 벤더를 부르지 않고, REQUESTED는 같은 본문 재제출로 회수한다`() {
        assertThat(NetworkTransferSubmissionPolicy.decide(null))
            .isEqualTo(NetworkTransferSubmissionAction.Submit)
        assertThat(NetworkTransferSubmissionPolicy.decide(record(SubmissionStatus.SUBMITTED)))
            .isEqualTo(NetworkTransferSubmissionAction.AlreadySubmitted)
        assertThat(NetworkTransferSubmissionPolicy.decide(record(SubmissionStatus.REQUESTED)))
            .isEqualTo(NetworkTransferSubmissionAction.Recover)
    }

    @Test
    fun `FAILED 재시도는 거절한다 — 같은 키는 새 전송을 만들지 못하고 새 키는 이중 지급이 될 수 있다`() {
        assertThat(NetworkTransferSubmissionPolicy.decide(record(SubmissionStatus.FAILED)))
            .isEqualTo(NetworkTransferSubmissionAction.RetryNotAllowed)

        assertThatThrownBy { NetworkTransferSubmissionPolicy.rejectRetry(EXTERNAL_ID) }
            .isInstanceOfSatisfying(UnprocessableRequestException::class.java) {
                assertThat(it.resource).isEqualTo("submission")
                assertThat(it.key).isEqualTo(EXTERNAL_ID)
            }
    }

    @Test
    fun `관찰이 요청과 같은 지갑·자산·목적지·금액일 때만 수용한다`() {
        assertThat(NetworkTransferSubmissionPolicy.matches(observation(), REQUEST)).isTrue()

        assertThat(NetworkTransferSubmissionPolicy.matches(observation(vendorWalletId = "wa-other"), REQUEST)).isFalse()
        assertThat(NetworkTransferSubmissionPolicy.matches(observation(vendorAssetId = "EthereumSepolia:Native"), REQUEST)).isFalse()
        assertThat(NetworkTransferSubmissionPolicy.matches(observation(destinationAddress = OTHER_ADDRESS), REQUEST)).isFalse()
        assertThat(NetworkTransferSubmissionPolicy.matches(observation(amountBaseUnits = "1000001"), REQUEST)).isFalse()
        assertThat(NetworkTransferSubmissionPolicy.matches(observation(externalId = "other-key"), REQUEST)).isFalse()
    }

    @Test
    fun `조회 관찰은 제출 키가 선택이라 없음을 불일치로 바꾸지 않는다`() {
        // 제출 응답의 externalId 결속은 어댑터가 강제하므로(없거나 다르면 실패) 그 경로에는 null이 오지 않는다.
        // 이 관용은 externalId가 선택 필드인 조회 관찰에 같은 대조 규칙을 쓰기 위한 것이다(계약13).
        assertThat(NetworkTransferSubmissionPolicy.matches(observation(externalId = null), REQUEST)).isTrue()
    }

    private fun record(status: SubmissionStatus): SubmissionRecord =
        SubmissionRecord(
            externalTransactionId = EXTERNAL_ID,
            requestHash = "0".repeat(64),
            hashVersion = "v1",
            status = status,
            claimId = null,
            claimExpiresAt = null,
            transactionType = SubmissionTransactionType.WITHDRAWAL,
            vendorTransactionId = if (status == SubmissionStatus.SUBMITTED) "xfr-1" else null,
            senderAccountId = "acct-1",
            recipientType = SubmissionRecipientType.ADDRESS,
            recipientValue = ADDRESS,
            network = "ETHEREUM_SEPOLIA",
            symbol = "USDC",
            amount = "1",
            requestedAt = "20260917090000",
            respondedAt = null,
        )

    private fun observation(
        vendorWalletId: String = WALLET_ID,
        vendorAssetId: String = ASSET_KEY,
        destinationAddress: String = ADDRESS,
        amountBaseUnits: String = "1000000",
        externalId: String? = EXTERNAL_ID,
    ): NetworkTransferObservation =
        NetworkTransferObservation(
            transferId = "xfr-1",
            network = "EthereumSepolia",
            vendorWalletId = vendorWalletId,
            vendorAssetId = vendorAssetId,
            destinationAddress = destinationAddress,
            amountBaseUnits = amountBaseUnits,
            status = NetworkTransferStatus.PENDING,
            externalId = externalId,
            transactionHash = null,
            requestedAt = "2026-09-17T09:00:00Z",
            failureReason = null,
        )

    private companion object {
        const val EXTERNAL_ID = "ext-1"
        const val WALLET_ID = "wa-1"
        const val ASSET_KEY = "EthereumSepolia:Erc20:0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
        const val ADDRESS = "0x1111111111111111111111111111111111111111"
        const val OTHER_ADDRESS = "0x2222222222222222222222222222222222222222"

        val REQUEST =
            NetworkTransferRequest(
                scope =
                    NetworkWalletScope(
                        ProviderOrigin("test-dfns-origin", "dfns", "dfns", "test-dfns-platform", "test-dfns-organization", "TESTNET"),
                        "acct-1",
                        "ETHEREUM_SEPOLIA",
                    ),
                vendorWalletId = WALLET_ID,
                vendorAssetId = ASSET_KEY,
                destinationAddress = ADDRESS,
                amountBaseUnits = "1000000",
                externalId = EXTERNAL_ID,
            )
    }
}
