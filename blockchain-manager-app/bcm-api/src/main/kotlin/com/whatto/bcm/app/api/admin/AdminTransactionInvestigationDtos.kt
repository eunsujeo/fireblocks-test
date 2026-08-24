package com.whatto.bcm.app.api.admin

import com.whatto.bcm.domain.admin.TransactionAllowance
import com.whatto.bcm.domain.admin.TransactionBoostAttempt
import com.whatto.bcm.domain.admin.TransactionFeeQuote
import com.whatto.bcm.domain.admin.TransactionInvestigation
import com.whatto.bcm.domain.admin.TransactionInvestigationSummary
import com.whatto.bcm.domain.admin.TransactionSweepExecution
import com.whatto.bcm.domain.admin.TransactionSweepItem
import com.whatto.bcm.domain.admin.TransactionTimelineEntry
import java.math.BigDecimal

data class AdminTransactionInvestigationData(
    val summary: AdminTransactionInvestigationSummaryData,
    val timeline: List<AdminTransactionTimelineEntryData>,
    val boosts: List<AdminTransactionBoostData>,
    val sweepExecution: AdminTransactionSweepExecutionData?,
    val allowances: List<AdminTransactionAllowanceData>,
    val feeQuotes: List<AdminTransactionFeeQuoteData>,
    val truncatedSources: List<String>,
) {
    companion object {
        fun from(investigation: TransactionInvestigation) =
            AdminTransactionInvestigationData(
                summary = AdminTransactionInvestigationSummaryData.from(investigation.summary),
                timeline = investigation.timeline.map(AdminTransactionTimelineEntryData::from),
                boosts = investigation.boosts.map(AdminTransactionBoostData::from),
                sweepExecution = investigation.sweepExecution?.let(AdminTransactionSweepExecutionData::from),
                allowances = investigation.allowances.map(AdminTransactionAllowanceData::from),
                feeQuotes = investigation.feeQuotes.map(AdminTransactionFeeQuoteData::from),
                truncatedSources = investigation.truncatedSources,
            )
    }
}

data class AdminTransactionInvestigationSummaryData(
    val rootTransactionId: String,
    val activeTransactionId: String,
    val externalTransactionId: String?,
    val transactionHash: String?,
    val accountId: String,
    val network: String,
    val symbol: String,
    val transactionType: String?,
    val status: String,
    val confirmationCount: Int,
    val vendorSubStatus: String?,
    val vendorNetworkStatus: String?,
    val submissionStatus: String?,
    val amount: String?,
    val senderAccountId: String?,
    val receiverType: String?,
    val receiverValue: String?,
    val sweepExecutionId: String?,
    val submissionRequestedAt: String?,
    val submissionRespondedAt: String?,
    val vendorCreatedAt: String,
    val firstDetectedAt: String,
    val lastChangedAt: String,
    val reconciliationCheckedAt: String?,
    val reconciliationCheckCount: Int,
    val reconciliationStoppedAt: String?,
) {
    companion object {
        fun from(summary: TransactionInvestigationSummary) =
            AdminTransactionInvestigationSummaryData(
                rootTransactionId = summary.rootTransactionId,
                activeTransactionId = summary.activeTransactionId,
                externalTransactionId = summary.externalTransactionId,
                transactionHash = summary.transactionHash,
                accountId = summary.accountId,
                network = summary.network,
                symbol = summary.symbol,
                transactionType = summary.transactionType,
                status = summary.status,
                confirmationCount = summary.confirmationCount,
                vendorSubStatus = summary.vendorSubStatus,
                vendorNetworkStatus = summary.vendorNetworkStatus,
                submissionStatus = summary.submissionStatus,
                amount = summary.amount.plain(),
                senderAccountId = summary.senderAccountId,
                receiverType = summary.receiverType,
                receiverValue = summary.receiverValue,
                sweepExecutionId = summary.sweepExecutionId,
                submissionRequestedAt = summary.submissionRequestedAt?.toString(),
                submissionRespondedAt = summary.submissionRespondedAt?.toString(),
                vendorCreatedAt = summary.vendorCreatedAt.toString(),
                firstDetectedAt = summary.firstDetectedAt.toString(),
                lastChangedAt = summary.lastChangedAt.toString(),
                reconciliationCheckedAt = summary.reconciliationCheckedAt?.toString(),
                reconciliationCheckCount = summary.reconciliationCheckCount,
                reconciliationStoppedAt = summary.reconciliationStoppedAt?.toString(),
            )
    }
}

