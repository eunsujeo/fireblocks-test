package com.whatto.bcm.infra.persistence.account

import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountCreationIntent
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.account.CreationStatus
import com.whatto.bcm.domain.account.DepositAddress
import com.whatto.bcm.domain.account.DepositAddressCreationIntent
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.CreationRetryLaterException
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import

@DataJdbcTest
@Import(AccountJdbcAdapter::class, DepositAddressJdbcAdapter::class, WalletProvisioningJdbcAdapter::class)
class AccountPersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var accounts: AccountJdbcAdapter

    @Autowired
    lateinit var addresses: DepositAddressJdbcAdapter

    @Autowired
    lateinit var provisioning: WalletProvisioningJdbcAdapter

    private fun account(
        accountId: String = "acct_01",
        accountType: AccountType = AccountType.CUSTOMER,
        ref: String = "000001",
    ) = Account(
        accountId = accountId,
        accountType = accountType,
        ref = ref,
        vendorVaultId = "vault-7",
        registeredAt = "20260805120000",
    )

    @Test
    fun `계정 매핑 왕복 — (유형, ref) 와 accountId 로 같은 도메인 객체를 되찾는다`() {
        val saved = accounts.insert(account())
        assertThat(accounts.findByTypeAndRef(AccountType.CUSTOMER, "000001")).isEqualTo(saved)
        assertThat(accounts.findByAccountId("acct_01")).isEqualTo(saved)
    }

    @Test
    fun `계정 생성 멱등의 최종 방어 — 같은 (유형, ref) 재삽입은 도메인 ConflictException 으로 변환된다`() {
        accounts.insert(account(accountId = "acct_02", ref = "000DUP"))
        assertThatThrownBy { accounts.insert(account(accountId = "acct_03", ref = "000DUP")) }
            .isInstanceOf(ConflictException::class.java)
            .hasRootCauseInstanceOf(java.sql.SQLException::class.java) // cause 체인 보존 (error-handling.md)
    }

    @Test
    fun `vault 생성 의도는 벤더 호출 전에 고정되고 완료 시 계정 매핑과 한 트랜잭션으로 종결된다`() {
        val candidate =
            AccountCreationIntent(
                accountId = "acct_intent",
                accountType = AccountType.CUSTOMER,
                ref = "intent-ref",
                vendorVaultName = "CUSTOMER:intent-ref",
                idempotencyKey = "bcm-vlt-00000000000000000000000000000001",
                idempotencyKeyRegisteredAt = "20260901000000",
                lastVendorCallPreparedAt = null,
                status = CreationStatus.PENDING,
                attemptCount = 0,
                vendorVaultId = null,
                registeredAt = "20260901000000",
                lastChangedAt = "20260901000000",
            )

        val reserved = provisioning.reserveAccount(candidate)
        val raced = provisioning.reserveAccount(candidate.copy(accountId = "acct_loser", idempotencyKey = "bcm-vlt-loser"))
        val submitting = provisioning.beginAccountAttempt(reserved.accountId, "20260901000001")
        val firstPrepared =
            provisioning.prepareAccountVendorCall(
                submitting,
                submitting.idempotencyKey,
                submitting.idempotencyKeyRegisteredAt,
                "20260901000001",
            )
        val superseding = provisioning.beginAccountAttempt(reserved.accountId, "20260902000000")
        val stalePreparation =
            provisioning.prepareAccountVendorCall(
                submitting,
                submitting.idempotencyKey,
                submitting.idempotencyKeyRegisteredAt,
                "20260902000000",
            )
        val prepared =
            provisioning.prepareAccountVendorCall(
                superseding,
                "bcm-vlt-00000000000000000000000000000002",
                "20260902000000",
                "20260902000000",
            )
        assertThatThrownBy {
            provisioning.completeAccount(checkNotNull(firstPrepared), "vault-stale", "20260902000001")
        }.isInstanceOf(CreationRetryLaterException::class.java)
        assertThat(accounts.findByTypeAndRef(AccountType.CUSTOMER, "intent-ref")).isNull()
        val completed = provisioning.completeAccount(checkNotNull(prepared), "vault-77", "20260902000001")

        assertThat(raced).isEqualTo(reserved)
        assertThat(submitting.status).isEqualTo(CreationStatus.SUBMITTING)
        assertThat(submitting.attemptCount).isEqualTo(1)
        assertThat(stalePreparation).isNull()
        assertThat(prepared.lastVendorCallPreparedAt).isEqualTo("20260902000000")
        assertThat(completed.accountId).isEqualTo("acct_intent")
        assertThat(completed.vendorVaultId).isEqualTo("vault-77")
        assertThat(accounts.findByTypeAndRef(AccountType.CUSTOMER, "intent-ref")).isEqualTo(completed)
        assertThat(provisioning.findAccount(AccountType.CUSTOMER, "intent-ref")?.status)
            .isEqualTo(CreationStatus.COMPLETED)
        assertThat(provisioning.beginAccountAttempt(reserved.accountId, "20260901000003").status)
            .isEqualTo(CreationStatus.COMPLETED)
    }

    @Test
    fun `유형이 다르면 같은 ref 도 별개 계정이다 — 접두사가 없어 코어 ID 공간이 겹칠 수 있다`() {
        val customer = accounts.insert(account(accountId = "acct_cu", accountType = AccountType.CUSTOMER, ref = "000123"))
        val system = accounts.insert(account(accountId = "acct_sy", accountType = AccountType.SYSTEM, ref = "000123"))

        assertThat(accounts.findByTypeAndRef(AccountType.CUSTOMER, "000123")).isEqualTo(customer)
        assertThat(accounts.findByTypeAndRef(AccountType.SYSTEM, "000123")).isEqualTo(system)
        assertThat(customer.accountId).isNotEqualTo(system.accountId)
    }

    private fun address(
        accountId: String = "acct_01",
        network: String = "ETHEREUM",
        symbol: String = "USDC",
        address: String = "0xA1b2C3",
    ) = DepositAddress(
        accountId = accountId,
        network = network,
        symbol = symbol,
        address = address,
        registeredAt = "20260805120000",
    )

    @Test
    fun `주소 매핑 왕복 — (계정, 네트워크, 심볼)과 역방향(주소) 조회가 같은 객체를 되찾는다`() {
        val saved = addresses.insert(address())
        assertThat(addresses.find("acct_01", "ETHEREUM", "USDC")).isEqualTo(saved)
        assertThat(addresses.findByAddress("0xA1b2C3", "ETHEREUM")).isEqualTo(saved)
    }

    @Test
    fun `같은 심볼이라도 네트워크가 다르면 별개 주소다 — 여러 네트워크 발급의 물리 근거`() {
        val ethereum = addresses.insert(address(network = "ETHEREUM", address = "0xETH"))
        val polygon = addresses.insert(address(network = "POLYGON", address = "0xPOLY"))

        assertThat(addresses.find("acct_01", "ETHEREUM", "USDC")).isEqualTo(ethereum)
        assertThat(addresses.find("acct_01", "POLYGON", "USDC")).isEqualTo(polygon)
    }

    @Test
    fun `주소 목록 조회 — 필터 없으면 전체, symbol 으로 거르면 그 자산만`() {
        addresses.insert(address(network = "ETHEREUM", address = "0xETH"))
        addresses.insert(address(network = "BASE", address = "0xBASE"))
        addresses.insert(address(network = "BASE", symbol = "USDT", address = "0xUSDT"))

        assertThat(addresses.findAll("acct_01", null, null)).hasSize(3)
        assertThat(addresses.findAll("acct_01", "USDC", null)).hasSize(2)
        assertThat(addresses.findAll("acct_01", "USDC", "BASE")).hasSize(1)
        assertThat(addresses.findAll("acct_01", "NONE", null)).isEmpty()
    }

    @Test
    fun `주소 발급 멱등의 물리 근거 — 같은 (계정, 네트워크, 심볼) 재발급은 도메인 ConflictException 으로 변환된다`() {
        addresses.insert(address(address = "0xFIRST"))
        assertThatThrownBy { addresses.insert(address(address = "0xSECOND")) }
            .isInstanceOf(ConflictException::class.java)
            .hasRootCauseInstanceOf(java.sql.SQLException::class.java)
    }

    @Test
    fun `주소 생성 의도는 assetId를 고정하고 완료 시 주소 매핑과 한 트랜잭션으로 종결된다`() {
        accounts.insert(account(accountId = "acct_address_intent", ref = "address-intent-ref"))
        val candidate =
            DepositAddressCreationIntent(
                accountId = "acct_address_intent",
                network = "ETHEREUM",
                symbol = "USDC",
                vendorAssetId = "USDC_ERC20",
                idempotencyKey = "bcm-adr-00000000000000000000000000000001",
                idempotencyKeyRegisteredAt = "20260901000000",
                lastVendorCallPreparedAt = null,
                status = CreationStatus.PENDING,
                attemptCount = 0,
                address = null,
                registeredAt = "20260901000000",
                lastChangedAt = "20260901000000",
            )

        val reserved = provisioning.reserveAddress(candidate)
        val raced = provisioning.reserveAddress(candidate.copy(vendorAssetId = "CHANGED", idempotencyKey = "bcm-adr-loser"))
        val submitting =
            provisioning.beginAddressAttempt(
                candidate.accountId,
                candidate.network,
                candidate.symbol,
                "20260901000001",
            )
        val firstPrepared =
            provisioning.prepareAddressVendorCall(
                submitting,
                submitting.idempotencyKey,
                submitting.idempotencyKeyRegisteredAt,
                "20260901000001",
            )
        val superseding =
            provisioning.beginAddressAttempt(
                candidate.accountId,
                candidate.network,
                candidate.symbol,
                "20260902000000",
            )
        val prepared =
            provisioning.prepareAddressVendorCall(
                superseding,
                "bcm-adr-00000000000000000000000000000002",
                "20260902000000",
                "20260902000000",
            )
        assertThatThrownBy {
            provisioning.completeAddress(checkNotNull(firstPrepared), "0xSTALE", "20260902000001")
        }.isInstanceOf(CreationRetryLaterException::class.java)
        assertThat(addresses.find(candidate.accountId, candidate.network, candidate.symbol)).isNull()
        val completed =
            provisioning.completeAddress(
                checkNotNull(prepared),
                "0xRECOVERED",
                "20260902000001",
            )

        assertThat(raced).isEqualTo(reserved)
        assertThat(raced.vendorAssetId).isEqualTo("USDC_ERC20")
        assertThat(submitting.attemptCount).isEqualTo(1)
        assertThat(prepared.idempotencyKey).isEqualTo("bcm-adr-00000000000000000000000000000002")
        assertThat(prepared.idempotencyKeyRegisteredAt).isEqualTo("20260902000000")
        assertThat(completed.address).isEqualTo("0xRECOVERED")
        assertThat(addresses.find(candidate.accountId, candidate.network, candidate.symbol)).isEqualTo(completed)
        assertThat(provisioning.findAddress(candidate.accountId, candidate.network, candidate.symbol)?.status)
            .isEqualTo(CreationStatus.COMPLETED)
        assertThat(
            provisioning
                .beginAddressAttempt(
                    candidate.accountId,
                    candidate.network,
                    candidate.symbol,
                    "20260901000003",
                ).status,
        ).isEqualTo(CreationStatus.COMPLETED)
    }

    @Test
    fun `없는 매핑은 null — 귀속 불명 입금의 분기 근거`() {
        assertThat(addresses.findByAddress("0xUNKNOWN", "ETHEREUM")).isNull()
        assertThat(accounts.findByTypeAndRef(AccountType.CUSTOMER, "000NONE")).isNull()
    }
}
