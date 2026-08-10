package com.whatto.bcm.app.application.account

import com.whatto.bcm.app.application.account.fixture.AccountFixture
import com.whatto.bcm.app.application.account.fixture.DepositAddressFixture
import com.whatto.bcm.app.application.asset.VendorAssetMappingQueryService
import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountRepository
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.account.DepositAddress
import com.whatto.bcm.domain.account.DepositAddressRepository
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.exception.AccountNotFoundException
import com.whatto.bcm.domain.exception.AssetNotSupportedException
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.vendor.VendorBalance
import com.whatto.bcm.domain.vendor.VendorDepositAddress
import com.whatto.bcm.domain.vendor.VendorVault
import com.whatto.bcm.domain.vendor.WalletVendorPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * 계정·주소 오케스트레이션 계약 — ref/(accountId, asset) 멱등 · UNIQUE 경합 시 이긴 값 반환(03) · 벤더 호출 최소화.
 */
class AccountServiceTest {
    private val accountRepository = mockk<AccountRepository>()
    private val depositAddressRepository = mockk<DepositAddressRepository>()
    private val assetMappingQueryService = mockk<VendorAssetMappingQueryService>()
    private val walletVendorPort = mockk<WalletVendorPort>()

    // KST 고정 시각 — registeredAt 은 14자 일시 (CLAUDE.md 3절)
    private val fixedClock = Clock.fixed(Instant.parse("2026-08-05T03:04:05Z"), ZoneId.of("Asia/Seoul"))

    private val service =
        AccountService(accountRepository, depositAddressRepository, assetMappingQueryService, walletVendorPort, fixedClock)

    private fun mapping(
        network: String = "ETHEREUM",
        symbol: String = "USDC",
        vendorAssetId: String = "USDC_ERC20",
    ) = VendorAssetMapping(
        network = network,
        symbol = symbol,
        vendorAssetId = vendorAssetId,
        contractAddress = "0xUSDC$network",
        registeredAt = "20260805120405",
        registeredByEmployeeNo = "123456",
        registeredByBranchCode = "0001",
    )

    @Test
    fun `신규 (유형, ref) — 벤더 vault 를 만들고 매핑을 저장해 돌려준다`() {
        every { accountRepository.findByTypeAndRef(AccountType.CUSTOMER, "000001") } returns null
        every { walletVendorPort.createVault("CUSTOMER:000001", "createVault:CUSTOMER:000001") } returns
            VendorVault("77", "CUSTOMER:000001")
        val inserted = slot<Account>()
        every { accountRepository.insert(capture(inserted)) } answers { inserted.captured }

        val account = service.createAccount(AccountType.CUSTOMER, "000001")

        assertThat(account.ref).isEqualTo("000001")
        assertThat(account.vendorVaultId).isEqualTo("77")
        assertThat(account.accountId).startsWith("acct_")
        assertThat(account.accountId.length).isLessThanOrEqualTo(64)
        assertThat(account.registeredAt).isEqualTo("20260805120405")
    }

    @Test
    fun `기존 (유형, ref) 멱등 재요청 — 같은 매핑을 돌려주고 벤더를 호출하지 않는다`() {
        val existing = AccountFixture.fixture(ref = "000001")
        every { accountRepository.findByTypeAndRef(AccountType.CUSTOMER, "000001") } returns existing

        val account = service.createAccount(AccountType.CUSTOMER, "000001")

        assertThat(account).isEqualTo(existing)
        verify(exactly = 0) { walletVendorPort.createVault(any(), any()) }
    }

    @Test
    fun `(유형, ref) UNIQUE 경합 — insert 충돌이면 이긴 값을 재조회해 돌려준다 (03)`() {
        val winner = AccountFixture.fixture(accountId = "acct_winner", ref = "000001")
        every { accountRepository.findByTypeAndRef(AccountType.CUSTOMER, "000001") } returns null andThen winner
        every { walletVendorPort.createVault("CUSTOMER:000001", "createVault:CUSTOMER:000001") } returns
            VendorVault("77", "CUSTOMER:000001")
        every { accountRepository.insert(any()) } throws ConflictException("account", "CUSTOMER:000001")

        val account = service.createAccount(AccountType.CUSTOMER, "000001")

        assertThat(account).isEqualTo(winner)
    }

    @Test
    fun `주소 발급 — 계정이 없으면 AccountNotFoundException`() {
        every { accountRepository.findByAccountId("acct_x") } returns null

        assertThatThrownBy { service.createDepositAddress("acct_x", "ETHEREUM", "USDC") }
            .isInstanceOf(AccountNotFoundException::class.java)
    }

