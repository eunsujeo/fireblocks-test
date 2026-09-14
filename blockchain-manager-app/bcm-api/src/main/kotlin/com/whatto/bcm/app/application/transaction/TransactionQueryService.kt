package com.whatto.bcm.app.application.transaction

import com.whatto.bcm.app.application.account.AccountQueryService
import com.whatto.bcm.app.application.asset.VendorAssetMappingQueryService
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.exception.AssetNotSupportedException
import com.whatto.bcm.domain.exception.InvalidRequestException
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import com.whatto.bcm.domain.tx.BoostAttemptRepository
import com.whatto.bcm.domain.tx.TxRecord
import com.whatto.bcm.domain.tx.TxRecordRepository
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.domain.vendor.UnmappedVendorAssetAlert
import com.whatto.bcm.domain.vendor.UnmappedVendorAssetAlertPort
import com.whatto.bcm.domain.vendor.VendorStatusObservation
import com.whatto.bcm.domain.vendor.VendorStatusTranslator
import com.whatto.bcm.domain.vendor.VendorTransaction
import com.whatto.bcm.domain.vendor.VendorTransactionOrder
import com.whatto.bcm.domain.vendor.VendorTransactionPageRequest
import com.whatto.bcm.domain.vendor.VendorTransactionPort
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.time.DateTimeException
import java.time.Instant
import java.time.OffsetDateTime
import java.util.Base64

