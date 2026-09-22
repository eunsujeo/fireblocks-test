package com.whatto.bcm.app.application.admin

import com.whatto.bcm.app.application.admin.fixture.ExecutionGateFixture.event
import com.whatto.bcm.app.application.admin.fixture.ExecutionGateFixture.operator
import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminRole
import com.whatto.bcm.domain.admin.ExecutionGateRepository
import com.whatto.bcm.domain.admin.ExecutionGateType
import com.whatto.bcm.domain.asset.ChainModel
import com.whatto.bcm.domain.asset.VendorBlockchainCatalog
import com.whatto.bcm.domain.asset.VendorBlockchainCatalogRepository
import com.whatto.bcm.domain.event.EventIdGenerator
import com.whatto.bcm.domain.exception.ConflictException
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class ExecutionGateCommandServiceTest {
    @MockK
    lateinit var gates: ExecutionGateRepository

    @MockK
    lateinit var networks: VendorBlockchainCatalogRepository

    @MockK
    lateinit var ids: EventIdGenerator

    private lateinit var service: ExecutionGateCommandService

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)
        service = ExecutionGateCommandService(gates, networks, DirectTransactionRunner(), ids, CLOCK)
        every { networks.findByNetwork("BASE") } returns network()
    }

    @Test
    fun `운영자는 사유와 티켓을 남겨 네트워크 출금을 즉시 중지한다`() {
        val inserted = slot<com.whatto.bcm.domain.admin.ExecutionGateEvent>()
        every { gates.findByIdempotency("830001", "stop-1") } returns null
        every { gates.findCurrent("BASE", ExecutionGateType.WITHDRAWAL) } returns null
        every { ids.nextId() } returns "gate-event-new"
        every { gates.insert(capture(inserted)) } answers { firstArg() }

        val result = service.stop(command())

        assertThat(result).isEqualTo(inserted.captured)
        assertThat(result.sequence).isEqualTo(1)
        assertThat(result.actor).isEqualTo(operator())
    }

    @Test
    fun `같은 멱등 키와 내용은 기존 event를 반환하고 다른 내용은 충돌한다`() {
        val existing = event(idempotencyKey = "stop-1")
        every { gates.findByIdempotency("830001", "stop-1") } returns existing

        assertThat(service.stop(command())).isEqualTo(existing)
        assertThatThrownBy { service.stop(command().copy(reason = "다른 사유")) }
            .isInstanceOf(ConflictException::class.java)
        verify(exactly = 0) { gates.insert(any()) }
    }

    @Test
    fun `이미 중지된 범위는 새 event를 만들지 않고 현재 중지를 반환한다`() {
        val existing = event(idempotencyKey = "first-stop")
        every { gates.findByIdempotency("830001", "stop-1") } returns null
        every { gates.findCurrent("BASE", ExecutionGateType.WITHDRAWAL) } returns existing

        assertThat(service.stop(command())).isEqualTo(existing)
        verify(exactly = 0) { gates.insert(any()) }
    }

    @Test
    fun `운영자 역할이 없으면 중지 원장을 조회하거나 쓰지 않는다`() {
        val viewer = AdminActor("830002", "0001", setOf(AdminRole.BCM_VIEWER))

        assertThatThrownBy { service.stop(command().copy(actor = viewer)) }
            .isInstanceOf(IllegalStateException::class.java)
        verify(exactly = 0) { gates.findByIdempotency(any(), any()) }
    }

    private fun command() =
        StopExecutionGateCommand(
            "BASE",
            ExecutionGateType.WITHDRAWAL,
            "이상 거래 조사",
            "INC-100",
            "stop-1",
            operator(),
        )

    private fun network() = VendorBlockchainCatalog("base", "BASE", 8453, "Base", false, false, "20260817120000", ChainModel.EVM)

    private class DirectTransactionRunner : TransactionRunner {
        override fun <T> run(block: () -> T): T = block()
    }

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-08-17T12:00:00Z"), ZoneOffset.UTC)
    }
}
