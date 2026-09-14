package com.whatto.bcm.domain.account

import com.whatto.bcm.domain.account.fixture.AccountModelFixture
import com.whatto.bcm.domain.exception.ConflictException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class AccountModelTest {
    @Test
    fun `기존 계정은 VAULT 모델과 동일 ID를 유지한다`() {
        val account = AccountModelFixture.fixture()
        assertThat(account.model).isEqualTo(AccountModel.VAULT)
        assertThat(account.requireVendorVaultId()).isEqualTo("vault-test")
    }

    @Test
    fun `논리 계정은 vault 없이 존재하고 vault 작업은 거절한다`() {
        val account = AccountModelFixture.fixture(AccountModel.LOGICAL, null)
        assertThat(account.vendorVaultId).isNull()
        assertThatThrownBy { account.requireVendorVaultId() }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `모델과 ID가 모순되거나 빈 vault이면 생성할 수 없다`() {
        listOf(null, "", " ", " vault", "vault ").forEach { id ->
            assertThatThrownBy { AccountModelFixture.fixture(vaultId = id) }.isInstanceOf(IllegalArgumentException::class.java)
        }
        assertThatThrownBy { AccountModelFixture.fixture(AccountModel.LOGICAL, "fake-vault") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