@Service
class TransactionQueryService(
    private val vendor: VendorTransactionPort,
    private val accounts: AccountQueryService,
    private val mappings: VendorAssetMappingQueryService,
    private val statusTranslator: VendorStatusTranslator,
    private val unmappedAssetAlertPort: UnmappedVendorAssetAlertPort,
    private val transactions: TxRecordRepository,
    private val boosts: BoostAttemptRepository,
) {
    fun transaction(transactionId: String): TransactionView {
        val family = familyByVendorTransactionId(transactionId)
        val lookupId = family?.activeVendorTxId ?: transactionId
        return vendor.transaction(lookupId)?.let { toView(it, family) }
            ?: throw ResourceNotFoundException("transaction", transactionId)
    }

    fun transactionByExternalTransactionId(externalTransactionId: String): TransactionView {
        val family = familyByExternalTransactionId(externalTransactionId)
        val result =
            if (family == null) {
                vendor.transactionByExternalTransactionId(externalTransactionId)
            } else {
                vendor.transaction(family.activeVendorTxId)
            }
        return result?.let { toView(it, family) }
            ?: throw ResourceNotFoundException("transaction", externalTransactionId)
    }

    fun transactions(
        accountId: String,
        query: TransactionPageQuery,
    ): TransactionPage {
        val account = accounts.requiredAccount(accountId)
        val conditions =
            if (query.cursor == null) {
                firstPageConditions(accountId, query)
            } else {
                TransactionCursorCodec.decode(query.cursor).also {
                    if (it.accountId != accountId) throw InvalidRequestException("cursor")
                }
            }
        val comparator = conditions.order.comparator()
        val visible = mutableListOf<PositionedTransactionView>()
        val scanned = mutableListOf<VendorTransaction>()
        val excludedTransactionIds = mutableSetOf<String>()
        val unmapped = mutableMapOf<String, MutableList<VendorTransaction>>()
        val mappingCache = mutableMapOf<String, VendorAssetMapping?>()
        val seenVendorCursors = mutableSetOf<String>()
        var vendorCursor: String? = null
        var lastScanned: VendorTransaction? = null
        var pagesScanned = 0

        do {
            val page =
                vendor.transactions(
                    vendorRequest(
                        sourceVaultId = account.requireVendorVaultId(),
                        conditions = conditions,
                        vendorCursor = vendorCursor,
                    ),
                )
            pagesScanned++
            val eligible =
                page.data
                    .asSequence()
                    .filter { conditions.isBeyondPosition(it) }
                    .sortedWith(comparator)
                    .toList()
            scanned += eligible
            eligible.lastOrNull()?.let { lastScanned = it }
            eligible.forEach { transaction ->
                val family = familyByVendorTransactionId(transaction.transactionId)
                if (family != null && family.activeVendorTxId != transaction.transactionId) {
                    excludedTransactionIds += transaction.transactionId
                    return@forEach
                }
                val mapping =
                    if (mappingCache.containsKey(transaction.vendorAssetId)) {
                        mappingCache[transaction.vendorAssetId]
                    } else {
                        mappings.findByVendorAssetId(transaction.vendorAssetId).also {
                            mappingCache[transaction.vendorAssetId] = it
                        }
                    }
                if (mapping == null) {
                    excludedTransactionIds += transaction.transactionId
                    unmapped.getOrPut(transaction.vendorAssetId) { mutableListOf() } += transaction
                } else {
                    val view = toView(transaction, mapping, family)
                    if (conditions.status == null || view.status == conditions.status) {
                        visible += PositionedTransactionView(transaction, view)
                    } else {
                        excludedTransactionIds += transaction.transactionId
                    }
                }
            }

            val next = page.next
            if (next != null && !seenVendorCursors.add(next)) {
                throw IllegalStateException("vendor returned a repeated transaction cursor")
            }
            vendorCursor = next
        } while (
            vendorCursor != null &&
            pagesScanned < MAX_VENDOR_PAGES_PER_REQUEST &&
            !enoughForStablePage(visible, lastScanned, conditions)
        )

        val orderedVisible = visible.sortedWith(compareBy(comparator) { it.transaction })
        val returned = orderedVisible.take(conditions.limit)
        val hasVisibleMore = orderedVisible.size > conditions.limit
        val position =
            if (hasVisibleMore) {
                returned.lastOrNull()?.transaction
            } else {
                lastScanned ?: returned.lastOrNull()?.transaction
            }
        val nextSeenBoundaryIds =
            conditions.nextSeenBoundaryIds(
                position = position,
                returned = returned,
                scanned = scanned,
                excludedTransactionIds = excludedTransactionIds,
                hasVisibleMore = hasVisibleMore,
            )
        unmapped.forEach { (vendorAssetId, transactions) ->
            val processedIds =
                transactions
                    .asSequence()
                    .filter { !hasVisibleMore || position == null || comparator.compare(it, position) <= 0 }
                    .map { it.transactionId }
                    .toList()
            if (processedIds.isNotEmpty()) {
                unmappedAssetAlertPort.alert(UnmappedVendorAssetAlert(accountId, vendorAssetId, processedIds))
            }
        }
        // 이어받을 위치는 벤더 커서가 아니라 마지막으로 반환(빈 응답이면 마지막으로 검사)한 거래다.
        // 그래서 마지막 페이지도 커서를 발급하고 asc 증분 폴링은 이후 생성분부터 다시 시작한다.
        return TransactionPage(
            data = returned.map { it.view },
            nextCursor =
                TransactionCursorCodec.encode(
                    conditions.copy(
                        positionEpochMillis =
                            position?.createdAtEpochMillis
                                ?: conditions.positionEpochMillis
                                ?: conditions.afterEpochMillis,
                        positionTransactionId = position?.transactionId ?: conditions.positionTransactionId,
                        seenBoundaryTransactionIds = nextSeenBoundaryIds,
                    ),
                ),
            hasMore = hasVisibleMore || vendorCursor != null,
        )
    }

    private fun vendorRequest(
        sourceVaultId: String,
        conditions: TransactionCursorConditions,
        vendorCursor: String?,
    ): VendorTransactionPageRequest {
        val position = conditions.positionEpochMillis
        return VendorTransactionPageRequest(
            sourceVaultId = sourceVaultId,
            afterEpochMillis =
                when (conditions.order) {
                    VendorTransactionOrder.ASC ->
                        position?.let { maxOf(conditions.afterEpochMillis ?: Long.MIN_VALUE, it.minusOneSafely()) }
                            ?: conditions.afterEpochMillis

                    VendorTransactionOrder.DESC -> conditions.afterEpochMillis
                },
            beforeEpochMillis =
                when (conditions.order) {
                    VendorTransactionOrder.ASC -> conditions.beforeEpochMillis
                    VendorTransactionOrder.DESC ->
                        position?.let {
                            minOf(conditions.beforeEpochMillis ?: Long.MAX_VALUE, it.plusOneSafely())
                        } ?: conditions.beforeEpochMillis
                },
            vendorStatus = null,
            order = conditions.order,
            limit = VENDOR_PAGE_LIMIT,
            cursor = vendorCursor,
        )
    }

    /**
     * limit+1 건을 찾았어도 마지막 건과 같은 createdAt 묶음이 다음 벤더 페이지에 걸칠 수 있다.
     * 시간 묶음을 끝까지 읽은 뒤 txId로 정렬해야 자체 위치 커서가 같은 시각 거래를 건너뛰지 않는다.
     */
    private fun enoughForStablePage(
        visible: List<PositionedTransactionView>,
        lastScanned: VendorTransaction?,
        conditions: TransactionCursorConditions,
    ): Boolean {
        if (visible.size <= conditions.limit || lastScanned == null) return false
        val cutoffTime =
            visible
                .sortedWith(compareBy(conditions.order.comparator()) { it.transaction })
                .getOrNull(conditions.limit - 1)
                ?.transaction
                ?.createdAtEpochMillis
                ?: return false
        return when (conditions.order) {
            VendorTransactionOrder.ASC -> lastScanned.createdAtEpochMillis > cutoffTime
            VendorTransactionOrder.DESC -> lastScanned.createdAtEpochMillis < cutoffTime
        }
    }

    private fun VendorTransactionOrder.comparator(): Comparator<VendorTransaction> =
        when (this) {
            VendorTransactionOrder.ASC -> ASC_ORDER
            VendorTransactionOrder.DESC -> ASC_ORDER.reversed()
        }

    private fun TransactionCursorConditions.isBeyondPosition(transaction: VendorTransaction): Boolean {
        val epoch = positionEpochMillis ?: return true
        if (transaction.createdAtEpochMillis != epoch) {
            return when (order) {
                VendorTransactionOrder.ASC -> transaction.createdAtEpochMillis > epoch
                VendorTransactionOrder.DESC -> transaction.createdAtEpochMillis < epoch
            }
        }
        return when (order) {
            VendorTransactionOrder.ASC -> transaction.transactionId !in seenBoundaryTransactionIds
            VendorTransactionOrder.DESC ->
                positionTransactionId?.let { transaction.transactionId < it } ?: false
        }
    }

    private fun TransactionCursorConditions.nextSeenBoundaryIds(
        position: VendorTransaction?,
        returned: List<PositionedTransactionView>,
        scanned: List<VendorTransaction>,
        excludedTransactionIds: Set<String>,
        hasVisibleMore: Boolean,
    ): Set<String> {
        if (order != VendorTransactionOrder.ASC) return emptySet()
        val boundaryEpoch = position?.createdAtEpochMillis ?: positionEpochMillis ?: afterEpochMillis ?: return emptySet()
        val result =
            if (positionEpochMillis == boundaryEpoch) {
                seenBoundaryTransactionIds.toMutableSet()
            } else {
                mutableSetOf()
            }
        val processed =
            if (hasVisibleMore) {
                val cutoff = checkNotNull(position) { "visible transaction page has no cursor position" }
                val comparator = order.comparator()
                returned.map { it.transaction } +
                    scanned.filter {
                        it.transactionId in excludedTransactionIds && comparator.compare(it, cutoff) <= 0
                    }
            } else {
                scanned
            }
        processed
            .asSequence()
            .filter { it.createdAtEpochMillis == boundaryEpoch }
            .mapTo(result) { it.transactionId }
        return result
    }

    private fun Long.minusOneSafely(): Long = if (this == Long.MIN_VALUE) this else this - 1

    private fun Long.plusOneSafely(): Long = if (this == Long.MAX_VALUE) this else this + 1

    private fun firstPageConditions(
        accountId: String,
        query: TransactionPageQuery,
    ): TransactionCursorConditions {
        val after = query.after ?: throw InvalidRequestException("after")
        return TransactionCursorConditions(
            accountId = accountId,
            status = query.status?.let { parseStatus(it) },
            limit = parseLimit(query.limit),
            afterEpochMillis = parseDateTime(after, "after"),
            beforeEpochMillis = query.before?.let { parseDateTime(it, "before") },
            order = parseOrder(query.order),
        )
    }

    private fun toView(
        transaction: VendorTransaction,
        family: TxRecord? = familyByVendorTransactionId(transaction.transactionId),
    ): TransactionView {
        val mapping =
            mappings.findByVendorAssetId(transaction.vendorAssetId)
                ?: throw AssetNotSupportedException("unknown", transaction.vendorAssetId)
        return toView(transaction, mapping, family)
    }

    private fun toView(
        transaction: VendorTransaction,
        mapping: VendorAssetMapping,
        family: TxRecord? = null,
    ): TransactionView =
        TransactionView(
            transactionId = family?.vendorTxId ?: transaction.transactionId,
            transactionHash = transaction.transactionHash,
            externalTransactionId = family?.externalTxId ?: transaction.externalTransactionId,
            network = mapping.network,
            symbol = mapping.symbol,
            amount = transaction.amount,
            sourceAddress = transaction.sourceAddress,
            destinationAddress = transaction.destinationAddress,
            status =
                statusTranslator.translate(
                    VendorStatusObservation(
                        rawStatus = transaction.rawStatus,
                        subStatus = transaction.subStatus,
                        confirmationCount = transaction.confirmationCount,
                    ),
                    mapping.network,
                ),
            confirmationCount = transaction.confirmationCount,
            createdAt = Instant.ofEpochMilli(transaction.createdAtEpochMillis).toString(),
            lastUpdated = Instant.ofEpochMilli(transaction.lastUpdatedEpochMillis).toString(),
        )

    private fun familyByVendorTransactionId(vendorTransactionId: String): TxRecord? =
        transactions.findByVendorTxId(vendorTransactionId)
            ?: transactions.findByActiveVendorTxId(vendorTransactionId)
            ?: boosts
                .findByNewVendorTransactionId(vendorTransactionId)
                ?.let { transactions.findByVendorTxId(it.rootVendorTransactionId) }

    private fun familyByExternalTransactionId(externalTransactionId: String): TxRecord? =
        transactions.findByExternalTxId(externalTransactionId)
            ?: boosts
                .findByExternalTransactionId(externalTransactionId)
                ?.let { transactions.findByVendorTxId(it.rootVendorTransactionId) }

    private fun parseDateTime(
        value: String,
        field: String,
    ): Long =
        try {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (_: DateTimeException) {
            throw InvalidRequestException(field)
        }

    private fun parseLimit(value: String?): Int {
        val limit = value?.toIntOrNull() ?: if (value == null) DEFAULT_LIMIT else throw InvalidRequestException("limit")
        if (limit !in 1..MAX_LIMIT) throw InvalidRequestException("limit")
        return limit
    }

    private fun parseOrder(value: String?): VendorTransactionOrder =
        when (value ?: "desc") {
            "asc" -> VendorTransactionOrder.ASC
            "desc" -> VendorTransactionOrder.DESC
            else -> throw InvalidRequestException("order")
        }

    private fun parseStatus(value: String): TxStatus =
        try {
            TxStatus.valueOf(value)
        } catch (_: IllegalArgumentException) {
            throw InvalidRequestException("status")
        }

    private companion object {
        const val DEFAULT_LIMIT = 200
        const val MAX_LIMIT = 500
        const val VENDOR_PAGE_LIMIT = 500
        const val MAX_VENDOR_PAGES_PER_REQUEST = 5

        /** 같은 createdAt 이 겹칠 때 위치가 흔들리지 않도록 txId 로 마저 가른다. */
        val ASC_ORDER: Comparator<VendorTransaction> =
            compareBy<VendorTransaction> { it.createdAtEpochMillis }.thenBy { it.transactionId }
    }
}

private data class PositionedTransactionView(
    val transaction: VendorTransaction,
    val view: TransactionView,
)

data class TransactionPageQuery(
    val after: String? = null,
    val before: String? = null,
    val order: String? = null,
    val status: String? = null,
    val limit: String? = null,
    val cursor: String? = null,
)

data class TransactionView(
    val transactionId: String,
    val transactionHash: String?,
    val externalTransactionId: String?,
    val network: String,
    val symbol: String,
    val amount: String,
    val sourceAddress: String?,
    val destinationAddress: String?,
    val status: TxStatus,
    val confirmationCount: Int,
    val createdAt: String,
    val lastUpdated: String,
)

data class TransactionPage(
    val data: List<TransactionView>,
    val nextCursor: String,
    val hasMore: Boolean,
)

/**
 * 커서 토큰이 담는 전부 — 최초 요청의 필터·정렬과 이어받을 위치다. 벤더 커서를 감싸지 않는다.
 * 그래서 벤더가 다음 페이지 커서를 주지 않아도 우리가 커서를 계속 발급할 수 있다 (PLAN #34).
 */
private data class TransactionCursorConditions(
    val accountId: String,
    val status: TxStatus?,
    val limit: Int,
    val afterEpochMillis: Long? = null,
    val beforeEpochMillis: Long? = null,
    val order: VendorTransactionOrder = VendorTransactionOrder.DESC,
    val positionEpochMillis: Long? = null,
    val positionTransactionId: String? = null,
    val seenBoundaryTransactionIds: Set<String> = emptySet(),
)

private object TransactionCursorCodec {
    private const val VERSION = "v3"
    private const val PART_COUNT = 9
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    fun encode(conditions: TransactionCursorConditions): String =
        listOf(
            VERSION,
            conditions.accountId,
            conditions.status?.name.orEmpty(),
            conditions.limit.toString(),
            conditions.order.name,
            conditions.afterEpochMillis?.toString().orEmpty(),
            conditions.beforeEpochMillis?.toString().orEmpty(),
            listOfNotNull(
                conditions.positionEpochMillis?.toString(),
                conditions.positionTransactionId,
            ).joinToString(":"),
            conditions.seenBoundaryTransactionIds.sorted().joinToString(",", transform = ::encodePart),
        ).joinToString(".") { encodePart(it) }

    fun decode(cursor: String): TransactionCursorConditions =
        try {
            val parts = cursor.split('.').map(::decodePart)
            if (parts.size != PART_COUNT || parts[0] != VERSION) throw InvalidRequestException("cursor")
            val limit = parts[3].toIntOrNull() ?: throw InvalidRequestException("cursor")
            if (limit !in 1..500) throw InvalidRequestException("cursor")
            val position = parts[7].split(':', limit = 2)
            val after = parts[5].takeIf(String::isNotBlank)?.toLong() ?: throw InvalidRequestException("cursor")
            val before = parts[6].takeIf(String::isNotBlank)?.toLong()
            val positionEpoch = position.getOrNull(0)?.takeIf(String::isNotBlank)?.toLong()
            if (before != null && before < after) throw InvalidRequestException("cursor")
            if (positionEpoch != null && (positionEpoch < after || (before != null && positionEpoch > before))) {
                throw InvalidRequestException("cursor")
            }
            TransactionCursorConditions(
                accountId = parts[1],
                status = parts[2].takeIf(String::isNotBlank)?.let(TxStatus::valueOf),
                limit = limit,
                order = VendorTransactionOrder.valueOf(parts[4]),
                afterEpochMillis = after,
                beforeEpochMillis = before,
                positionEpochMillis = positionEpoch,
                positionTransactionId = position.getOrNull(1)?.takeIf(String::isNotBlank),
                seenBoundaryTransactionIds =
                    parts[8]
                        .takeIf(String::isNotBlank)
                        ?.split(',')
                        ?.mapTo(mutableSetOf(), ::decodePart)
                        ?: emptySet(),
            )
        } catch (exception: InvalidRequestException) {
            throw exception
        } catch (_: IllegalArgumentException) {
            throw InvalidRequestException("cursor")
        }

    private fun encodePart(value: String): String = encoder.encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun decodePart(value: String): String = String(decoder.decode(value), StandardCharsets.UTF_8)
}
