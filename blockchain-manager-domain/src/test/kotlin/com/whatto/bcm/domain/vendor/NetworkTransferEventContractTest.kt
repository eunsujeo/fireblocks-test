package com.whatto.bcm.domain.vendor

import com.whatto.bcm.domain.webhook.VendorWebhookDelivery
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** 웹훅 전송 사건의 순수 계약(계약13 "웹훅 전송 사건 관찰") — 종류 대응과 알림 메타 불변식이다. */
class NetworkTransferEventContractTest {
    @Test
    fun `문서화된 전송 종류 다섯만 사건 종류로 옮긴다`() {
        assertThat(NetworkTransferEventKind.ofVendorKind("wallet.transfer.requested")).isEqualTo(NetworkTransferEventKind.REQUESTED)
        assertThat(NetworkTransferEventKind.ofVendorKind("wallet.transfer.broadcasted")).isEqualTo(NetworkTransferEventKind.BROADCASTED)
        assertThat(NetworkTransferEventKind.ofVendorKind("wallet.transfer.confirmed")).isEqualTo(NetworkTransferEventKind.CONFIRMED)
        assertThat(NetworkTransferEventKind.ofVendorKind("wallet.transfer.failed")).isEqualTo(NetworkTransferEventKind.FAILED)
        assertThat(NetworkTransferEventKind.ofVendorKind("wallet.transfer.rejected")).isEqualTo(NetworkTransferEventKind.REJECTED)
        assertThat(NetworkTransferEventKind.entries).hasSize(5)
        // 거래·입금 감지·지갑 종류는 전송 사건이 아니다. 대소문자·공백이 다른 값도 전송 종류로 받지 않는다.
        listOf(
            "wallet.transaction.confirmed",
            "wallet.blockchainevent.detected",
            "wallet.blockchain_event.transfer.included",
            "wallet.created",
            "",
            "WALLET.TRANSFER.CONFIRMED",
            " wallet.transfer.confirmed",
        ).forEach { assertThat(NetworkTransferEventKind.ofVendorKind(it)).describedAs(it).isNull() }
    }

    @Test
    fun `종류와 관찰 상태를 독립으로 담아 종류를 상태로 번역하지 않는다`() {
        // 종류는 알림 지점, 상태는 관찰값이다 — 둘이 짝을 이룬다는 보장이 문서에 없으므로 모델이 강제하지 않는다.
        val event = event(kind = NetworkTransferEventKind.CONFIRMED, status = NetworkTransferStatus.FAILED)

        assertThat(event.kind).isEqualTo(NetworkTransferEventKind.CONFIRMED)
        assertThat(event.observation.status).isEqualTo(NetworkTransferStatus.FAILED)
        assertThat(event.observation.status.terminal).isTrue()
        assertThat(event.observation.status.onChainSubmitted).isNull()
    }

    @Test
    fun `알림 메타의 빈 값·0 이하 전달 시도는 사건으로 받지 않는다`() {
        assertThat(delivery(deliveryAttempt = 3, retryOf = "whe-1").retryOfNotificationId).isEqualTo("whe-1")

        listOf<Pair<String, () -> VendorWebhookDelivery>>(
            "deliveryAttempt" to { delivery(deliveryAttempt = 0) },
            "notificationId" to { delivery(notificationId = " ") },
            "occurredAt" to { delivery(occurredAt = "") },
            "retryOfNotificationId" to { delivery(retryOf = "") },
        ).forEach { (field, build) ->
            assertThatThrownBy { build() }
                .describedAs(field)
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining(field)
        }
    }

    private fun delivery(
        notificationId: String = "whe-544ul-uqgad-jkgltj5p6fvd04cj",
        occurredAt: String = "2026-09-16T00:00:00.000Z",
        deliveryAttempt: Int = 1,
        retryOf: String? = null,
    ) = VendorWebhookDelivery(notificationId, occurredAt, deliveryAttempt, retryOf)

    private fun event(
        kind: NetworkTransferEventKind = NetworkTransferEventKind.CONFIRMED,
        status: NetworkTransferStatus = NetworkTransferStatus.CONFIRMED,
    ) = NetworkTransferEvent(
        delivery = delivery(),
        kind = kind,
        observation =
            NetworkTransferObservation(
                transferId = "xfr-20g4k-nsdpo-mg6arrifgvid4orn",
                network = "ETHEREUM_SEPOLIA",
                vendorWalletId = "wa-5pfuu-9euek-h0odgb6snva8ph3k",
                vendorAssetId = "EthereumSepolia:Native",
                destinationAddress = "0x00e3495cf6af59008f22ffaf32d4c92ac33dac47",
                amountBaseUnits = "1500000",
                status = status,
                externalId = "wd-ext-1",
                transactionHash = null,
                requestedAt = "2026-09-16T00:00:00.000Z",
                failureReason = null,
            ),
    )
}
