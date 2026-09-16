package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.vendor.NetworkTransferEvent
import com.whatto.bcm.domain.vendor.NetworkTransferEventKind
import com.whatto.bcm.domain.vendor.NetworkTransferEventParser
import com.whatto.bcm.domain.webhook.WebhookPayloadException
import tools.jackson.databind.ObjectMapper

/**
 * NetworkTransferEventParser의 Dfns 구현 — 공식 `webhooks`의 `wallet.transfer.*` 다섯 종류와
 * `WebhookEnvelopeBase`·`TransferRequest` schema를 근거로 한다(계약13 "웹훅 전송 사건 관찰"). **내부 대역이며 판단 워커에 연결하지 않았다.**
 *
 * - `data.transferRequest`는 조회 응답과 같은 `TransferRequest`라 [DfnsTransferRequests]로 **같은 검사**를 거친다.
 * - 전송이 아닌 종류는 null이다. 전송 종류인데 `data.transferRequest`가 없거나 형식이 다르면 예외로 올린다 —
 *   자금 이동 신호를 "해석 불가"로 조용히 버리지 않는다.
 * - 사건의 `network`는 설정 매핑의 역방향으로 BCM 코드를 되찾는다(`bcm.dfns.networks` 값은 중복될 수 없다).
 *   매핑에 없는 네트워크의 전송 사건은 BCM이 해석할 수 없으므로 거절한다 — 조용히 넘기면 관리 대상 이동을 놓친다.
 * - 알림 메타는 종류와 무관하게 [DfnsWebhookEnvelopes]가 명세 `WebhookEnvelopeBase`대로 해석한다.
 */
class DfnsNetworkTransferEventParser(
    private val objectMapper: ObjectMapper,
    private val properties: DfnsProperties,
) : NetworkTransferEventParser {
    private val networksByVendorValue: Map<String, String> = properties.bcmNetworks()

    override fun parse(payload: ByteArray): NetworkTransferEvent? {
        val root =
            try {
                objectMapper.readTree(payload)
            } catch (exception: RuntimeException) {
                throw failure("웹훅 원문을 JSON으로 읽을 수 없다", exception)
            }
        if (!root.isObject) throw failure("웹훅 원문이 JSON 객체가 아니다", null)
        val kind = NetworkTransferEventKind.ofVendorKind(DfnsTransferRequests.requiredText(root, "kind", ::failure)) ?: return null
        val transferRequest = root.path("data").path("transferRequest")
        if (!transferRequest.isObject) throw failure("전송 사건 결손: data.transferRequest", null)
        val vendorNetwork = DfnsTransferRequests.vendorNetwork(transferRequest, ::failure)
        val network =
            networksByVendorValue[vendorNetwork]
                ?: throw failure("설정에 없는 네트워크의 전송 사건: data.transferRequest.network", null)
        return NetworkTransferEvent(
            delivery = DfnsWebhookEnvelopes.delivery(root, ::failure),
            kind = kind,
            observation =
                DfnsTransferRequests.normalize(
                    node = transferRequest,
                    network = network,
                    vendorNetwork = vendorNetwork,
                    // 웹훅은 우리가 요청한 호출의 응답이 아니라 벤더가 알려주는 지갑이므로 대조할 기대값이 없다.
                    expectedWalletId = null,
                    failure = ::failure,
                ),
        )
    }

    /** 원문 값은 담지 않고 필드 이름만 담는다 — 인박스에 보관한 원문이 증적이다. */
    private fun failure(
        reason: String,
        cause: Throwable?,
    ): RuntimeException = WebhookPayloadException("Dfns 웹훅 $reason", cause)
}