    @Test
    fun `주소 발급 멱등 재요청 — 기존 주소를 돌려주고 벤더를 호출하지 않는다`() {
        val existing = DepositAddressFixture.fixture()
        every { accountRepository.findByAccountId("acct_test_01") } returns AccountFixture.fixture()
        every { assetMappingQueryService.requiredMapping("ETHEREUM", "USDC") } returns mapping()
        every { depositAddressRepository.find("acct_test_01", "ETHEREUM", "USDC") } returns existing

        val depositAddress = service.createDepositAddress("acct_test_01", "ETHEREUM", "USDC")

        assertThat(depositAddress).isEqualTo(existing)
        verify(exactly = 0) { walletVendorPort.createDepositAddress(any(), any(), any()) }
    }

    @Test
    fun `주소 신규 발급 — 계정의 벤더 vault 로 발급하고 저장한다`() {
        every { accountRepository.findByAccountId("acct_test_01") } returns AccountFixture.fixture(vendorVaultId = "77")
        every { assetMappingQueryService.requiredMapping("ETHEREUM", "USDC") } returns mapping()
        every { depositAddressRepository.find("acct_test_01", "ETHEREUM", "USDC") } returns null
        every {
            walletVendorPort.createDepositAddress("77", "USDC_ERC20", "createDepositAddress:acct_test_01:ETHEREUM:USDC")
        } returns VendorDepositAddress(address = "0xNEW", tag = null)
        val inserted = slot<DepositAddress>()
        every { depositAddressRepository.insert(capture(inserted)) } answers { inserted.captured }

        val depositAddress = service.createDepositAddress("acct_test_01", "ETHEREUM", "USDC")

        assertThat(depositAddress.address).isEqualTo("0xNEW")
        assertThat(depositAddress.accountId).isEqualTo("acct_test_01")
        assertThat(depositAddress.network).isEqualTo("ETHEREUM")
        assertThat(depositAddress.symbol).isEqualTo("USDC")
        assertThat(depositAddress.registeredAt).isEqualTo("20260805120405")
    }

    @Test
    fun `주소 PK 경합 — insert 충돌이면 이긴 값을 재조회해 돌려준다`() {
        val winner = DepositAddressFixture.fixture(address = "0xWINNER")
        every { accountRepository.findByAccountId("acct_test_01") } returns AccountFixture.fixture()
        every { assetMappingQueryService.requiredMapping("ETHEREUM", "USDC") } returns mapping()
        every { depositAddressRepository.find("acct_test_01", "ETHEREUM", "USDC") } returns null andThen winner
        every { walletVendorPort.createDepositAddress(any(), any(), any()) } returns
            VendorDepositAddress(address = "0xLOSER", tag = null)
        every { depositAddressRepository.insert(any()) } throws ConflictException("depositAddress", "k")

        val depositAddress = service.createDepositAddress("acct_test_01", "ETHEREUM", "USDC")

        assertThat(depositAddress).isEqualTo(winner)
    }

    @Test
    fun `잔액 조회 — 계정이 없으면 AccountNotFoundException, 벤더를 호출하지 않는다`() {
        every { accountRepository.findByAccountId("acct_x") } returns null

        assertThatThrownBy { service.balancesOf("acct_x", null, null) }
            .isInstanceOf(AccountNotFoundException::class.java)
        verify(exactly = 0) { walletVendorPort.balanceOf(any(), any()) }
    }

    @Test
    fun `잔액 조회 — 계정의 벤더 vault 로 조회해 벤더 잔액을 그대로 돌려준다`() {
        val vendorBalance =
            VendorBalance(total = "11.8", available = "10.5", pending = "1.0", frozen = "0.2", lockedAmount = "0.1")
        every { accountRepository.findByAccountId("acct_test_01") } returns AccountFixture.fixture(vendorVaultId = "77")
        every { depositAddressRepository.findAll("acct_test_01", null, null) } returns
            listOf(DepositAddressFixture.fixture())
        every { assetMappingQueryService.requiredMapping("ETHEREUM", "USDC") } returns mapping()
        every { walletVendorPort.balanceOf("77", "USDC_ERC20") } returns vendorBalance

        val balances = service.balancesOf("acct_test_01", null, null)
        assertThat(balances).hasSize(1)
        assertThat(balances[0].network).isEqualTo("ETHEREUM")
        assertThat(balances[0].balance).isEqualTo(vendorBalance)
    }

    @Test
    fun `주소 조회 — 계정이 없으면 AccountNotFoundException, 미발급이면 빈 배열`() {
        every { accountRepository.findByAccountId("acct_x") } returns null
        assertThatThrownBy { service.depositAddressesOf("acct_x", null, null) }
            .isInstanceOf(AccountNotFoundException::class.java)

        every { accountRepository.findByAccountId("acct_test_01") } returns AccountFixture.fixture()
        every { depositAddressRepository.findAll("acct_test_01", null, null) } returns emptyList()
        assertThat(service.depositAddressesOf("acct_test_01", null, null)).isEmpty()
    }

