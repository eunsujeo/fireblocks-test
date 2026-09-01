package com.whatto.bcm.app.application.account

import com.whatto.bcm.app.application.account.fixture.AccountFixture
import com.whatto.bcm.app.application.account.fixture.DepositAddressFixture
import com.whatto.bcm.app.application.asset.VendorAssetMappingQueryService
import com.whatto.bcm.domain.account.AccountCreationIntent
import com.whatto.bcm.domain.account.AccountRepository
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.account.CreationStatus
import com.whatto.bcm.domain.account.DepositAddressCreationIntent
import com.whatto.bcm.domain.account.DepositAddressRepository
import com.whatto.bcm.domain.account.WalletProvisioningPolicy
import com.whatto.bcm.domain.account.WalletProvisioningRepository
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.exception.AccountNotFoundException
import com.whatto.bcm.domain.exception.AssetNotSupportedException
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.CreationRetryLaterException
import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.vendor.VendorBalance
import com.whatto.bcm.domain.vendor.VendorDepositAddress
import com.whatto.bcm.domain.vendor.VendorPage
import com.whatto.bcm.domain.vendor.VendorVault
import com.whatto.bcm.domain.vendor.WalletVendorPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.mockk.verifyOrder
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * 계정·주소 오케스트레이션 계약 — ref/(accountId, asset) 멱등 · UNIQUE 경합 시 이긴 값 반환(03) · 벤더 호출 최소화.
 */
class AccountServiceTest {
    private val accountRepository = mockk<AccountRepository>()
    private val depositAddressRepository = mockk<DepositAddressRepository>()
    private val provisioningRepository = mockk<WalletProvisioningRepository>()
    private val assetMappingQueryService = mockk<VendorAssetMappingQueryService>()
    private val walletVendorPort = mockk<WalletVendorPort>()
    private val provisioningPolicy = WalletProvisioningPolicy(Duration.ofSeconds(49))

    // zone이 KST인 Clock이어도 registeredAt은 UTC 14자 일시로 정규화한다 (CLAUDE.md 3절).
    private val fixedClock = Clock.fixed(Instant.parse("2026-08-05T12:04:05Z"), ZoneId.of("Asia/Seoul"))

    private val service =
        AccountService(
            accountRepository,
            depositAddressRepository,
            provisioningRepository,
            assetMappingQueryService,
            walletVendorPort,
            provisioningPolicy,
            fixedClock,
        )

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

    private fun accountIntent(
        accountId: String = "acct_00000000-0000-0000-0000-000000000001",
        accountType: AccountType = AccountType.CUSTOMER,
        ref: String = "000001",
        vendorVaultName: String = "$accountType:$ref",
        idempotencyKey: String = "bcm-vlt-00000000000000000000000000000001",
        idempotencyKeyRegisteredAt: String = "20260805120405",
        lastVendorCallPreparedAt: String? = null,
        status: CreationStatus = CreationStatus.PENDING,
        attemptCount: Int = 0,
    ) = AccountCreationIntent(
        accountId = accountId,
        accountType = accountType,
        ref = ref,
        vendorVaultName = vendorVaultName,
        idempotencyKey = idempotencyKey,
        idempotencyKeyRegisteredAt = idempotencyKeyRegisteredAt,
        lastVendorCallPreparedAt = lastVendorCallPreparedAt,
        status = status,
        attemptCount = attemptCount,
        vendorVaultId = null,
        registeredAt = "20260805120405",
        lastChangedAt = "20260805120405",
    )

    private fun addressIntent(
        accountId: String = "acct_test_01",
        network: String = "ETHEREUM",
        symbol: String = "USDC",
        vendorAssetId: String = "USDC_ERC20",
        idempotencyKey: String = "bcm-adr-00000000000000000000000000000001",
        idempotencyKeyRegisteredAt: String = "20260805120405",
        lastVendorCallPreparedAt: String? = null,
        status: CreationStatus = CreationStatus.PENDING,
        attemptCount: Int = 0,
    ) = DepositAddressCreationIntent(
        accountId = accountId,
        network = network,
        symbol = symbol,
        vendorAssetId = vendorAssetId,
        idempotencyKey = idempotencyKey,
        idempotencyKeyRegisteredAt = idempotencyKeyRegisteredAt,
        lastVendorCallPreparedAt = lastVendorCallPreparedAt,
        status = status,
        attemptCount = attemptCount,
        address = null,
        registeredAt = "20260805120405",
        lastChangedAt = "20260805120405",
    )

