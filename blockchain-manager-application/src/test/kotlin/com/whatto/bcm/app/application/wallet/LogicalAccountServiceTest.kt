package com.whatto.bcm.app.application.wallet

import com.whatto.bcm.app.application.account.LogicalAccountService
import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountModel
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.account.LogicalAccountRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import com.whatto.bcm.app.application.wallet.fixture.WalletProvisioningFixture as F

class LogicalAccountServiceTest {
    private val repository = mockk<LogicalAccountRepository>()
    private val clock = Clock.fixed(Instant.parse("2026-09-14T00:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `외부 vault 없이 유형과 ref로 논리 계정을 예약한다`() {
        val captured = slot<Account>()
        every { repository.reserve(capture(captured), F.origin()) } returns F.account()
        val service = LogicalAccountService(repository, F.origin(), clock)
        assertThat(service.create(AccountType.CUSTOMER, "ref-fixture")).isEqualTo(F.account())
        assertThat(captured.captured.model).isEqualTo(AccountModel.LOGICAL)
        assertThat(captured.captured.vendorVaultId).isNull()
        assertThat(captured.captured.ref).isEqualTo("ref-fixture")
        assertThat(captured.captured.registeredAt).isEqualTo(F.NOW)
    }

    @Test
    fun `다른 제공자는 논리 계정 예약을 수행하지 않는다`() {
        val origin = F.origin().copy(executionMode = "fireblocks", protocolProvider = "fireblocks")
        assertThatThrownBy { LogicalAccountService(repository, origin, clock).create(AccountType.CUSTOMER, "ref-fixture") }
            .isInstanceOf(IllegalArgumentException::class.java)
        verify(exactly = 0) { repository.reserve(any(), any()) }
    }
}
