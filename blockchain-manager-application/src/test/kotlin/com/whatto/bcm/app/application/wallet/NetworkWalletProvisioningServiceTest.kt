package com.whatto.bcm.app.application.wallet

import com.whatto.bcm.app.application.account.AccountQueryService
import com.whatto.bcm.domain.account.AccountModel
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.vendor.NetworkWalletProvisioningPort
import com.whatto.bcm.domain.vendor.VendorPage
import com.whatto.bcm.domain.wallet.NetworkWalletCreationIntent
import com.whatto.bcm.domain.wallet.NetworkWalletCreationStatus
import com.whatto.bcm.domain.wallet.NetworkWalletEvidenceOperation
import com.whatto.bcm.domain.wallet.NetworkWalletEvidenceStore
import com.whatto.bcm.domain.wallet.NetworkWalletProvisioningRepository
import com.whatto.bcm.domain.wallet.NetworkWalletRecoveryPage
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import com.whatto.bcm.app.application.wallet.fixture.WalletProvisioningFixture as F

class NetworkWalletProvisioningServiceTest {
    private val repository = mockk<NetworkWalletProvisioningRepository>()
    private val vendor = mockk<NetworkWalletProvisioningPort>()
    private val evidence = mockk<NetworkWalletEvidenceStore>()
    private val accounts = mockk<AccountQueryService>()
    private val service =
        NetworkWalletProvisioningService(
            repository,
            vendor,
            evidence,
            accounts,
            F.origin(),
            Clock.fixed(Instant.parse("2026-09-14T00:00:00Z"), ZoneOffset.UTC),
        )
    private val prepared = F.intent()
    private val submitting = F.intent(NetworkWalletCreationStatus.SUBMITTING, 1)
    private val recovering = F.intent(NetworkWalletCreationStatus.RECOVERING, 2).copy(scanId = "scan-fixture")
    private val completed =
        F
            .intent(
                NetworkWalletCreationStatus.COMPLETED,
                3,
            ).copy(knownWalletId = F.wallet().vendorWalletId, scanComplete = true)

    @BeforeEach
    fun setup() {
        every { accounts.requiredAccount(F.account().accountId) } returns F.account()
        every { repository.reserve(F.seed(), F.NOW) } returns prepared
        every { repository.claimSubmission(F.seed().request.scope, 0, F.NOW) } returns submitting
        every { repository.startRecovery(F.seed().request.scope, any(), any(), F.NOW) } returns recovering
        every { repository.recordPage(F.seed().request.scope, any(), any(), F.NOW) } returns completed
        every { evidence.store(any(), any()) } returns F.evidence()
        every { vendor.create(F.seed().request, F.seed().submission) } returns F.response(F.wallet())
    }

    @Test
    fun `최초 승자만 고정 snapshot으로 생성하고 원문 증적 뒤에 완료를 기록한다`() {
        val page = slot<NetworkWalletRecoveryPage>()
        every { repository.recordPage(F.seed().request.scope, recovering.revision, capture(page), F.NOW) } returns completed
        assertThat(service.provision(F.seed())).isEqualTo(completed)
        verify(exactly = 1) { vendor.create(F.seed().request, F.seed().submission) }
        verify {
            evidence.store(
                match { it.operation == NetworkWalletEvidenceOperation.CREATE && it.intentId == F.seed().intentId },
                match { it.contentEquals(F.body()) },
            )
        }
        assertThat(page.captured.evidenceHash).isEqualTo(F.evidence().hash)
        assertThat(page.captured.candidates).containsExactly(F.wallet())
    }

    @Test
    fun `동시 요청의 claim 패자는 create를 호출하지 않는다`() {
        val claimed = AtomicBoolean(false)
        every { repository.claimSubmission(F.seed().request.scope, 0, F.NOW) } answers {
            if (claimed.compareAndSet(false, true)) submitting else null
        }
        every { repository.find(F.seed().request.scope) } returns submitting
        Executors.newFixedThreadPool(2).use { executor ->
            val calls = (1..2).map { executor.submit<NetworkWalletCreationIntent> { service.provision(F.seed()) } }
            calls.forEach { it.get(5, TimeUnit.SECONDS) }
        }
        verify(exactly = 1) { vendor.create(any(), any()) }
    }

    @Test
    fun `응답 유실 뒤 재요청은 새 create 없이 후보 조회로 회수한다`() {
        every { vendor.create(any(), any()) } throws IllegalStateException("simulated response loss")
        every { repository.reserve(F.seed(), F.NOW) } returnsMany listOf(prepared, submitting)
        every { vendor.candidates(F.seed().request, null) } returns F.response(VendorPage(listOf(F.wallet()), null))
        assertThatThrownBy { service.provision(F.seed()) }.isInstanceOf(IllegalStateException::class.java)
        assertThat(service.provision(F.seed())).isEqualTo(completed)
        verify(exactly = 1) { vendor.create(any(), any()) }
        verify(exactly = 1) { vendor.candidates(F.seed().request, null) }
    }