data class AdminTransactionTimelineEntryData(
    val source: String,
    val code: String,
    val status: String?,
    val observedAt: String?,
    val identifier: String?,
) {
    companion object {
        fun from(entry: TransactionTimelineEntry) =
            AdminTransactionTimelineEntryData(
                entry.source,
                entry.code,
                entry.status,
                entry.observedAt?.toString(),
                entry.identifier,
            )
    }
}

data class AdminTransactionBoostData(
    val attemptSequence: Int,
    val externalTransactionId: String,
    val status: String,
    val replacedTransactionId: String,
    val replacedTransactionHash: String,
    val newTransactionId: String?,
    val feeLevel: String,
    val gasless: Boolean,
    val requestedAt: String,
    val respondedAt: String?,
) {
    companion object {
        fun from(boost: TransactionBoostAttempt) =
            AdminTransactionBoostData(
                boost.attemptSequence,
                boost.externalTransactionId,
                boost.status,
                boost.replacedTransactionId,
                boost.replacedTransactionHash,
                boost.newTransactionId,
                boost.feeLevel,
                boost.gasless,
                boost.requestedAt.toString(),
                boost.respondedAt?.toString(),
            )
    }
}

data class AdminTransactionSweepExecutionData(
    val executionId: String,
    val externalTransactionId: String,
    val status: String,
    val operatorAccountId: String,
    val contractAddress: String,
    val requestedTotalAmount: String,
    val actualTotalAmount: String?,
    val transactionId: String?,
    val transactionHash: String?,
    val requestedAt: String,
    val finishedAt: String?,
    val items: List<AdminTransactionSweepItemData>,
) {
    companion object {
        fun from(execution: TransactionSweepExecution) =
            AdminTransactionSweepExecutionData(
                execution.executionId,
                execution.externalTransactionId,
                execution.status,
                execution.operatorAccountId,
                execution.contractAddress,
                execution.requestedTotalAmount.toPlainString(),
                execution.actualTotalAmount.plain(),
                execution.transactionId,
                execution.transactionHash,
                execution.requestedAt.toString(),
                execution.finishedAt?.toString(),
                execution.items.map(AdminTransactionSweepItemData::from),
            )
    }
}

data class AdminTransactionSweepItemData(
    val sequence: Int,
    val accountId: String,
    val sourceAddress: String,
    val requestedAmount: String,
    val actualAmount: String?,
    val status: String,
    val failureCode: String?,
    val logIndex: Int?,
) {
    companion object {
        fun from(item: TransactionSweepItem) =
            AdminTransactionSweepItemData(
                item.sequence,
                item.accountId,
                item.sourceAddress,
                item.requestedAmount.toPlainString(),
                item.actualAmount.plain(),
                item.status,
                item.failureCode,
                item.logIndex,
            )
    }
}

data class AdminTransactionAllowanceData(
    val accountId: String,
    val network: String,
    val symbol: String,
    val contractAddress: String,
    val cap: String,
    val observedAllowance: String,
    val status: String,
    val checkedAt: String,
) {
    companion object {
        fun from(allowance: TransactionAllowance) =
            AdminTransactionAllowanceData(
                allowance.accountId,
                allowance.network,
                allowance.symbol,
                allowance.contractAddress,
                allowance.cap.toPlainString(),
                allowance.observedAllowance.toPlainString(),
                allowance.status,
                allowance.checkedAt.toString(),
            )
    }
}

data class AdminTransactionFeeQuoteData(
    val context: String,
    val level: String,
    val observedAt: String,
    val feePerByte: String?,
    val gasPrice: String?,
    val networkFee: String?,
    val baseFee: String?,
    val priorityFee: String?,
) {
    companion object {
        fun from(quote: TransactionFeeQuote) =
            AdminTransactionFeeQuoteData(
                quote.context,
                quote.level,
                quote.observedAt.toString(),
                quote.feePerByte.plain(),
                quote.gasPrice.plain(),
                quote.networkFee.plain(),
                quote.baseFee.plain(),
                quote.priorityFee.plain(),
            )
    }
}

private fun BigDecimal?.plain(): String? = this?.toPlainString()
