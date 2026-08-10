package com.whatto.bcm.app.api.transaction

import com.fasterxml.jackson.annotation.JsonIgnore
import com.whatto.bcm.app.api.account.AccountController
import com.whatto.bcm.app.application.submission.TransactionSubmissionCommand
import com.whatto.bcm.app.application.submission.TransactionSubmissionRecipient
import com.whatto.bcm.app.application.transaction.TransactionView
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.support.submission.SubmissionAmounts
import jakarta.validation.Valid
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size

data class TransactionRequest(
    @field:NotBlank
    @field:Size(max = 128)
    val externalTxId: String?,
    @field:NotNull
    @field:Valid
    val from: TransferPeerRequest?,
    @field:NotNull
    @field:Valid
    val to: TransferPeerRequest?,
    @field:NotBlank
    @field:Pattern(regexp = AccountController.NETWORK_PATTERN)
    val network: String?,
    @field:NotBlank
    @field:Pattern(regexp = AccountController.SYMBOL_PATTERN)
    val symbol: String?,
    @field:NotBlank
    @field:Pattern(regexp = DECIMAL_AMOUNT_PATTERN)
    val amount: String?,
    val note: String?,
    val travelRule: Map<String, Any?>?,
) {
    @get:JsonIgnore
    @get:AssertTrue(message = "from must be an ACCOUNT peer")
    val validSource: Boolean
        get() = from == null || from.type == PeerType.ACCOUNT

    @get:JsonIgnore
    @get:AssertTrue(message = "amount must be positive and fit NUMERIC(36,18)")
    val validAmount: Boolean
        get() = amount == null || SubmissionAmounts.isValid(amount)

    fun toCommand(): TransactionSubmissionCommand {
        val source = checkNotNull(from)
        val destination = checkNotNull(to)
        return TransactionSubmissionCommand(
            externalTransactionId = checkNotNull(externalTxId),
            senderAccountId = checkNotNull(source.accountId),
            recipient = destination.toRecipient(),
            network = checkNotNull(network),
            symbol = checkNotNull(symbol),
            amount = checkNotNull(amount),
            note = note,
            travelRuleMessage = travelRule,
        )
    }

    private companion object {
        const val DECIMAL_AMOUNT_PATTERN = "(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?"
    }
}

data class TransferPeerRequest(
    @field:NotNull
    val type: PeerType?,
    @field:Size(max = 128)
    val address: String?,
    @field:Size(max = 64)
    val accountId: String?,
    @field:Size(max = 128)
    val walletId: String?,
) {
    @get:JsonIgnore
    @get:AssertTrue(message = "peer identifier must match type")
    val validIdentifier: Boolean
        get() =
            when (type) {
                null -> true
                PeerType.ADDRESS -> address.isPresent() && accountId == null && walletId == null
                PeerType.ACCOUNT -> accountId.isPresent() && address == null && walletId == null
                PeerType.WHITELISTED -> walletId.isPresent() && address == null && accountId == null
            }

    fun toRecipient(): TransactionSubmissionRecipient =
        when (checkNotNull(type)) {
            PeerType.ADDRESS -> TransactionSubmissionRecipient.Address(checkNotNull(address))
            PeerType.ACCOUNT -> TransactionSubmissionRecipient.Account(checkNotNull(accountId))
            PeerType.WHITELISTED -> TransactionSubmissionRecipient.Whitelisted(checkNotNull(walletId))
        }

    private fun String?.isPresent(): Boolean = !isNullOrBlank()
}

enum class PeerType {
    ADDRESS,
    ACCOUNT,
    WHITELISTED,
}

data class SubmitResultData(
    val txId: String,
)

data class TransferData(
    val txId: String,
    val txHash: String?,
    val externalTxId: String?,
    val network: String,
    val symbol: String,
    val amount: String,
    val from: String?,
    val to: String?,
    val status: TxStatus,
    val numOfConfirmations: Int,
    val createdAt: String,
    val lastUpdated: String,
) {
    companion object {
        fun from(view: TransactionView): TransferData =
            TransferData(
                txId = view.transactionId,
                txHash = view.transactionHash,
                externalTxId = view.externalTransactionId,
                network = view.network,
                symbol = view.symbol,
                amount = view.amount,
                from = view.sourceAddress,
                to = view.destinationAddress,
                status = view.status,
                numOfConfirmations = view.confirmationCount,
                createdAt = view.createdAt,
                lastUpdated = view.lastUpdated,
            )
    }
}
