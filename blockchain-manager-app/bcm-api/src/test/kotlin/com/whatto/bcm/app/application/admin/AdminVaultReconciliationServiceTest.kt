package com.whatto.bcm.app.application.admin

import com.whatto.bcm.domain.admin.VaultReconciliation
import com.whatto.bcm.domain.admin.VaultReconciliationPage
import com.whatto.bcm.domain.admin.VaultReconciliationRepository
import com.whatto.bcm.domain.admin.VaultReconciliationStatus
import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.vendor.VendorPage
import com.whatto.bcm.domain.vendor.VendorVault
import com.whatto.bcm.domain.vendor.WalletVendorPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.core.task.SyncTaskExecutor
import org.springframework.core.task.TaskExecutor
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class AdminVaultReconciliationServiceTest {
    private val repository = RecordingVaultReconciliationRepository()
    private val wallet = mockk<WalletVendorPort>()
    private val service =
        AdminVaultReconciliationService(
            repository = repository,
            walletVendor = wallet,
            taskExecutor = SyncTaskExecutor(),
            clock = Clock.fixed(Instant.parse("2026-08-31T02:00:00Z"), ZoneOffset.UTC),
            properties = VaultReconciliationProperties(),
        )

    @Test
    fun `실행을 먼저 기록하고 Fireblocks page를 순차 반영한 뒤 완료한다`() {
        every { wallet.vaults(null) } returns VendorPage(listOf(VendorVault("1", "customer-a", 2)), "next")
        every { wallet.vaults("next") } returns VendorPage(listOf(VendorVault("2", "orphan", 1)), null)

        val accepted = service.start(" customer ")

        assertThat(repository.created?.query).isEqualTo("customer")
        assertThat(repository.recordedPages.map { it.cursor }).containsExactly(null, "next")
        assertThat(repository.recordedPages.map { it.nextCursor }).containsExactly("next", null)
        assertThat(repository.completed).isEqualTo(accepted.runId)
        assertThat(repository.failed).isNull()
    }

    @Test
    fun `vendor cursor가 반복되면 확인한 page를 남기고 PARTIAL로 종결한다`() {
        every { wallet.vaults(null) } returns VendorPage(listOf(VendorVault("1", "customer-a", 2)), "same")
        every { wallet.vaults("same") } returns VendorPage(listOf(VendorVault("2", "customer-b", 1)), "same")

        val accepted = service.start(null)

        assertThat(repository.completed).isNull()
        assertThat(repository.failed).isEqualTo(accepted.runId to "VENDOR_CURSOR_REPEATED")
        assertThat(repository.recordedPages).hasSize(2)
    }

    @Test
    fun `결과 조회는 opaque cursor를 해석하고 최대 100건으로 제한한다`() {
        repository.page =
            VaultReconciliationPage(
                run = run(status = VaultReconciliationStatus.COMPLETED),
                items = emptyList(),
                nextSequence = 151,
            )

        val result = service.result("run-1", AdminVaultCursor.encode(50), 100)

        assertThat(repository.requestedPage).isEqualTo(Triple("run-1", 50L, 100))
        assertThat(result.nextCursor).isEqualTo(AdminVaultCursor.encode(151))
        assertThatThrownBy { service.result("run-1", null, 101) }
            .isInstanceOf(com.whatto.bcm.domain.exception.InvalidRequestException::class.java)
    }

    @Test
    fun `마지막 page 기록 뒤 재기동하면 처음부터 다시 읽지 않고 완료만 확정한다`() {
        repository.page =
            VaultReconciliationPage(
                run = run(status = VaultReconciliationStatus.RUNNING).copy(vendorPaginationDone = true),
                items = emptyList(),
                nextSequence = null,
            )

        service.enqueue("run-1")

        verify(exactly = 0) { wallet.vaults(any()) }
        assertThat(repository.completed).isEqualTo("run-1")
    }

    @Test
    fun `같은 실행을 중복 enqueue해도 claim을 얻은 worker만 vendor를 호출한다`() {
        every { wallet.vaults(null) } returns VendorPage(emptyList(), null)

        val accepted = service.start(null)
        service.enqueue(accepted.runId)

        verify(exactly = 1) { wallet.vaults(null) }
    }

    @Test
    fun `첫 vendor page 실패는 안전 코드로 종결한다`() {
        every { wallet.vaults(null) } throws VendorApiException("vaults", 503)

        val accepted = service.start(null)

        assertThat(repository.failed).isEqualTo(accepted.runId to "VENDOR_UNAVAILABLE")
    }

    @Test
    fun `executor가 접수를 거부하면 ACCEPTED 실행을 실패로 종결한다`() {
        val rejectingService =
            AdminVaultReconciliationService(
                repository = repository,
                walletVendor = wallet,
                taskExecutor = TaskExecutor { throw IllegalStateException("rejected") },
                clock = Clock.fixed(Instant.parse("2026-08-31T02:00:00Z"), ZoneOffset.UTC),
                properties = VaultReconciliationProperties(),
            )

        assertThatThrownBy { rejectingService.start(null) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(repository.failedAccepted?.second).isEqualTo("EXECUTOR_UNAVAILABLE")
    }

    private fun run(status: VaultReconciliationStatus) =
        VaultReconciliation(
            runId = "run-1",
            query = null,
            status = status,
            vendorPageCount = 0,
            vendorVaultCount = 0,
            resultCount = 0,
            failureCode = null,
            requestedAt = "20260831020000",
            startedAt = null,
            finishedAt = null,
        )
}

private class RecordingVaultReconciliationRepository : VaultReconciliationRepository {
    var created: VaultReconciliation? = null
    val recordedPages = mutableListOf<RecordedPage>()
    var completed: String? = null
    var failed: Pair<String, String>? = null
    var failedAccepted: Pair<String, String>? = null
    var page: VaultReconciliationPage? = null
    var requestedPage: Triple<String, Long?, Int>? = null
    private var claimOwner: String? = null
    private var active = true

    override fun create(run: VaultReconciliation): VaultReconciliation = run.also { created = it }

    override fun claim(
        runId: String,
        claimId: String,
        claimedAt: String,
        expiresAt: String,
    ): Boolean =
        if (active && claimOwner == null) {
            claimOwner = claimId
            true
        } else {
            false
        }

    override fun renewClaim(
        runId: String,
        claimId: String,
        renewedAt: String,
        expiresAt: String,
    ): Boolean = claimOwner == claimId

    override fun startWithAccountSnapshot(
        runId: String,
        claimId: String,
        startedAt: String,
    ): Boolean = true

    override fun hasDuplicateVendorVaultMapping(runId: String): Boolean = false

    override fun recordVendorPage(
        runId: String,
        claimId: String,
        cursor: String?,
        nextCursor: String?,
        vaults: List<VendorVault>,
        recordedAt: String,
    ): Boolean {
        recordedPages += RecordedPage(cursor, nextCursor, vaults)
        return true
    }

    override fun complete(
        runId: String,
        claimId: String,
        finishedAt: String,
    ): Boolean {
        completed = runId
        claimOwner = null
        active = false
        return true
    }

    override fun fail(
        runId: String,
        claimId: String,
        failureCode: String,
        finishedAt: String,
    ): Boolean {
        failed = runId to failureCode
        claimOwner = null
        active = false
        return true
    }

    override fun failAccepted(
        runId: String,
        failureCode: String,
        finishedAt: String,
    ) {
        failedAccepted = runId to failureCode
        active = false
    }

    override fun find(runId: String): VaultReconciliation? = page?.run ?: created

    override fun findPage(
        runId: String,
        afterSequence: Long?,
        limit: Int,
    ): VaultReconciliationPage? {
        requestedPage = Triple(runId, afterSequence, limit)
        return page
    }
}

private data class RecordedPage(
    val cursor: String?,
    val nextCursor: String?,
    val vaults: List<VendorVault>,
)
