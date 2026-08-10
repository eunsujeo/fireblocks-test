package com.whatto.bcm.infra.client.fireblocks

import com.whatto.bcm.domain.tx.FinalityPolicy
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.domain.vendor.VendorStatusObservation
import com.whatto.bcm.domain.vendor.VendorStatusTranslator
import com.whatto.bcm.domain.vendor.VendorTransaction
import com.whatto.bcm.domain.webhook.WebhookPayloadException
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.ObjectMapper

data class FireblocksTransaction(
    val vendorTransactionId: String,
    val vendorAssetId: String,
    val managedVaultSource: Boolean,
    val sourceAddress: String?,
    /** 체인에 오르기 전 알림에는 비어 있을 수 있다 (02-bcm-flow 미확정 — 제출 직후 조회의 빈 필드). */
    val destinationAddress: String?,
    val amount: String,
    internal val rawStatus: String,
    val subStatus: String?,
    val networkStatus: String?,
    val transactionHash: String?,
    val externalTransactionId: String?,
    val confirmationCount: Int,
)

/** Fireblocks 원문 JSON을 워커가 소비할 검증된 관찰값으로 변환한다. */
@Component
class FireblocksTransactionParser(
    private val objectMapper: ObjectMapper,
) {
    fun parse(payload: String): FireblocksTransaction {
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

        return FireblocksTransaction(
            vendorTransactionId = requiredText(data.path("id").asString(), "data.id"),
            vendorAssetId = requiredText(data.path("assetId").asString(), "data.assetId"),
            managedVaultSource =
                requiredText(data.path("source").path("type").asString(), "data.source.type") == VAULT_ACCOUNT_SOURCE,
            sourceAddress = optionalText(data.path("sourceAddress").asString()),
            destinationAddress = optionalText(data.path("destinationAddress").asString()),
            amount = requiredText(data.path("amountInfo").path("amount").asString(), "data.amountInfo.amount"),
            rawStatus = requiredText(data.path("status").asString(), "data.status"),
            subStatus = optionalText(data.path("subStatus").asString()),
            networkStatus = optionalText(data.path("networkStatus").asString()),
            transactionHash = optionalText(data.path("txHash").asString()),
            externalTransactionId = optionalText(data.path("externalTxId").asString()),
            confirmationCount = confirmationsNode.asInt(),
        )
    }

    private fun requiredText(
        value: String,
        field: String,
    ): String = value.takeIf(String::isNotBlank) ?: throw WebhookPayloadException("missing $field")

    private fun optionalText(value: String): String? = value.takeIf(String::isNotBlank)

    private companion object {
        const val VAULT_ACCOUNT_SOURCE = "VAULT_ACCOUNT"
    }
}

/** Fireblocks 상태 원어를 벤더 중립 TxStatus로 번역한다. */
@Component
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

    fun translate(
        transaction: FireblocksTransaction,
        network: String,
    ): TxStatus =
        translate(
            rawStatus = transaction.rawStatus,
            subStatus = transaction.subStatus,
            confirmationCount = transaction.confirmationCount,
            network = network,
        )

    fun translate(
        transaction: VendorTransaction,
        network: String,
    ): TxStatus =
        translate(
            rawStatus = transaction.rawStatus,
            subStatus = transaction.subStatus,
            confirmationCount = transaction.confirmationCount,
            network = network,
        )

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
        val FREEZE_SUB_STATUSES = setOf("AUTO_FREEZE", "FROZEN_MANUALLY", "REJECTED_AML_SCREENING")
    }
}
