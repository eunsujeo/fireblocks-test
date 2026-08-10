package com.whatto.bcm.app.api.transaction

import com.whatto.bcm.app.api.web.ApiResponse
import com.whatto.bcm.app.api.web.Pagination
import com.whatto.bcm.app.api.web.RequestIdFilter
import com.whatto.bcm.app.application.submission.TransactionSubmissionService
import com.whatto.bcm.app.application.transaction.TransactionPageQuery
import com.whatto.bcm.app.application.transaction.TransactionQueryService
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
class TransactionController(
    private val submissionService: TransactionSubmissionService,
    private val queryService: TransactionQueryService,
) {
    @PostMapping("/transactions")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun submitTransaction(
        @Valid @RequestBody request: TransactionRequest,
        httpRequest: HttpServletRequest,
    ): ApiResponse<SubmitResultData> {
        val result = submissionService.submit(request.toCommand())
        return ApiResponse.of(
            SubmitResultData(txId = result.transactionId),
            RequestIdFilter.requestIdOf(httpRequest),
        )
    }

    @GetMapping("/transactions/{txId}")
    fun transactionOf(
        @PathVariable txId: String,
        httpRequest: HttpServletRequest,
    ): ApiResponse<TransferData> =
        ApiResponse.of(
            TransferData.from(queryService.transaction(txId)),
            RequestIdFilter.requestIdOf(httpRequest),
        )

    @GetMapping("/transactions/external/{externalTxId}")
    fun transactionByExternalTxId(
        @PathVariable @Size(max = 128) externalTxId: String,
        httpRequest: HttpServletRequest,
    ): ApiResponse<TransferData> =
        ApiResponse.of(
            TransferData.from(queryService.transactionByExternalTransactionId(externalTxId)),
            RequestIdFilter.requestIdOf(httpRequest),
        )

    @GetMapping("/accounts/{accountId}/transactions")
    fun transactionsOf(
        @PathVariable @Size(max = 64) accountId: String,
        @RequestParam(required = false) after: String?,
        @RequestParam(required = false) before: String?,
        @RequestParam(required = false) order: String?,
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) limit: String?,
        @RequestParam(required = false) cursor: String?,
        httpRequest: HttpServletRequest,
    ): ApiResponse<List<TransferData>> {
        val page =
            queryService.transactions(
                accountId,
                TransactionPageQuery(after, before, order, status, limit, cursor),
            )
        return ApiResponse.of(
            data = page.data.map(TransferData::from),
            requestId = RequestIdFilter.requestIdOf(httpRequest),
            pagination = Pagination(nextCursor = page.nextCursor, hasMore = page.hasMore),
        )
    }
}
