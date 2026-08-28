package com.whatto.bcm.app.application.sweep

import com.whatto.bcm.app.application.account.AccountQueryService
import com.whatto.bcm.app.application.admin.ExecutionGateQueryService
import com.whatto.bcm.app.application.asset.VendorAssetMappingQueryService
import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.admin.ExecutionGateStatus
import com.whatto.bcm.domain.admin.ExecutionGateType
import com.whatto.bcm.domain.event.EventIdGenerator
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.InvalidRequestException
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import com.whatto.bcm.domain.exception.UnprocessableRequestException
import com.whatto.bcm.domain.sweep.SweepRequest
import com.whatto.bcm.domain.sweep.SweepRequestAcceptance
import com.whatto.bcm.domain.sweep.SweepRequestItem
import com.whatto.bcm.domain.sweep.SweepRequestItemStatus
import com.whatto.bcm.domain.sweep.SweepRequestRepository
import com.whatto.bcm.domain.sweep.SweepRequestStatus
import com.whatto.bcm.support.submission.SweepRequestHashItem
import com.whatto.bcm.support.submission.SweepRequestHashes
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.stereotype.Service
import java.time.Clock

data class SweepRequestCommandItem(
    val accountId: String,
    val sourceEventIds: List<String>,
)

data class SweepRequestCommand(
    val externalSweepRequestId: String,
    val network: String,
    val symbol: String,
    val items: List<SweepRequestCommandItem>,
)

@Service
class SweepRequestService(
    private val transactionRunner: TransactionRunner,
    private val requests: SweepRequestRepository,
    private val accounts: AccountQueryService,
    private val assets: VendorAssetMappingQueryService,
    private val executionGates: ExecutionGateQueryService,
    private val ids: EventIdGenerator,
    private val clock: Clock,
) {
    fun accept(command: SweepRequestCommand): SweepRequest {
        val fingerprint =
            runCatching {
                SweepRequestHashes.requestV1(
                    command.network,
                    command.symbol,
                    command.items.map { SweepRequestHashItem(it.accountId, it.sourceEventIds) },
                )
            }.getOrElse { throw InvalidRequestException("items") }

        return transactionRunner.run {
            requests.findByExternalRequestId(command.externalSweepRequestId)?.let { existing ->
                if (existing.requestHash != fingerprint.requestHash) {
                    throw ConflictException("sweepRequest", command.externalSweepRequestId)
                }
                return@run existing
            }

            assets.requiredCurrentMapping(command.network, command.symbol)
            fingerprint.items.forEach { item ->
                val account = accounts.requiredAccount(item.accountId)
                if (account.accountType != AccountType.CUSTOMER) {
                    throw UnprocessableRequestException("sweepAccount", item.accountId)
                }
            }

            val gate = executionGates.lockAndFindCurrent(command.network, ExecutionGateType.SWEEP)
            val status =
                if (gate?.status == ExecutionGateStatus.STOPPED) {
                    SweepRequestStatus.BLOCKED
                } else {
                    SweepRequestStatus.ACCEPTED
                }
            val request =
                SweepRequest(
                    sweepRequestId = ids.nextId(),
                    externalSweepRequestId = command.externalSweepRequestId,
                    requestHash = fingerprint.requestHash,
                    network = command.network,
                    symbol = command.symbol,
                    status = status,
                    requestedAt = CoreDateTimes.now(clock),
                    items =
                        fingerprint.items.mapIndexed { index, item ->
                            SweepRequestItem(
                                sweepItemId = ids.nextId(),
                                sequence = index + 1,
                                accountId = item.accountId,
                                status = SweepRequestItemStatus.PENDING,
                                sourceEventIds = item.sourceEventIds,
                            )
                        },
                )

            when (val result = requests.accept(request)) {
                is SweepRequestAcceptance.Created -> result.request
                is SweepRequestAcceptance.Existing -> {
                    if (result.request.requestHash == request.requestHash) {
                        result.request
                    } else {
                        throw ConflictException("sweepRequest", command.externalSweepRequestId)
                    }
                }

                SweepRequestAcceptance.HashConflict ->
                    throw ConflictException("sweepRequest", command.externalSweepRequestId)

                is SweepRequestAcceptance.SourceEventNotFound ->
                    throw ResourceNotFoundException("sourceEvent", result.eventId)

                is SweepRequestAcceptance.SourceEventInvalid ->
                    throw UnprocessableRequestException("sourceEvent", result.eventId)

                is SweepRequestAcceptance.SourceEventNotCompleted ->
                    throw UnprocessableRequestException("sourceEventCompletion", result.eventId)

                is SweepRequestAcceptance.SourceEventConsumed ->
                    throw ConflictException("sourceEvent", result.eventId)
            }
        }
    }
}
