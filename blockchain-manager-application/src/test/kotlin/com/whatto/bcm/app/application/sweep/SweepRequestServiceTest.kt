package com.whatto.bcm.app.application.sweep

import com.whatto.bcm.app.application.account.AccountQueryService
import com.whatto.bcm.app.application.admin.ExecutionGateQueryService
import com.whatto.bcm.app.application.asset.VendorAssetMappingQueryService
import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.admin.ExecutionGateEvent
import com.whatto.bcm.domain.admin.ExecutionGateStatus
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.event.EventIdGenerator
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.sweep.SweepRequest
import com.whatto.bcm.domain.sweep.SweepRequestAcceptance
import com.whatto.bcm.domain.sweep.SweepRequestRepository
import com.whatto.bcm.domain.sweep.SweepRequestStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SweepRequestServiceTest {
    private val requests = mockk<SweepRequestRepository>()
    private val accounts = mockk<AccountQueryService>()
    private val assets = mockk<VendorAssetMappingQueryService>()
    private val gates = mockk<ExecutionGateQueryService>()
    private val ids = ArrayDeque(listOf(REQUEST_ID, ITEM_A_ID, ITEM_B_ID))
    private val service =
        SweepRequestService(
            transactionRunner = DirectTransactionRunner,
            requests = requests,
            accounts = accounts,
            assets = assets,
            executionGates = gates,
            ids = EventIdGenerator { ids.removeFirst() },
            clock = Clock.fixed(Instant.parse("2026-08-27T01:02:03Z"), ZoneOffset.UTC),
        )

    @BeforeEach
    fun setUp() {
        every { requests.findByExternalRequestId(EXTERNAL_ID) } returns null
        every { assets.requiredCurrentMapping("BASE", "USDC") } returns mapping()
        every { accounts.requiredAccount(any()) } answers { account(firstArg()) }
        every { gates.lockAndFindCurrent(any(), any()) } returns null
    }

    @Test
    fun `요청 항목을 canonical 순서로 접수하고 실행 후보를 만든다`() {
        val captured = slot<SweepRequest>()
        every { requests.accept(capture(captured)) } answers { SweepRequestAcceptance.Created(captured.captured) }

        val result = service.accept(command())

        assertThat(result.status).isEqualTo(SweepRequestStatus.ACCEPTED)
        assertThat(result.items.map { it.accountId }).containsExactly("account-a", "account-b")
        assertThat(result.items.map { it.sequence }).containsExactly(1, 2)
        assertThat(result.requestedAt).isEqualTo("20260827010203")
    }

    @Test
    fun `중지 게이트에서는 BLOCKED로 접수한다`() {
        every { gates.lockAndFindCurrent("BASE", any()) } returns stoppedGate()
        val captured = slot<SweepRequest>()
        every { requests.accept(capture(captured)) } answers { SweepRequestAcceptance.Created(captured.captured) }

        assertThat(service.accept(command()).status).isEqualTo(SweepRequestStatus.BLOCKED)
    }

    @Test
    fun `같은 외부 요청과 hash는 최초 결과를 반환하고 다른 body는 충돌한다`() {
        val existing = existingRequest()
        every { requests.findByExternalRequestId(EXTERNAL_ID) } returns existing

        assertThat(service.accept(command())).isSameAs(existing)
        assertThatThrownBy { service.accept(command(symbol = "KRWK")) }
            .isInstanceOf(ConflictException::class.java)
    }

    private fun command(symbol: String = "USDC") =
        SweepRequestCommand(
            externalSweepRequestId = EXTERNAL_ID,
            network = "BASE",
            symbol = symbol,
            items =
                listOf(
                    SweepRequestCommandItem("account-b", listOf(EVENT_B)),
                    SweepRequestCommandItem("account-a", listOf(EVENT_A)),
                ),
        )

    private fun existingRequest(): SweepRequest {
        val captured = slot<SweepRequest>()
        every { requests.accept(capture(captured)) } answers { SweepRequestAcceptance.Created(captured.captured) }
        val created = service.accept(command())
        every { requests.findByExternalRequestId(EXTERNAL_ID) } returns created
        return created
    }

    private fun account(id: String) = Account(id, AccountType.CUSTOMER, id, "vault-$id", "20260827000000")

    private fun mapping() = VendorAssetMapping("BASE", "USDC", "USDC_BASE_TEST", null, "20260827000000", "SYSTEM", "9999")

    private fun stoppedGate(): ExecutionGateEvent =
        mockk {
            every { status } returns ExecutionGateStatus.STOPPED
        }

    private object DirectTransactionRunner : TransactionRunner {
        override fun <T> run(block: () -> T): T = block()
    }

    private companion object {
        const val EXTERNAL_ID = "daw-sweep-20260827-1"
        const val REQUEST_ID = "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7801"
        const val ITEM_A_ID = "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7802"
        const val ITEM_B_ID = "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7803"
        const val EVENT_A = "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7891"
        const val EVENT_B = "0198f9f2-6de2-7e5d-8bb0-8d65fb6e7892"
    }
}
