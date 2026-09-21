package com.whatto.bcm.infra.client.fireblocks

import com.whatto.bcm.domain.tx.FinalityPolicy
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.domain.vendor.VendorStatusObservation
import com.whatto.bcm.domain.vendor.VendorStatusTranslator
import com.whatto.bcm.domain.vendor.VendorTransaction
import com.whatto.bcm.domain.webhook.WebhookPayloadException
import com.whatto.bcm.domain.webhook.WebhookTransaction
import com.whatto.bcm.domain.webhook.WebhookTransactionParser
import com.whatto.bcm.infra.client.config.ConditionalOnFireblocksProtocol
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal

/** Fireblocks 원문 JSON을 워커가 소비할 검증된 관찰값으로 변환한다. */
@Component
@ConditionalOnFireblocksProtocol
class FireblocksTransactionParser(
    private val objectMapper: ObjectMapper,
) : WebhookTransactionParser {
    override fun parse(payload: String): WebhookTransaction {
        val data =
            try {
                objectMapper.readTree(payload).path("data")
            } catch (exception: JacksonException) {
                throw WebhookPayloadException("invalid webhook JSON", exception)
            }

        val confirmationsNode = data.path("numOfConfirmations")
        if (confirmationsNode.isMissingNode || confirmationsNode.isNull) {
            throw WebhookPayloadException("missing data.numOfConfirmations")
        }
        if (!confirmationsNode.isNumber || confirmationsNode.asInt() < 0) {
            throw WebhookPayloadException("invalid data.numOfConfirmations")
        }
        val createdAtNode = data.path("createdAt")
        if (createdAtNode.isMissingNode || createdAtNode.isNull) {
            throw WebhookPayloadException("missing data.createdAt")
        }
        if (!createdAtNode.isNumber || createdAtNode.asLong() < 0) {
            throw WebhookPayloadException("invalid data.createdAt")
        }

        return WebhookTransaction(
            vendorTransactionId = requiredText(data.path("id").asString(), "data.id"),
            vendorAssetId = requiredText(data.path("assetId").asString(), "data.assetId"),
            managedVaultSource =
                requiredText(data.path("source").path("type").asString(), "data.source.type") == VAULT_ACCOUNT_SOURCE,
            sourceAddress = optionalText(data.path("sourceAddress").asString()),
            destinationAddress = optionalText(data.path("destinationAddress").asString()),
            amount = requiredAmount(data.path("amountInfo").path("amount").asString(), "data.amountInfo.amount"),
            statusObservation =
                VendorStatusObservation(
                    rawStatus = requiredText(data.path("status").asString(), "data.status"),
                    subStatus = optionalText(data.path("subStatus").asString()),
                    confirmationCount = confirmationsNode.asInt(),
                ),
            networkStatus = optionalText(data.path("networkStatus").asString()),
            transactionHash = optionalText(data.path("txHash").asString()),
            externalTransactionId = optionalText(data.path("externalTxId").asString()),
            createdAtEpochMillis = createdAtNode.asLong(),
        )
    }

    private fun requiredText(
        value: String,
        field: String,
    ): String = value.takeIf(String::isNotBlank) ?: throw WebhookPayloadException("missing $field")

    /**
     * 금액은 **경계에서 수로 검증한다**. 안쪽은 원장 금액과 `BigDecimal`로 대조하므로(03 V32 동일성 검사),
     * 수가 아닌 문자열을 통과시키면 그 실패가 판정 한가운데서 예외로 터지고 값이 로그로 샌다.
     * 여기서 걸러 **저장 가능한 사유만** 남긴다 — 사유에도 값은 넣지 않는다.
     */
    private fun requiredAmount(
        value: String,
        field: String,
    ): String {
        val text = requiredText(value, field)
        runCatching { BigDecimal(text) }.getOrElse { throw WebhookPayloadException("malformed $field") }
        return text
    }

    private fun optionalText(value: String): String? = value.takeIf(String::isNotBlank)

    private companion object {
        const val VAULT_ACCOUNT_SOURCE = "VAULT_ACCOUNT"
    }
}

/** Fireblocks 상태 원어를 벤더 중립 TxStatus로 번역한다. */
@Component
@ConditionalOnFireblocksProtocol
class FireblocksStatusTranslator(
    private val finalityPolicy: FinalityPolicy,
) : VendorStatusTranslator {
    override fun translate(
        observation: VendorStatusObservation,
        network: String,
    ): TxStatus =
        translate(
            rawStatus = observation.rawStatus,
            subStatus = observation.subStatus,
            confirmationCount = observation.confirmationCount,
            network = network,
        )

    override fun terminalStatusForReconciliation(
        observation: VendorStatusObservation,
        sourceType: String,
    ): TxStatus? {
        if (observation.subStatus in FREEZE_SUB_STATUSES) {
            return if (sourceType == VAULT_ACCOUNT_SOURCE) TxStatus.REJECTED else null
        }
        return when (observation.rawStatus) {
            "COMPLETED" -> TxStatus.FINALIZED
            "FAILED" -> TxStatus.FAILED
            "REJECTED", "BLOCKED" -> if (sourceType == VAULT_ACCOUNT_SOURCE) TxStatus.REJECTED else null
            else -> null
        }
    }

    fun translate(
        transaction: VendorTransaction,
        network: String,
    ): TxStatus = translate(transaction.statusObservation(), network)

    private fun translate(
        rawStatus: String,
        subStatus: String?,
        confirmationCount: Int,
        network: String,
    ): TxStatus {
        if (subStatus in FREEZE_SUB_STATUSES) return TxStatus.REJECTED
        return when (rawStatus) {
            "SUBMITTED", "PENDING_SIGNATURE", "QUEUED", "BROADCASTING" -> {
                TxStatus.SUBMITTED
            }

            "CONFIRMING" -> {
                TxStatus.CONFIRMED
            }

            "COMPLETED" -> {
                if (confirmationCount >= finalityPolicy.requiredConfirmations(network)) {
                    TxStatus.FINALIZED
                } else {
                    TxStatus.CONFIRMED
                }
            }

            "REJECTED", "BLOCKED" -> {
                TxStatus.REJECTED
            }

            "FAILED" -> {
                TxStatus.FAILED
            }

            else -> {
                throw WebhookPayloadException("unsupported data.status")
            }
        }
    }

    private companion object {
        const val VAULT_ACCOUNT_SOURCE = "VAULT_ACCOUNT"
        val FREEZE_SUB_STATUSES = setOf("AUTO_FREEZE", "FROZEN_MANUALLY", "REJECTED_AML_SCREENING")
    }
}
