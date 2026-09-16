package com.whatto.bcm.infra.client.dfns

import com.whatto.bcm.domain.vendor.NetworkChainDirection
import com.whatto.bcm.domain.vendor.NetworkChainEvent
import com.whatto.bcm.domain.vendor.NetworkChainEventKind
import com.whatto.bcm.domain.vendor.NetworkChainEventParser
import com.whatto.bcm.domain.vendor.NetworkChainTransfer
import com.whatto.bcm.domain.vendor.NetworkChainTransferStatus
import com.whatto.bcm.domain.webhook.WebhookPayloadException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

/**
 * NetworkChainEventParser의 Dfns 구현 — 채택 명세 1.1018.3 `webhooks`의 `wallet.blockchainevent.detected`·
 * `wallet.blockchain_event.transfer.included`와 그 `data.blockchainEvent`(`WalletHistoryEvent`)·`data.wallet`(`Wallet`)이 근거다
 * (계약13 "웹훅 온체인 이동 사건 관찰"). `dfns`에서 조립되어 입금 판단이 소비한다.
 *
 * - 두 종류가 아니면 null이다. 두 종류인데 형식이 다르면 예외로 올린다 — 입금 신호를 "해석 불가"로 조용히 버리지 않는다.
 * - `data.wallet.id`는 사건의 `walletId`와 같아야 한다. 두 값이 어긋난 본문은 어느 지갑의 이동인지 증명하지 못한다.
 * - 자산 키는 이동 종류(`Erc20Transfer` 등)를 자산 kind로 옮겨 등록과 **같은 규칙**으로 만든다. 문서화된 미지원 종류는 키를 만들지 않고
 *   원어만 남긴다 — 등록할 수 없는 자산이라도 사건 자체를 버리지 않는다(미지원 자산 판단은 워커의 몫).
 *   문서에 없는 종류는 명세를 만족하지 않는 본문이므로 미지원으로 받아들이지 않고 거절한다.
 * - 필수 필드는 oneOf 변형별로 다르다. 모든 변형의 공통 필수는 늘 검사하고, 모델 대상 종류는 그 변형이 요구하는 필드까지 검사한다 —
 *   관찰값에 담지 않는 필드도 결손이면 명세를 만족하지 않는다.
 * - 알림 메타는 [DfnsWebhookEnvelopes]가 명세 `WebhookEnvelopeBase`대로 해석한다.
 */
