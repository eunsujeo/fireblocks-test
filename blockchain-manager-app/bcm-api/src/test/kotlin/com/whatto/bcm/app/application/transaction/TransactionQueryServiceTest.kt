package com.whatto.bcm.app.application.transaction

import com.whatto.bcm.app.application.account.AccountQueryService
import com.whatto.bcm.app.application.account.fixture.AccountFixture
import com.whatto.bcm.app.application.asset.VendorAssetMappingQueryService
import com.whatto.bcm.app.application.transaction.fixture.VendorTransactionFixture.fixture
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.exception.InvalidRequestException
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import com.whatto.bcm.domain.tx.BoostAttemptRepository
import com.whatto.bcm.domain.tx.FinalityPolicy
import com.whatto.bcm.domain.tx.TxRecord
import com.whatto.bcm.domain.tx.TxRecordRepository
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.domain.vendor.UnmappedVendorAssetAlert
import com.whatto.bcm.domain.vendor.UnmappedVendorAssetAlertPort
import com.whatto.bcm.domain.vendor.VendorPage
import com.whatto.bcm.domain.vendor.VendorTransactionOrder
import com.whatto.bcm.domain.vendor.VendorTransactionPageRequest
import com.whatto.bcm.domain.vendor.VendorTransactionPort
import com.whatto.bcm.infra.client.fireblocks.FireblocksStatusTranslator
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TransactionQueryServiceTest {
    @MockK
    lateinit var vendor: VendorTransactionPort

    @MockK
    lateinit var accounts: AccountQueryService

    @MockK
    lateinit var mappings: VendorAssetMappingQueryService

    @MockK
    lateinit var transactions: TxRecordRepository

    @MockK
    lateinit var boosts: BoostAttemptRepository

    private val unmappedAlerts = mutableListOf<UnmappedVendorAssetAlert>()
    private val unmappedAssetAlerts = UnmappedVendorAssetAlertPort { unmappedAlerts += it }

    private lateinit var service: TransactionQueryService

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)
        service =
            TransactionQueryService(
                vendor,
                accounts,
                mappings,
                FireblocksStatusTranslator(FinalityPolicy { 3 }),
                unmappedAssetAlerts,
                transactions,
                boosts,
            )
        every { mappings.findByVendorAssetId("asset-uuid") } returns MAPPING
        every { transactions.findByVendorTxId(any()) } returns null
        every { transactions.findByActiveVendorTxId(any()) } returns null
        every { transactions.findByExternalTxId(any()) } returns null
        every { boosts.findByNewVendorTransactionId(any()) } returns null
        every { boosts.findByExternalTransactionId(any()) } returns null
        every { boosts.findLatestSubmittedByRoot(any()) } returns null
    }

    @Test
    fun `txId 단건 조회는 자산과 공통 상태를 번역하고 epoch 밀리초를 UTC ISO 시각으로 바꾼다`() {
        every { vendor.transaction("tx-91c") } returns fixture()

        val result = service.transaction("tx-91c")

        assertThat(result.transactionId).isEqualTo("tx-91c")
        assertThat(result.externalTransactionId).isEqualTo("wd-260713-0042")
        assertThat(result.network).isEqualTo("ETHEREUM")
        assertThat(result.symbol).isEqualTo("USDC")
        assertThat(result.status).isEqualTo(TxStatus.FINALIZED)
        assertThat(result.amount).isEqualTo("1.50")
        assertThat(result.sourceAddress).isEqualTo("0xFrom")
        assertThat(result.destinationAddress).isEqualTo("0x9fE2")
        assertThat(result.createdAt).isEqualTo("2026-08-07T02:05:06.789Z")
        assertThat(result.lastUpdated).isEqualTo("2026-08-07T02:06:10.120Z")
    }

    @Test
    fun `root txId 단건 조회는 active 대체 거래를 읽고 최초 식별자를 유지한다`() {
        every { transactions.findByVendorTxId("tx-root") } returns rootRecord()
        every { vendor.transaction("tx-replacement") } returns
            fixture(transactionId = "tx-replacement", externalTransactionId = "bst-replacement")

        val result = service.transaction("tx-root")

        assertThat(result.transactionId).isEqualTo("tx-root")
        assertThat(result.externalTransactionId).isEqualTo("wd-root")
        verify(exactly = 1) { vendor.transaction("tx-replacement") }
    }

    @Test
    fun `최초 externalTxId 단건 조회도 active 대체 거래를 읽고 root 식별자를 유지한다`() {
        every { transactions.findByExternalTxId("wd-root") } returns rootRecord()
        every { vendor.transaction("tx-replacement") } returns
            fixture(transactionId = "tx-replacement", externalTransactionId = "bst-replacement")

        val result = service.transactionByExternalTransactionId("wd-root")

        assertThat(result.transactionId).isEqualTo("tx-root")
        assertThat(result.externalTransactionId).isEqualTo("wd-root")
        verify(exactly = 1) { vendor.transaction("tx-replacement") }
        verify(exactly = 0) { vendor.transactionByExternalTransactionId(any()) }
    }

    @Test
    fun `거래 목록은 비활성 원 거래를 제외하고 active 대체 거래를 root 식별자로 접는다`() {
        every { accounts.requiredAccount(ACCOUNT_ID) } returns AccountFixture.fixture(ACCOUNT_ID, vendorVaultId = "71")
        every { transactions.findByVendorTxId("tx-root") } returns rootRecord()
        every { transactions.findByActiveVendorTxId("tx-replacement") } returns rootRecord()
        every { vendor.transactions(any()) } returns
            VendorPage(
                listOf(
                    fixture(transactionId = "tx-root", externalTransactionId = "wd-root"),
                    fixture(transactionId = "tx-replacement", externalTransactionId = "bst-replacement"),
                ),
                null,
            )

        val result = service.transactions(ACCOUNT_ID, TransactionPageQuery(after = AFTER))

        assertThat(result.data).hasSize(1)
        assertThat(result.data.single().transactionId).isEqualTo("tx-root")
        assertThat(result.data.single().externalTransactionId).isEqualTo("wd-root")
    }

    @Test
    fun `externalTxId 단건이 벤더에 없으면 NOT_FOUND다`() {
        every { vendor.transactionByExternalTransactionId("wd-none") } returns null

        assertThatThrownBy { service.transactionByExternalTransactionId("wd-none") }
            .isInstanceOf(ResourceNotFoundException::class.java)
    }

    @Test
    fun `벤더의 최초 SUBMITTED 상태도 공통 SUBMITTED로 조회한다`() {
        every { vendor.transaction("tx-new") } returns
            fixture(
                transactionId = "tx-new",
                rawStatus = "SUBMITTED",
                subStatus = null,
                confirmationCount = 0,
                sourceAddress = null,
                destinationAddress = null,
            )

        val result = service.transaction("tx-new")

        assertThat(result.status).isEqualTo(TxStatus.SUBMITTED)
        assertThat(result.sourceAddress).isNull()
        assertThat(result.destinationAddress).isNull()
    }

    @Test
    fun `커서 없는 첫 목록 요청은 after가 필수이고 형식과 limit을 검증한다`() {
        every { accounts.requiredAccount(ACCOUNT_ID) } returns AccountFixture.fixture(ACCOUNT_ID, vendorVaultId = "71")

        assertThatThrownBy { service.transactions(ACCOUNT_ID, TransactionPageQuery(after = null)) }
            .isInstanceOf(InvalidRequestException::class.java)
        assertThatThrownBy { service.transactions(ACCOUNT_ID, TransactionPageQuery(after = "yesterday")) }
            .isInstanceOf(InvalidRequestException::class.java)
        assertThatThrownBy {
            service.transactions(ACCOUNT_ID, TransactionPageQuery(after = AFTER, limit = "501"))
        }.isInstanceOf(InvalidRequestException::class.java)
        verify(exactly = 0) { vendor.transactions(any()) }
    }

    @Test
    fun `첫 목록은 vault와 기간 정렬을 넘기고 다음 벤더 페이지까지 훑어 상태를 필터한다`() {
        every { accounts.requiredAccount(ACCOUNT_ID) } returns AccountFixture.fixture(ACCOUNT_ID, vendorVaultId = "71")
        val requests = mutableListOf<VendorTransactionPageRequest>()
        every { vendor.transactions(capture(requests)) } answers {
            if (firstArg<VendorTransactionPageRequest>().cursor == null) {
                VendorPage(
                    listOf(
                        fixture(transactionId = "tx-final"),
                        fixture(
                            transactionId = "tx-confirming",
                            rawStatus = "CONFIRMING",
                            subStatus = "PENDING_BLOCKCHAIN_CONFIRMATIONS",
                            confirmationCount = 1,
                        ),
                    ),
                    "vendor-cursor-2",
                )
            } else {
                VendorPage(emptyList(), null)
            }
        }

        val page =
            service.transactions(
                ACCOUNT_ID,
                TransactionPageQuery(
                    after = AFTER,
                    before = BEFORE,
                    order = "asc",
                    status = "FINALIZED",
                    limit = "2",
                ),
            )

        assertThat(requests.first().sourceVaultId).isEqualTo("71")
        assertThat(requests.first().afterEpochMillis).isEqualTo(1_786_072_800_000)
        assertThat(requests.first().beforeEpochMillis).isEqualTo(1_786_159_200_000)
        assertThat(requests.first().order).isEqualTo(VendorTransactionOrder.ASC)
        assertThat(requests.first().limit).isEqualTo(500)
        assertThat(requests.first().vendorStatus).isNull()
        assertThat(requests[1].cursor).isEqualTo("vendor-cursor-2")
        assertThat(requests[1].afterEpochMillis).isEqualTo(1_786_072_800_000)
        assertThat(requests[1].beforeEpochMillis).isEqualTo(1_786_159_200_000)
        assertThat(requests[1].order).isEqualTo(VendorTransactionOrder.ASC)
        assertThat(requests[1].limit).isEqualTo(500)
        assertThat(page.data.map { it.transactionId }).containsExactly("tx-final")
        assertThat(page.nextCursor).isNotEqualTo("vendor-cursor-2")
        assertThat(page.hasMore).isFalse()
    }

    @Test
    fun `커서 요청은 함께 온 조건을 무시하고 매니저 위치에서 벤더 조회를 다시 시작한다`() {
        every { accounts.requiredAccount(ACCOUNT_ID) } returns AccountFixture.fixture(ACCOUNT_ID, vendorVaultId = "71")
        every { vendor.transactions(any()) } returns
            VendorPage(listOf(fixture(transactionId = "tx-first", createdAtEpochMillis = PAGE_TIME)), null)
        val first =
            service.transactions(
                ACCOUNT_ID,
                TransactionPageQuery(after = AFTER, status = "FINALIZED", limit = "1"),
            )

        val cursorRequest = slot<VendorTransactionPageRequest>()
        every { vendor.transactions(capture(cursorRequest)) } returns
            VendorPage(
                listOf(
                    fixture(transactionId = "tx-first", createdAtEpochMillis = PAGE_TIME),
                    fixture(
                        transactionId = "tx-next",
                        createdAtEpochMillis = PAGE_TIME - 1,
                    ),
                ),
                null,
            )

        val next =
            service.transactions(
                ACCOUNT_ID,
                TransactionPageQuery(
                    cursor = first.nextCursor,
                    after = "ignored-invalid-date",
                    before = "ignored-invalid-date",
                    order = "sideways",
                    status = "FAILED",
                    limit = "not-a-number",
                ),
            )

        assertThat(cursorRequest.captured.cursor).isNull()
        assertThat(cursorRequest.captured.afterEpochMillis).isEqualTo(1_786_072_800_000)
        assertThat(cursorRequest.captured.beforeEpochMillis).isEqualTo(PAGE_TIME + 1)
        assertThat(next.data.map { it.transactionId }).containsExactly("tx-next")
    }

    @Test
    fun `마지막 페이지도 nextCursor를 보존하고 hasMore는 false다`() {
        every { accounts.requiredAccount(ACCOUNT_ID) } returns AccountFixture.fixture(ACCOUNT_ID, vendorVaultId = "71")
        every { vendor.transactions(any()) } returns VendorPage(listOf(fixture()), null)

        val page =
            service.transactions(
                ACCOUNT_ID,
                TransactionPageQuery(after = AFTER, order = "asc", limit = "2"),
            )

        assertThat(page.nextCursor).isNotBlank()
        assertThat(page.hasMore).isFalse()
        assertThat(page.data).hasSize(1)
    }

    @Test
    fun `미등록 자산 거래를 제외하고 벤더 페이지 끝까지 확인한다`() {
        every { accounts.requiredAccount(ACCOUNT_ID) } returns AccountFixture.fixture(ACCOUNT_ID, vendorVaultId = "71")
        every { mappings.findByVendorAssetId("unmapped-asset") } returns null
        every { vendor.transactions(any()) } answers {
            if (firstArg<VendorTransactionPageRequest>().cursor == null) {
                VendorPage(
                    listOf(
                        fixture(transactionId = "tx-unmapped", vendorAssetId = "unmapped-asset"),
                        fixture(transactionId = "tx-supported"),
                    ),
                    "vendor-cursor-2",
                )
            } else {
                VendorPage(emptyList(), null)
            }
        }

        val page = service.transactions(ACCOUNT_ID, TransactionPageQuery(after = AFTER, limit = "2"))

        assertThat(page.data.map { it.transactionId }).containsExactly("tx-supported")
        assertThat(page.hasMore).isFalse()
        assertThat(page.nextCursor).isNotBlank()
        assertThat(unmappedAlerts).containsExactly(
            UnmappedVendorAssetAlert(ACCOUNT_ID, "unmapped-asset", listOf("tx-unmapped")),
        )
    }

    @Test
    fun `같은 createdAt 거래가 벤더 페이지에 나뉘어도 매니저 커서는 빠짐없이 이어간다`() {
        every { accounts.requiredAccount(ACCOUNT_ID) } returns AccountFixture.fixture(ACCOUNT_ID, vendorVaultId = "71")
        every { vendor.transactions(any()) } answers {
            when (firstArg<VendorTransactionPageRequest>().cursor) {
                null ->
                    VendorPage(
                        listOf(
                            fixture(transactionId = "tx-b", createdAtEpochMillis = PAGE_TIME),
                            fixture(transactionId = "tx-d", createdAtEpochMillis = PAGE_TIME),
                        ),
                        "vendor-cursor-2",
                    )

                "vendor-cursor-2" ->
                    VendorPage(
                        listOf(
                            fixture(transactionId = "tx-a", createdAtEpochMillis = PAGE_TIME),
                            fixture(transactionId = "tx-c", createdAtEpochMillis = PAGE_TIME),
                        ),
                        null,
                    )

                else -> error("unexpected vendor cursor")
            }
        }

        val first =
            service.transactions(ACCOUNT_ID, TransactionPageQuery(after = AFTER, order = "asc", limit = "2"))
        val second = service.transactions(ACCOUNT_ID, TransactionPageQuery(cursor = first.nextCursor))

        assertThat(first.data.map { it.transactionId }).containsExactly("tx-a", "tx-b")
        assertThat(first.hasMore).isTrue()
        assertThat(second.data.map { it.transactionId }).containsExactly("tx-c", "tx-d")
        assertThat(second.hasMore).isFalse()
    }

    @Test
    fun `asc 마지막 커서는 새 거래만 이어받는 증분 폴링 위치다`() {
        every { accounts.requiredAccount(ACCOUNT_ID) } returns AccountFixture.fixture(ACCOUNT_ID, vendorVaultId = "71")
        every { vendor.transactions(any()) } returns
            VendorPage(listOf(fixture(transactionId = "tx-old", createdAtEpochMillis = PAGE_TIME)), null)
        val first =
            service.transactions(ACCOUNT_ID, TransactionPageQuery(after = AFTER, order = "asc", limit = "2"))

        every { vendor.transactions(any()) } returns
            VendorPage(
                listOf(
                    fixture(transactionId = "tx-old", createdAtEpochMillis = PAGE_TIME),
                    fixture(transactionId = "tx-new", createdAtEpochMillis = PAGE_TIME + 1),
                ),
                null,
            )

        val next = service.transactions(ACCOUNT_ID, TransactionPageQuery(cursor = first.nextCursor))

        assertThat(first.hasMore).isFalse()
        assertThat(next.data.map { it.transactionId }).containsExactly("tx-new")
        assertThat(next.hasMore).isFalse()
    }

    @Test
    fun `asc 증분 커서는 같은 밀리초에 늦게 나타난 더 작은 txId도 놓치지 않는다`() {
        every { accounts.requiredAccount(ACCOUNT_ID) } returns AccountFixture.fixture(ACCOUNT_ID, vendorVaultId = "71")
        every { vendor.transactions(any()) } returns
            VendorPage(listOf(fixture(transactionId = "tx-z", createdAtEpochMillis = PAGE_TIME)), null)
        val first =
            service.transactions(ACCOUNT_ID, TransactionPageQuery(after = AFTER, order = "asc", limit = "2"))

        every { vendor.transactions(any()) } returns
            VendorPage(
                listOf(
                    fixture(transactionId = "tx-a", createdAtEpochMillis = PAGE_TIME),
                    fixture(transactionId = "tx-z", createdAtEpochMillis = PAGE_TIME),
                ),
                null,
            )

        val next = service.transactions(ACCOUNT_ID, TransactionPageQuery(cursor = first.nextCursor))

        assertThat(next.data.map { it.transactionId }).containsExactly("tx-a")
    }

    @Test
    fun `한 요청이 벤더 페이지를 무한히 훑지 않고 다음 매니저 커서로 진행한다`() {
        every { accounts.requiredAccount(ACCOUNT_ID) } returns AccountFixture.fixture(ACCOUNT_ID, vendorVaultId = "71")
        var callCount = 0
        every { vendor.transactions(any()) } answers {
            callCount++
            VendorPage(
                listOf(
                    fixture(
                        transactionId = "tx-confirming-$callCount",
                        createdAtEpochMillis = PAGE_TIME + callCount,
                        rawStatus = "CONFIRMING",
                        subStatus = "PENDING_BLOCKCHAIN_CONFIRMATIONS",
                        confirmationCount = 1,
                    ),
                ),
                "vendor-cursor-${callCount + 1}",
            )
        }

        val page =
            service.transactions(
                ACCOUNT_ID,
                TransactionPageQuery(after = AFTER, order = "asc", status = "FINALIZED", limit = "2"),
            )

        assertThat(callCount).isEqualTo(5)
        assertThat(page.data).isEmpty()
        assertThat(page.hasMore).isTrue()
        assertThat(page.nextCursor).isNotBlank()
    }

    @Test
    fun `아직 반환 경계를 지나지 않은 미등록 자산은 다음 커서에서 한 번만 경보한다`() {
        every { accounts.requiredAccount(ACCOUNT_ID) } returns AccountFixture.fixture(ACCOUNT_ID, vendorVaultId = "71")
        every { mappings.findByVendorAssetId("unmapped-asset") } returns null
        every { vendor.transactions(any()) } returns
            VendorPage(
                listOf(
                    fixture(transactionId = "tx-a", createdAtEpochMillis = PAGE_TIME),
                    fixture(transactionId = "tx-b", createdAtEpochMillis = PAGE_TIME),
                    fixture(
                        transactionId = "tx-c-unmapped",
                        vendorAssetId = "unmapped-asset",
                        createdAtEpochMillis = PAGE_TIME,
                    ),
                ),
                null,
            )

        val first =
            service.transactions(ACCOUNT_ID, TransactionPageQuery(after = AFTER, order = "asc", limit = "1"))
        assertThat(unmappedAlerts).isEmpty()

        val second = service.transactions(ACCOUNT_ID, TransactionPageQuery(cursor = first.nextCursor))
        assertThat(unmappedAlerts).containsExactly(
            UnmappedVendorAssetAlert(ACCOUNT_ID, "unmapped-asset", listOf("tx-c-unmapped")),
        )

        service.transactions(ACCOUNT_ID, TransactionPageQuery(cursor = second.nextCursor))
        assertThat(unmappedAlerts).hasSize(1)
    }

    private fun rootRecord() =
        TxRecord(
            vendorTxId = "tx-root",
            activeVendorTxId = "tx-replacement",
            externalTxId = "wd-root",
            accountId = ACCOUNT_ID,
            network = "ETHEREUM",
            symbol = "USDC",
            transactionHash = "0xabc",
            lastPublishedStatus = TxStatus.CONFIRMED,
            confirmationCount = 0,
            firstDetectedAt = "20260807110000",
            lastChangedAt = "20260807120000",
        )

    companion object {
        private const val ACCOUNT_ID = "acct_pool_02"
        private const val AFTER = "2026-08-07T03:20:00Z"
        private const val BEFORE = "2026-08-08T03:20:00Z"
        private const val PAGE_TIME = 1_786_073_000_000L
        private val MAPPING =
            VendorAssetMapping(
                network = "ETHEREUM",
                symbol = "USDC",
                vendorAssetId = "asset-uuid",
                contractAddress = "0xA0b8",
                registeredAt = "20260807120000",
                registeredByEmployeeNo = "E001",
                registeredByBranchCode = "0001",
            )
    }
}