    @Test
    fun `원문 보관 실패는 완료 기록을 남기지 않는다`() {
        every { evidence.store(any(), any()) } throws IllegalStateException("simulated archive failure")
        assertThatThrownBy { service.provision(F.seed()) }.isInstanceOf(IllegalStateException::class.java)
        verify(exactly = 0) { repository.recordPage(any(), any(), any(), any()) }
        verify(exactly = 1) { vendor.create(any(), any()) }
    }

    @Test
    fun `보관한 원문 hash가 다르면 성공으로 기록하지 않는다`() {
        every { evidence.store(any(), any()) } returns F.evidence().copy(hash = "0".repeat(64))
        assertThatThrownBy { service.provision(F.seed()) }.isInstanceOf(IllegalStateException::class.java)
        verify(exactly = 0) { repository.recordPage(any(), any(), any(), any()) }
    }

    @Test
    fun `진행 중 목록은 known ID가 있어도 저장 cursor에서 한 페이지만 이어간다`() {
        val partial = recovering.copy(nextCursor = "next-fixture", knownWalletId = F.wallet().vendorWalletId)
        every { repository.reserve(F.seed(), F.NOW) } returns partial
        every { vendor.candidates(F.seed().request, "next-fixture") } returns F.response(VendorPage(emptyList(), "later-fixture"))
        val page = slot<NetworkWalletRecoveryPage>()
        every { repository.recordPage(F.seed().request.scope, partial.revision, capture(page), F.NOW) } returns
            partial.copy(nextCursor = "later-fixture")
        assertThat(service.provision(F.seed()).nextCursor).isEqualTo("later-fixture")
        assertThat(page.captured.cursor).isEqualTo("next-fixture")
        verify(exactly = 0) { repository.startRecovery(any(), any(), any(), any()) }
        verify(exactly = 0) { vendor.read(any(), any()) }
        verify(exactly = 0) { vendor.create(any(), any()) }
    }

    @Test
    fun `known ID가 미관찰이어도 같은 ID만 조회하고 재생성하지 않는다`() {
        val waiting = recovering.copy(knownWalletId = F.wallet().vendorWalletId, scanComplete = true)
        every { repository.reserve(F.seed(), F.NOW) } returns waiting
        every { repository.startRecovery(any(), any(), any(), any()) } returns waiting.copy(scanComplete = false, revision = 3)
        every { vendor.read(F.seed().request.scope, F.wallet().vendorWalletId) } returns F.response(null)
        val page = slot<NetworkWalletRecoveryPage>()
        every { repository.recordPage(any(), any(), capture(page), any()) } returns waiting.copy(lastReason = "NOT_OBSERVED")
        assertThat(service.provision(F.seed()).lastReason).isEqualTo("NOT_OBSERVED")
        assertThat(page.captured.candidates).isEmpty()
        verify(exactly = 0) { vendor.create(any(), any()) }
        verify(exactly = 0) { vendor.candidates(any(), any()) }
    }

    @Test
    fun `조회 오류에는 cursor를 저장하거나 생성으로 전환하지 않는다`() {
        every { repository.reserve(F.seed(), F.NOW) } returns recovering
        every { vendor.candidates(any(), any()) } throws IllegalStateException("simulated lookup failure")
        assertThatThrownBy { service.provision(F.seed()) }.isInstanceOf(IllegalStateException::class.java)
        verify(exactly = 0) { repository.recordPage(any(), any(), any(), any()) }
        verify(exactly = 0) { vendor.create(any(), any()) }
    }

    @Test
    fun `완료 또는 충돌 의도는 외부 호출 없이 반환한다`() {
        every { repository.reserve(F.seed(), F.NOW) } returnsMany
            listOf(completed, completed.copy(status = NetworkWalletCreationStatus.CONFLICT))
        assertThat(service.provision(F.seed()).status).isEqualTo(NetworkWalletCreationStatus.COMPLETED)
        assertThat(service.provision(F.seed()).status).isEqualTo(NetworkWalletCreationStatus.CONFLICT)
        verify(exactly = 0) { vendor.create(any(), any()) }
        verify(exactly = 0) { vendor.candidates(any(), any()) }
        verify(exactly = 0) { vendor.read(any(), any()) }
    }

    @Test
    fun `다른 원천과 VAULT 계정은 의도 예약 전에 거절한다`() {
        val wrong =
            F.seed().copy(
                request =
                    F.seed().request.copy(
                        scope =
                            F
                                .seed()
                                .request.scope
                                .copy(origin = F.origin().copy(chainMode = "MAINNET")),
                    ),
            )
        assertThatThrownBy { service.provision(wrong) }.isInstanceOf(IllegalStateException::class.java)
        every { accounts.requiredAccount(any()) } returns F.account().copy(model = AccountModel.VAULT, vendorVaultId = "vault-fixture")
        assertThatThrownBy { service.provision(F.seed()) }.isInstanceOf(ConflictException::class.java)
        verify(exactly = 0) { repository.reserve(any(), any()) }
        verify(exactly = 0) { vendor.create(any(), any()) }
    }
}