    @BeforeEach
    fun prepareVendorCallDefaults() {
        every { provisioningRepository.prepareAccountVendorCall(any(), any(), any(), any()) } answers {
            firstArg<AccountCreationIntent>().copy(
                idempotencyKey = secondArg(),
                idempotencyKeyRegisteredAt = thirdArg(),
                lastVendorCallPreparedAt = arg(3),
            )
        }
        every { provisioningRepository.prepareAddressVendorCall(any(), any(), any(), any()) } answers {
            firstArg<DepositAddressCreationIntent>().copy(
                idempotencyKey = secondArg(),
                idempotencyKeyRegisteredAt = thirdArg(),
                lastVendorCallPreparedAt = arg(3),
            )
        }
    }

    @Test
    fun `신규 (유형, ref) — 벤더 vault 를 만들고 매핑을 저장해 돌려준다`() {
        every { accountRepository.findByTypeAndRef(AccountType.CUSTOMER, "000001") } returns null
        val reserved = accountIntent()
        val requestedIntent = slot<AccountCreationIntent>()
        every { provisioningRepository.reserveAccount(capture(requestedIntent)) } returns reserved
        every { provisioningRepository.beginAccountAttempt(reserved.accountId, any()) } returns
            reserved.copy(status = CreationStatus.SUBMITTING, attemptCount = 1)
        every { walletVendorPort.createVault(reserved.vendorVaultName, reserved.idempotencyKey) } returns
            VendorVault("77", "CUSTOMER:000001")
        every { provisioningRepository.completeAccount(any(), "77", any()) } returns
            AccountFixture.fixture(
                accountId = reserved.accountId,
                ref = "000001",
                vendorVaultId = "77",
                registeredAt = "20260805120405",
            )

        val account = service.createAccount(AccountType.CUSTOMER, "000001")

        assertThat(account.ref).isEqualTo("000001")
        assertThat(account.vendorVaultId).isEqualTo("77")
        assertThat(account.accountId).startsWith("acct_")
        assertThat(account.accountId.length).isLessThanOrEqualTo(64)
        assertThat(account.registeredAt).isEqualTo("20260805120405")
        assertThat(requestedIntent.captured.idempotencyKey).startsWith("bcm-vlt-").hasSize(40)
        verifyOrder {
            provisioningRepository.reserveAccount(any())
            provisioningRepository.beginAccountAttempt(reserved.accountId, "20260805120405")
            provisioningRepository.prepareAccountVendorCall(any(), reserved.idempotencyKey, "20260805120405", "20260805120405")
            walletVendorPort.createVault(reserved.vendorVaultName, reserved.idempotencyKey)
            provisioningRepository.completeAccount(any(), "77", "20260805120405")
        }
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
    fun `(유형, ref) 생성 의도 경합 — 같은 의도와 완료 매핑을 돌려준다 (03)`() {
        val winner = AccountFixture.fixture(accountId = "acct_winner", ref = "000001")
        val reserved = accountIntent(accountId = "acct_winner")
        every { accountRepository.findByTypeAndRef(AccountType.CUSTOMER, "000001") } returns null
        every { provisioningRepository.reserveAccount(any()) } returns reserved
        every { provisioningRepository.beginAccountAttempt(reserved.accountId, any()) } returns
            reserved.copy(status = CreationStatus.SUBMITTING, attemptCount = 1)
        every { walletVendorPort.createVault(reserved.vendorVaultName, reserved.idempotencyKey) } returns
            VendorVault("77", "CUSTOMER:000001")
        every { provisioningRepository.completeAccount(any(), "77", any()) } returns winner

        val account = service.createAccount(AccountType.CUSTOMER, "000001")

        assertThat(account).isEqualTo(winner)
    }

    @Test
    fun `vault 생성 의도를 다른 요청이 먼저 완료하면 완료 매핑을 돌려주고 벤더를 호출하지 않는다`() {
        val reserved = accountIntent(accountId = "acct_winner")
        val completed = AccountFixture.fixture(accountId = reserved.accountId, ref = reserved.ref, vendorVaultId = "77")
        every { accountRepository.findByTypeAndRef(AccountType.CUSTOMER, reserved.ref) } returns null andThen completed
        every { provisioningRepository.reserveAccount(any()) } returns reserved
        every { provisioningRepository.beginAccountAttempt(reserved.accountId, any()) } returns
            reserved.copy(status = CreationStatus.COMPLETED, vendorVaultId = "77")

        assertThat(service.createAccount(AccountType.CUSTOMER, reserved.ref)).isEqualTo(completed)
        verify(exactly = 0) { walletVendorPort.createVault(any(), any()) }
        verify(exactly = 0) { provisioningRepository.completeAccount(any(), any(), any()) }
    }

    @Test
    fun `vault 생성 성공 뒤 DB 완료가 실패하면 재시도는 exact-name 조회로 회수하고 추가 생성하지 않는다`() {
        val reserved = accountIntent()
        val completed =
            AccountFixture.fixture(
                accountId = reserved.accountId,
                ref = reserved.ref,
                vendorVaultId = "77",
            )
        every { accountRepository.findByTypeAndRef(AccountType.CUSTOMER, "000001") } returns null
        every { provisioningRepository.reserveAccount(any()) } returns reserved
        every { provisioningRepository.beginAccountAttempt(reserved.accountId, any()) } returns
            reserved.copy(status = CreationStatus.SUBMITTING, attemptCount = 1) andThen
            reserved.copy(status = CreationStatus.SUBMITTING, attemptCount = 2)
        every { walletVendorPort.createVault(reserved.vendorVaultName, reserved.idempotencyKey) } returns
            VendorVault("77", reserved.vendorVaultName)
        every { provisioningRepository.completeAccount(any(), "77", any()) } throws
            IllegalStateException("injected DB failure") andThen completed
        every { walletVendorPort.vaultsByName(reserved.vendorVaultName, null) } returns
            VendorPage(listOf(VendorVault("77", reserved.vendorVaultName)), null)

        assertThatThrownBy { service.createAccount(AccountType.CUSTOMER, "000001") }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(service.createAccount(AccountType.CUSTOMER, "000001")).isEqualTo(completed)
        verify(exactly = 1) { walletVendorPort.createVault(reserved.vendorVaultName, reserved.idempotencyKey) }
        verify(exactly = 1) { walletVendorPort.vaultsByName(reserved.vendorVaultName, null) }
    }

    @Test
    fun `vault 후보가 없고 멱등 키와 마지막 POST 준비가 24시간 지났으면 새 키를 CAS 준비해 생성한다`() {
        val expired =
            accountIntent(
                status = CreationStatus.SUBMITTING,
                attemptCount = 1,
                idempotencyKeyRegisteredAt = "20260804120404",
                lastVendorCallPreparedAt = "20260804120315",
            )
        val rotatedKey = slot<String>()
        every { accountRepository.findByTypeAndRef(AccountType.CUSTOMER, expired.ref) } returns null
        every { provisioningRepository.reserveAccount(any()) } returns expired
        every { provisioningRepository.beginAccountAttempt(expired.accountId, any()) } returns expired.copy(attemptCount = 2)
        every { walletVendorPort.vaultsByName(expired.vendorVaultName, null) } returns VendorPage(emptyList(), null)
        every {
            provisioningRepository.prepareAccountVendorCall(any(), capture(rotatedKey), "20260805120405", "20260805120405")
        } answers {
            expired.copy(
                attemptCount = 2,
                idempotencyKey = secondArg(),
                idempotencyKeyRegisteredAt = thirdArg(),
                lastVendorCallPreparedAt = arg(3),
            )
        }
        every { walletVendorPort.createVault(expired.vendorVaultName, any()) } returns
            VendorVault("77", expired.vendorVaultName)
        every { provisioningRepository.completeAccount(any(), "77", any()) } returns
            AccountFixture.fixture(accountId = expired.accountId, ref = expired.ref, vendorVaultId = "77")

        service.createAccount(AccountType.CUSTOMER, expired.ref)

        assertThat(rotatedKey.captured).startsWith("bcm-vlt-").hasSize(40).isNotEqualTo(expired.idempotencyKey)
        verify { walletVendorPort.createVault(expired.vendorVaultName, rotatedKey.captured) }
    }

    @Test
    fun `vault 키는 만료됐어도 마지막 POST 준비가 24시간 안이면 새 키 호출을 보류한다`() {
        val coolingDown =
            accountIntent(
                status = CreationStatus.SUBMITTING,
                attemptCount = 1,
                idempotencyKeyRegisteredAt = "20260804120404",
                lastVendorCallPreparedAt = "20260805110405",
            )
        every { accountRepository.findByTypeAndRef(AccountType.CUSTOMER, coolingDown.ref) } returns null
        every { provisioningRepository.reserveAccount(any()) } returns coolingDown
        every { provisioningRepository.beginAccountAttempt(coolingDown.accountId, any()) } returns
            coolingDown.copy(attemptCount = 2)
        every { walletVendorPort.vaultsByName(coolingDown.vendorVaultName, null) } returns VendorPage(emptyList(), null)

        val exception = assertThrows<CreationRetryLaterException> { service.createAccount(AccountType.CUSTOMER, coolingDown.ref) }
        assertThat(exception.retryAfterSeconds).isEqualTo(82_850)
        verify(exactly = 0) { provisioningRepository.prepareAccountVendorCall(any(), any(), any(), any()) }
        verify(exactly = 0) { walletVendorPort.createVault(any(), any()) }
    }

    @Test
    fun `vault 회수 후보가 둘이면 어느 것도 연결하지 않고 conflict로 격리한다`() {
        val reserved = accountIntent(attemptCount = 1, status = CreationStatus.SUBMITTING)
        every { accountRepository.findByTypeAndRef(AccountType.CUSTOMER, "000001") } returns null
        every { provisioningRepository.reserveAccount(any()) } returns reserved
        every { provisioningRepository.beginAccountAttempt(reserved.accountId, any()) } returns
            reserved.copy(attemptCount = 2)
        every { walletVendorPort.vaultsByName(reserved.vendorVaultName, null) } returns
            VendorPage(
                listOf(
                    VendorVault("77", reserved.vendorVaultName),
                    VendorVault("88", reserved.vendorVaultName),
                ),
                null,
            )

        assertThatThrownBy { service.createAccount(AccountType.CUSTOMER, "000001") }
            .isInstanceOf(ConflictException::class.java)
        verify(exactly = 0) { walletVendorPort.createVault(any(), any()) }
        verify(exactly = 0) { provisioningRepository.completeAccount(any(), any(), any()) }
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
        val reserved = addressIntent()
        val requestedIntent = slot<DepositAddressCreationIntent>()
        every { accountRepository.findByAccountId("acct_test_01") } returns AccountFixture.fixture(vendorVaultId = "77")
        every { assetMappingQueryService.requiredMapping("ETHEREUM", "USDC") } returns mapping()
        every { depositAddressRepository.find("acct_test_01", "ETHEREUM", "USDC") } returns null
        every { provisioningRepository.reserveAddress(capture(requestedIntent)) } returns reserved
        every { provisioningRepository.beginAddressAttempt("acct_test_01", "ETHEREUM", "USDC", any()) } returns
            reserved.copy(status = CreationStatus.SUBMITTING, attemptCount = 1)
        every {
            walletVendorPort.createDepositAddress("77", "USDC_ERC20", reserved.idempotencyKey)
        } returns VendorDepositAddress(address = "0xNEW", tag = null)
        every {
            provisioningRepository.completeAddress(any(), "0xNEW", any())
        } returns DepositAddressFixture.fixture(address = "0xNEW", registeredAt = "20260805120405")

        val depositAddress = service.createDepositAddress("acct_test_01", "ETHEREUM", "USDC")

        assertThat(depositAddress.address).isEqualTo("0xNEW")
        assertThat(depositAddress.accountId).isEqualTo("acct_test_01")
        assertThat(depositAddress.network).isEqualTo("ETHEREUM")
        assertThat(depositAddress.symbol).isEqualTo("USDC")
        assertThat(depositAddress.registeredAt).isEqualTo("20260805120405")
        assertThat(requestedIntent.captured.idempotencyKey).startsWith("bcm-adr-").hasSize(40)
    }

    @Test
    fun `주소 생성 의도 경합 — 같은 의도와 완료 매핑을 돌려준다`() {
        val winner = DepositAddressFixture.fixture(address = "0xWINNER")
        val reserved = addressIntent()
        every { accountRepository.findByAccountId("acct_test_01") } returns AccountFixture.fixture()
        every { assetMappingQueryService.requiredMapping("ETHEREUM", "USDC") } returns mapping()
        every { depositAddressRepository.find("acct_test_01", "ETHEREUM", "USDC") } returns null
        every { provisioningRepository.reserveAddress(any()) } returns reserved
        every { provisioningRepository.beginAddressAttempt(any(), any(), any(), any()) } returns
            reserved.copy(status = CreationStatus.SUBMITTING, attemptCount = 1)
        every { walletVendorPort.createDepositAddress(any(), any(), reserved.idempotencyKey) } returns
            VendorDepositAddress(address = "0xLOSER", tag = null)
        every { provisioningRepository.completeAddress(any(), "0xLOSER", any()) } returns winner

        val depositAddress = service.createDepositAddress("acct_test_01", "ETHEREUM", "USDC")

        assertThat(depositAddress).isEqualTo(winner)
    }

    @Test
    fun `주소 생성 의도를 다른 요청이 먼저 완료하면 완료 매핑을 돌려주고 벤더를 호출하지 않는다`() {
        val reserved = addressIntent()
        val completed = DepositAddressFixture.fixture(address = "0xWINNER")
        every { accountRepository.findByAccountId(reserved.accountId) } returns AccountFixture.fixture()
        every { assetMappingQueryService.requiredMapping(reserved.network, reserved.symbol) } returns mapping()
        every { depositAddressRepository.find(reserved.accountId, reserved.network, reserved.symbol) } returns
            null andThen completed
        every { provisioningRepository.reserveAddress(any()) } returns reserved
        every { provisioningRepository.beginAddressAttempt(any(), any(), any(), any()) } returns
            reserved.copy(status = CreationStatus.COMPLETED, address = completed.address)

        assertThat(service.createDepositAddress(reserved.accountId, reserved.network, reserved.symbol)).isEqualTo(completed)
        verify(exactly = 0) { walletVendorPort.createDepositAddress(any(), any(), any()) }
        verify(exactly = 0) { provisioningRepository.completeAddress(any(), any(), any()) }
    }

    @Test
    fun `wallet 생성 성공 뒤 DB 완료가 실패하면 재시도는 주소 조회로 회수하고 추가 생성하지 않는다`() {
        val account = AccountFixture.fixture(vendorVaultId = "77")
        val reserved = addressIntent()
        val completed = DepositAddressFixture.fixture(address = "0xRECOVERED")
        every { accountRepository.findByAccountId("acct_test_01") } returns account
        every { depositAddressRepository.find("acct_test_01", "ETHEREUM", "USDC") } returns null
        every { assetMappingQueryService.requiredMapping("ETHEREUM", "USDC") } returns
            mapping(vendorAssetId = "USDC_NEW_MAPPING")
        every { provisioningRepository.reserveAddress(any()) } returns reserved
        every { provisioningRepository.beginAddressAttempt(any(), any(), any(), any()) } returns
            reserved.copy(status = CreationStatus.SUBMITTING, attemptCount = 1) andThen
            reserved.copy(status = CreationStatus.SUBMITTING, attemptCount = 2)
        every { walletVendorPort.createDepositAddress("77", "USDC_ERC20", reserved.idempotencyKey) } returns
            VendorDepositAddress("0xRECOVERED", null)
        every { provisioningRepository.completeAddress(any(), "0xRECOVERED", any()) } throws
            IllegalStateException("injected DB failure") andThen completed
        every { walletVendorPort.depositAddresses("77", "USDC_ERC20", null) } returns
            VendorPage(listOf(VendorDepositAddress("0xRECOVERED", null)), null)

        assertThatThrownBy { service.createDepositAddress("acct_test_01", "ETHEREUM", "USDC") }
            .isInstanceOf(IllegalStateException::class.java)

        assertThat(service.createDepositAddress("acct_test_01", "ETHEREUM", "USDC")).isEqualTo(completed)
        verify(exactly = 1) { walletVendorPort.createDepositAddress("77", "USDC_ERC20", reserved.idempotencyKey) }
        verify(exactly = 1) { walletVendorPort.depositAddresses("77", "USDC_ERC20", null) }
    }

    @Test
    fun `wallet 후보가 없고 멱등 키와 마지막 POST 준비가 24시간 지났으면 새 키로 생성한다`() {
        val expired =
            addressIntent(
                status = CreationStatus.SUBMITTING,
                attemptCount = 1,
                idempotencyKeyRegisteredAt = "20260804120404",
                lastVendorCallPreparedAt = "20260804120315",
            )
        val rotatedKey = slot<String>()
        every { accountRepository.findByAccountId(expired.accountId) } returns AccountFixture.fixture(vendorVaultId = "77")
        every { assetMappingQueryService.requiredMapping(expired.network, expired.symbol) } returns mapping()
        every { depositAddressRepository.find(expired.accountId, expired.network, expired.symbol) } returns null
        every { provisioningRepository.reserveAddress(any()) } returns expired
        every { provisioningRepository.beginAddressAttempt(any(), any(), any(), any()) } returns expired.copy(attemptCount = 2)
        every { walletVendorPort.depositAddresses("77", expired.vendorAssetId, null) } returns VendorPage(emptyList(), null)
        every {
            provisioningRepository.prepareAddressVendorCall(any(), capture(rotatedKey), "20260805120405", "20260805120405")
        } answers {
            expired.copy(
                attemptCount = 2,
                idempotencyKey = secondArg(),
                idempotencyKeyRegisteredAt = thirdArg(),
                lastVendorCallPreparedAt = arg(3),
            )
        }
        every { walletVendorPort.createDepositAddress("77", expired.vendorAssetId, any()) } returns
            VendorDepositAddress("0xROTATED", null)
        every { provisioningRepository.completeAddress(any(), "0xROTATED", any()) } returns
            DepositAddressFixture.fixture(address = "0xROTATED")

        service.createDepositAddress(expired.accountId, expired.network, expired.symbol)

        assertThat(rotatedKey.captured).startsWith("bcm-adr-").hasSize(40).isNotEqualTo(expired.idempotencyKey)
        verify { walletVendorPort.createDepositAddress("77", expired.vendorAssetId, rotatedKey.captured) }
    }

    @Test
    fun `wallet 회수 cursor가 반복되면 생성이나 완료를 하지 않고 conflict로 격리한다`() {
        val reserved = addressIntent(attemptCount = 1, status = CreationStatus.SUBMITTING)
        every { accountRepository.findByAccountId("acct_test_01") } returns AccountFixture.fixture(vendorVaultId = "77")
        every { depositAddressRepository.find("acct_test_01", "ETHEREUM", "USDC") } returns null
        every { assetMappingQueryService.requiredMapping("ETHEREUM", "USDC") } returns mapping()
        every { provisioningRepository.reserveAddress(any()) } returns reserved
        every { provisioningRepository.beginAddressAttempt(any(), any(), any(), any()) } returns
            reserved.copy(attemptCount = 2)
        every { walletVendorPort.depositAddresses("77", "USDC_ERC20", null) } returns
            VendorPage(emptyList(), "repeated")
        every { walletVendorPort.depositAddresses("77", "USDC_ERC20", "repeated") } returns
            VendorPage(emptyList(), "repeated")

        assertThatThrownBy { service.createDepositAddress("acct_test_01", "ETHEREUM", "USDC") }
            .isInstanceOf(ConflictException::class.java)
        verify(exactly = 0) { walletVendorPort.createDepositAddress(any(), any(), any()) }
        verify(exactly = 0) { provisioningRepository.completeAddress(any(), any(), any()) }
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
    fun `잔액 조회 — 발급 자산의 실제 0잔액은 빈 배열 대신 자산 행으로 돌려준다`() {
        val zeroBalance = VendorBalance(total = "0", available = "0", pending = "0", frozen = "0", lockedAmount = "0")
        every { accountRepository.findByAccountId("acct_test_01") } returns AccountFixture.fixture(vendorVaultId = "77")
        every { depositAddressRepository.findAll("acct_test_01", "USDC", "BASE") } returns
            listOf(DepositAddressFixture.fixture(network = "BASE"))
        every { assetMappingQueryService.requiredMapping("BASE", "USDC") } returns mapping(network = "BASE")
        every { walletVendorPort.balanceOf("77", "USDC_ERC20") } returns zeroBalance

        val balances = service.balancesOf("acct_test_01", "BASE", "USDC")

        assertThat(balances).singleElement().satisfies({ balance ->
            assertThat(balance.network).isEqualTo("BASE")
            assertThat(balance.symbol).isEqualTo("USDC")
            assertThat(balance.balance).isEqualTo(zeroBalance)
        })
    }

    @Test
    fun `잔액 조회 — 계정은 있지만 요청 자산이 미발급이면 빈 배열이고 벤더를 호출하지 않는다`() {
        every { accountRepository.findByAccountId("acct_test_01") } returns AccountFixture.fixture(vendorVaultId = "77")
        every { depositAddressRepository.findAll("acct_test_01", "USDC", "BASE") } returns emptyList()

        val balances = service.balancesOf("acct_test_01", "BASE", "USDC")

        assertThat(balances).isEmpty()
        verify(exactly = 0) { assetMappingQueryService.requiredMapping(any(), any()) }
        verify(exactly = 0) { walletVendorPort.balanceOf(any(), any()) }
    }

    @Test
    fun `잔액 조회 — 로컬 발급 기록과 벤더 wallet 404 불일치는 빈 배열로 숨기지 않는다`() {
        every { accountRepository.findByAccountId("acct_test_01") } returns AccountFixture.fixture(vendorVaultId = "77")
        every { depositAddressRepository.findAll("acct_test_01", "USDC", "ETHEREUM") } returns
            listOf(DepositAddressFixture.fixture())
        every { assetMappingQueryService.requiredMapping("ETHEREUM", "USDC") } returns mapping()
        every { walletVendorPort.balanceOf("77", "USDC_ERC20") } throws VendorApiException("balanceOf", 404)

        assertThatThrownBy { service.balancesOf("acct_test_01", "ETHEREUM", "USDC") }
            .isInstanceOf(VendorApiException::class.java)
            .satisfies({ exception ->
                assertThat((exception as VendorApiException).httpStatus).isEqualTo(404)
            })
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
        val reserved =
            accountIntent(
                accountType = AccountType.SYSTEM,
                vendorVaultName = "SYSTEM:000001",
                idempotencyKey = "bcm-vlt-00000000000000000000000000000002",
            )
        every { accountRepository.findByTypeAndRef(AccountType.SYSTEM, "000001") } returns null
        every { provisioningRepository.reserveAccount(any()) } returns reserved
        every { provisioningRepository.beginAccountAttempt(reserved.accountId, any()) } returns
            reserved.copy(status = CreationStatus.SUBMITTING, attemptCount = 1)
        every {
            walletVendorPort.createVault("SYSTEM:000001", reserved.idempotencyKey)
        } returns VendorVault("88", "SYSTEM:000001")
        every { provisioningRepository.completeAccount(any(), "88", any()) } returns
            AccountFixture.fixture(accountType = AccountType.SYSTEM, ref = "000001", vendorVaultId = "88")

        val account = service.createAccount(AccountType.SYSTEM, "000001")

        assertThat(account.vendorVaultId).isEqualTo("88")
        assertThat(account.accountType).isEqualTo(AccountType.SYSTEM)
        assertThat(reserved.vendorVaultName).isEqualTo("SYSTEM:000001")
    }

    @Test
    fun `여러 네트워크 발급 — 네트워크 하나가 실패해도 나머지는 발급되고 결과가 요청 순서로 온다`() {
        val tronIntent = addressIntent(accountId = "acct_ok", network = "TRON", vendorAssetId = "USDC_TRX")
        every { accountRepository.findByAccountId("acct_ok") } returns AccountFixture.fixture(accountId = "acct_ok")
        every { assetMappingQueryService.requiredMapping("ETHEREUM", "USDC") } returns mapping()
        every { assetMappingQueryService.requiredMapping("TRON", "USDC") } returns mapping("TRON", vendorAssetId = "USDC_TRX")
        every { depositAddressRepository.find("acct_ok", "ETHEREUM", "USDC") } returns
            DepositAddressFixture.fixture(accountId = "acct_ok")
        every { depositAddressRepository.find("acct_ok", "TRON", "USDC") } returns null
        every { provisioningRepository.reserveAddress(any()) } returns tronIntent
        every { provisioningRepository.beginAddressAttempt("acct_ok", "TRON", "USDC", any()) } returns
            tronIntent.copy(status = CreationStatus.SUBMITTING, attemptCount = 1)
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