    @Test
    fun `유형이 다르면 벤더 멱등 키도 다르다 — 같은 ref 가 vault 를 공유하면 안 된다`() {
        every { accountRepository.findByTypeAndRef(AccountType.SYSTEM, "000001") } returns null
        every {
            walletVendorPort.createVault("SYSTEM:000001", "createVault:SYSTEM:000001")
        } returns VendorVault("88", "SYSTEM:000001")
        val inserted = slot<Account>()
        every { accountRepository.insert(capture(inserted)) } answers { inserted.captured }

        val account = service.createAccount(AccountType.SYSTEM, "000001")

        // 고객 케이스는 createVault:CUSTOMER:000001 을 쓴다 — 유형이 키에 들어가야 vault 가 갈린다
        assertThat(account.vendorVaultId).isEqualTo("88")
        assertThat(account.accountType).isEqualTo(AccountType.SYSTEM)
        verify(exactly = 0) { walletVendorPort.createVault("000001", "createVault:000001") }
    }

    @Test
    fun `여러 네트워크 발급 — 네트워크 하나가 실패해도 나머지는 발급되고 결과가 요청 순서로 온다`() {
        every { accountRepository.findByAccountId("acct_ok") } returns AccountFixture.fixture(accountId = "acct_ok")
        every { assetMappingQueryService.requiredMapping("ETHEREUM", "USDC") } returns mapping()
        every { assetMappingQueryService.requiredMapping("TRON", "USDC") } returns mapping("TRON", vendorAssetId = "USDC_TRX")
        every { depositAddressRepository.find("acct_ok", "ETHEREUM", "USDC") } returns
            DepositAddressFixture.fixture(accountId = "acct_ok")
        every { depositAddressRepository.find("acct_ok", "TRON", "USDC") } returns null
        every {
            walletVendorPort.createDepositAddress(any(), "USDC_TRX", any())
        } throws ConflictException("depositAddress", "acct_ok:TRON:USDC")

        val results = service.createDepositAddresses("acct_ok", "USDC", listOf("ETHEREUM", "TRON"))

        assertThat(results.map { it.network }).containsExactly("ETHEREUM", "TRON")
        assertThat(results[0].depositAddress?.network).isEqualTo("ETHEREUM")
        assertThat(results[0].failure).isNull()
        assertThat(results[1].depositAddress).isNull()
        assertThat(results[1].failure).isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `여러 네트워크 발급 — 계정이 없으면 네트워크별 오류가 아니라 요청 전체가 AccountNotFoundException`() {
        every { accountRepository.findByAccountId("acct_x") } returns null

        assertThatThrownBy { service.createDepositAddresses("acct_x", "USDC", listOf("ETHEREUM")) }
            .isInstanceOf(AccountNotFoundException::class.java)
    }

    @Test
    fun `여러 네트워크 발급 — 같은 네트워크가 두 번 오면 발급은 한 번만 하고 같은 결과를 담는다`() {
        every { accountRepository.findByAccountId("acct_ok") } returns AccountFixture.fixture(accountId = "acct_ok")
        every { assetMappingQueryService.requiredMapping("ETHEREUM", "USDC") } returns mapping()
        every { depositAddressRepository.find("acct_ok", "ETHEREUM", "USDC") } returns
            DepositAddressFixture.fixture(accountId = "acct_ok")

        val results = service.createDepositAddresses("acct_ok", "USDC", listOf("ETHEREUM", "ETHEREUM"))

        assertThat(results).hasSize(2)
        assertThat(results[0]).isEqualTo(results[1])
        verify(exactly = 1) { depositAddressRepository.find("acct_ok", "ETHEREUM", "USDC") }
    }

    @Test
    fun `여러 네트워크 발급 — 매핑이 하나라도 없으면 벤더 호출 전에 전체를 ASSET_NOT_SUPPORTED로 거절한다`() {
        every { accountRepository.findByAccountId("acct_ok") } returns AccountFixture.fixture(accountId = "acct_ok")
        every { assetMappingQueryService.requiredMapping("ETHEREUM", "USDC") } returns mapping()
        every { assetMappingQueryService.requiredMapping("BASE", "USDC") } throws AssetNotSupportedException("BASE", "USDC")

        assertThatThrownBy {
            service.createDepositAddresses("acct_ok", "USDC", listOf("ETHEREUM", "BASE"))
        }.isInstanceOf(AssetNotSupportedException::class.java)
        verify(exactly = 0) { walletVendorPort.createDepositAddress(any(), any(), any()) }
        verify(exactly = 0) { depositAddressRepository.insert(any()) }
    }
}
