package com.whatto.bcm.app.application.account

import com.whatto.bcm.app.application.account.fixture.AccountFixture
import com.whatto.bcm.domain.account.AccountRepository
import com.whatto.bcm.domain.exception.AccountNotFoundException
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AccountQueryServiceTest {
    private val accounts = mockk<AccountRepository>()
    private val service = AccountQueryService(accounts)

    @Test
    fun `계정이 있으면 피처 경계에서 조회한다`() {
        val account = AccountFixture.fixture(accountId = "acct-1")
        every { accounts.findByAccountId("acct-1") } returns account

        assertThat(service.requiredAccount("acct-1")).isEqualTo(account)
    }

    @Test
    fun `계정이 없으면 ACCOUNT_NOT_FOUND로 변환할 도메인 예외를 던진다`() {
        every { accounts.findByAccountId("missing") } returns null

        assertThatThrownBy { service.requiredAccount("missing") }
            .isInstanceOf(AccountNotFoundException::class.java)
    }
}