class DfnsNetworkChainEventParser(
    private val objectMapper: ObjectMapper,
    properties: DfnsProperties,
) : NetworkChainEventParser {
    private val networksByVendorValue: Map<String, String> = properties.bcmNetworks()

    override fun parse(payload: ByteArray): NetworkChainEvent? {
        val root =
            try {
                objectMapper.readTree(payload)
            } catch (exception: RuntimeException) {
                throw failure("웹훅 원문을 JSON으로 읽을 수 없다", exception)
            }
        if (!root.isObject) throw failure("웹훅 원문이 JSON 객체가 아니다", null)
        val kind = NetworkChainEventKind.ofVendorKind(requiredText(root, "kind")) ?: return null
        val event = root.path("data").path("blockchainEvent")
        if (!event.isObject) throw failure("온체인 이동 사건 결손: data.blockchainEvent", null)
        val wallet = root.path("data").path("wallet")
        if (!wallet.isObject) throw failure("온체인 이동 사건 결손: data.wallet", null)
        return NetworkChainEvent(
            delivery = DfnsWebhookEnvelopes.delivery(root, ::failure),
            kind = kind,
            observation = transfer(event, wallet),
        )
    }

    private fun transfer(
        event: JsonNode,
        wallet: JsonNode,
    ): NetworkChainTransfer {
        val vendorAssetKind = requiredText(event, "kind")
        if (vendorAssetKind !in DfnsChainEventKinds.DOCUMENTED) {
            throw failure("명세에 없는 이동 종류: data.blockchainEvent.kind", null)
        }
        DfnsChainEventKinds.COMMON_REQUIRED_TEXTS.forEach { requiredText(event, it) }
        // 모든 변형의 공통 필수 — 값을 쓰지 않더라도 결손이면 이동 사건으로 인정하지 않는다.
        val metadata = event.path("metadata")
        if (!metadata.isObject) throw failure("결손: data.blockchainEvent.metadata", null)
        if (!metadata.path("asset").isObject) throw failure("결손: data.blockchainEvent.metadata.asset", null)
        val modeled = DfnsChainEventKinds.modeled(vendorAssetKind)
        modeled?.requiredTexts?.forEach { requiredText(event, it) }
        modeled?.requiredNumbers?.forEach { requiredNumber(event, it) }

        val vendorNetwork = requiredText(event, "network")
        val network =
            networksByVendorValue[vendorNetwork]
                ?: throw failure("설정에 없는 네트워크의 온체인 이동 사건: data.blockchainEvent.network", null)
        val vendorWalletId = requiredText(event, "walletId")
        // 사건과 함께 온 지갑 객체가 같은 지갑·같은 네트워크를 가리켜야 이 이동을 그 지갑의 것으로 볼 수 있다.
        if (requiredText(wallet, "id") != vendorWalletId) throw failure("지갑 ID 불일치: data.wallet.id", null)
        if (requiredText(wallet, "network") != vendorNetwork) throw failure("지갑 network 불일치: data.wallet.network", null)
        val direction =
            NetworkChainDirection.ofVendorValue(requiredText(event, "direction"))
                ?: throw failure("필드 형식 오류: direction", null)
        val status =
            NetworkChainTransferStatus.ofVendorValue(requiredText(event, "status"))
                ?: throw failure("필드 형식 오류: status", null)
        return try {
            NetworkChainTransfer(
                network = network,
                vendorWalletId = vendorWalletId,
                vendorWalletAddress = optionalText(wallet, "address"),
                vendorAssetId = modeled?.let { vendorAssetId(event, vendorNetwork, it.assetKind) },
                vendorAssetKind = vendorAssetKind,
                direction = direction,
                status = status,
                // 미지원 종류는 `value`가 필수가 아니고 의미도 다르므로(NFT의 tokenId 등) 금액을 읽지 않는다.
                amountBaseUnits = modeled?.let { requiredText(event, "value") },
                fromAddress = optionalText(event, "from"),
                toAddress = optionalText(event, "to"),
                transactionHash = requiredText(event, "txHash"),
                blockNumber = blockNumber(event),
                eventIndex = optionalText(event, "index"),
                observedAt = requiredText(event, "timestamp"),
            )
        } catch (exception: IllegalArgumentException) {
            throw failure("온체인 이동 사건 필드 형식 오류", exception)
        }
    }

    /** 등록·잔액과 같은 키 규칙을 적용한다 — locator 형식이 깨졌으면 키를 만들지 않고 실패한다. */
    private fun vendorAssetId(
        event: JsonNode,
        vendorNetwork: String,
        assetKind: String,
    ): String {
        val locator = DfnsAssetKeys.locatorField(assetKind)?.let { requiredText(event, it) }
        return runCatching { DfnsAssetKeys.of(vendorNetwork, assetKind, locator) }.getOrNull()
            ?: throw failure("자산 지정을 해석할 수 없다: data.blockchainEvent", null)
    }

    /** 변형이 number로 요구하는 필드의 존재·형식만 확인한다(관찰값에는 담지 않는다). */
    private fun requiredNumber(
        node: JsonNode,
        field: String,
    ) {
        val value = node.path(field)
        if (value.isMissingNode || value.isNull) throw failure("결손: $field", null)
        if (!value.isNumber) throw failure("필드 형식 오류: $field", null)
    }

    /** 명세가 number로 둔 블록 번호 — 정수가 아니거나 Long 범위 밖이면 사건으로 받지 않는다. */
    private fun blockNumber(event: JsonNode): Long {
        val value = event.path("blockNumber")
        if (value.isMissingNode || value.isNull) throw failure("결손: blockNumber", null)
        if (!value.isIntegralNumber || !value.canConvertToLong()) throw failure("필드 형식 오류: blockNumber", null)
        return value.asLong()
    }

    private fun requiredText(
        node: JsonNode,
        field: String,
    ): String = DfnsTransferRequests.requiredText(node, field, ::failure)

    private fun optionalText(
        node: JsonNode,
        field: String,
    ): String? = DfnsTransferRequests.optionalText(node, field, ::failure)

    /** 원문 값은 담지 않고 필드 이름만 담는다 — 인박스에 보관한 원문이 증적이다. */
    private fun failure(
        reason: String,
        cause: Throwable?,
    ): RuntimeException = WebhookPayloadException("Dfns 웹훅 $reason", cause)
}
