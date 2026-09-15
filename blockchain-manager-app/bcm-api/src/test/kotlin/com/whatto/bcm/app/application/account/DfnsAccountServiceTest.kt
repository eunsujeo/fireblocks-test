package com.whatto.bcm.app.application.account

import com.whatto.bcm.app.application.asset.VendorAssetMappingQueryService
import com.whatto.bcm.app.application.wallet.NetworkWalletProvisioningService
import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountModel
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.account.DepositAddress
import com.whatto.bcm.domain.account.DepositAddressRepository
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.exception.AccountNotFoundException
import com.whatto.bcm.domain.exception.AssetNotSupportedException
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.ProvisioningPendingException
import com.whatto.bcm.domain.exception.UnprocessableRequestException
import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.vendor.NetworkWalletCreationRequest
import com.whatto.bcm.domain.vendor.NetworkWalletObservation
import com.whatto.bcm.domain.vendor.NetworkWalletOwnership
import com.whatto.bcm.domain.vendor.NetworkWalletScope
import com.whatto.bcm.domain.vendor.NetworkWalletSubmissionPort
import com.whatto.bcm.domain.wallet.NetworkWalletAddressPolicy
import com.whatto.bcm.domain.wallet.NetworkWalletCreationSeed
import com.whatto.bcm.domain.wallet.NetworkWalletSubmissionSpec
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Dfns 계정·주소 유스케이스 계약(계약13 "계정·주소 API의 Dfns 연결") — 논리 계정 멱등, 주소 모델·매핑 선검증,
 * scope 결정적 seed와 포트가 만든 제출 snapshot으로 지갑 서비스 호출, 검증된 지갑 주소 저장, 보류/충돌 전파, 잔액 조회 거절.
 * 도메인 포트만 mock하며 벤더 설정·HTTP 형식은 나타나지 않는다.
 */
class DfnsAccountServiceTest {
    private val logicalAccounts = mockk<LogicalAccountService>()
    private val accounts = mockk<AccountQueryService>()
    private val assetMappings = mockk<VendorAssetMappingQueryService>()
    private val depositAddresses = mockk<DepositAddressRepository>()
    private val provisioning = mockk<NetworkWalletProvisioningService>()
    private val submissions =
        NetworkWalletSubmissionPort { request ->
            mapOf("ETHEREUM_SEPOLIA" to "EthereumSepolia", "SOLANA_DEVNET" to "SolanaDevnet")[request.scope.network]?.let { vendorNetwork ->
                NetworkWalletSubmissionSpec(sha256("$vendorNetwork|${request.correlationId}"), "test-v1", vendorNetwork)
            }
        }
    private val policy = NetworkWalletAddressPolicy(setOf("ETHEREUM_SEPOLIA", "UNMAPPED_NET"), 7)
    private val clock = Clock.fixed(Instant.parse("2026-09-15T00:00:00Z"), ZoneOffset.UTC)
    private val service =
        DfnsAccountService(logicalAccounts, accounts, assetMappings, depositAddresses, provisioning, submissions, policy, ORIGIN, clock)
    private val logical = Account("acct_dfns_1", AccountType.CUSTOMER, "ref-1", null, "20260915000000", AccountModel.LOGICAL)
    private val scope = NetworkWalletScope(ORIGIN, logical.accountId, "ETHEREUM_SEPOLIA")

    init {
        every { accounts.requiredAccount(logical.accountId) } returns logical
        every { accounts.requiredAccount("acct_missing") } throws AccountNotFoundException("acct_missing")
        every { assetMappings.requiredCurrentMapping("ETHEREUM_SEPOLIA", "USDC") } returns mapping("ETHEREUM_SEPOLIA")
        every { assetMappings.requiredCurrentMapping("ETHEREUM_SEPOLIA", "KRWK") } returns mapping("ETHEREUM_SEPOLIA", "KRWK")
        every { assetMappings.requiredCurrentMapping("SOLANA_DEVNET", "USDC") } returns mapping("SOLANA_DEVNET")
        every { assetMappings.requiredCurrentMapping("UNMAPPED_NET", "USDC") } returns mapping("UNMAPPED_NET")
        every { assetMappings.requiredCurrentMapping("TRON", "USDC") } throws AssetNotSupportedException("TRON", "USDC")
        every { depositAddresses.find(any(), any(), any()) } returns null
    }

    @Test
    fun `계정 생성은 외부 호출 없이 논리 계정 등록으로 완료한다`() {
        every { logicalAccounts.create(AccountType.CUSTOMER, "ref-1") } returns logical

        assertThat(service.createAccount(AccountType.CUSTOMER, "ref-1")).isEqualTo(logical)
        verify(exactly = 0) { provisioning.provisionedWallet(any(), any()) }
    }

    @Test
    fun `준비된 지갑의 주소를 그 네트워크의 토큰 수신 주소로 저장하고 포트가 만든 제출 snapshot과 정책 재시도 초를 넘긴다`() {
        val seed = slot<NetworkWalletCreationSeed>()
        every { provisioning.provisionedWallet(capture(seed), 7) } answers { wallet("wa-1", "0xabc") }
        every { depositAddresses.insert(any()) } answers { firstArg() }

        val issued = service.createDepositAddress(logical.accountId, "ETHEREUM_SEPOLIA", "USDC")

        assertThat(issued).isEqualTo(DepositAddress(logical.accountId, "ETHEREUM_SEPOLIA", "USDC", "0xabc", "20260915000000"))
        val captured = seed.captured
        assertThat(captured.request.scope).isEqualTo(scope)
        assertThat(captured.request.correlationId).isEqualTo(captured.intentId)
        assertThat(captured.submission).isEqualTo(submissions.submission(NetworkWalletCreationRequest(scope, captured.intentId)))
        assertThat(captured.submission.vendorNetwork).isEqualTo("EthereumSepolia")
        verify(exactly = 1) { depositAddresses.insert(issued) }
    }

    @Test
    fun `이미 발급된 주소는 지갑 의도를 건드리지 않고 그대로 돌려준다`() {
        val existing = DepositAddress(logical.accountId, "ETHEREUM_SEPOLIA", "USDC", "0xold", "20260914000000")
        every { depositAddresses.find(logical.accountId, "ETHEREUM_SEPOLIA", "USDC") } returns existing

        assertThat(service.createDepositAddress(logical.accountId, "ETHEREUM_SEPOLIA", "USDC")).isEqualTo(existing)
        verify(exactly = 0) { provisioning.provisionedWallet(any(), any()) }
    }

    @Test
    fun `지갑 서비스의 보류·충돌은 그대로 전파되고 주소를 저장하지 않는다`() {
        every { provisioning.provisionedWallet(any(), 7) } throws ProvisioningPendingException("intent", "INCOMPLETE_SCAN", 7)
        assertThatThrownBy { service.createDepositAddress(logical.accountId, "ETHEREUM_SEPOLIA", "USDC") }
            .isInstanceOfSatisfying(ProvisioningPendingException::class.java) { assertThat(it.retryAfterSeconds).isEqualTo(7) }

        every { provisioning.provisionedWallet(any(), 7) } throws ConflictException("networkWallet", "intent")
        assertThatThrownBy { service.createDepositAddress(logical.accountId, "ETHEREUM_SEPOLIA", "USDC") }
            .isInstanceOf(ConflictException::class.java)
        verify(exactly = 0) { depositAddresses.insert(any()) }
    }

    @Test
    fun `수신 주소 모델 미확인·벤더 network 매핑 없음·자산 매핑 없음은 지갑 의도 예약 전에 지원하지 않는 자산으로 거절한다`() {
        assertThatThrownBy { service.createDepositAddress(logical.accountId, "SOLANA_DEVNET", "USDC") }
            .isInstanceOf(AssetNotSupportedException::class.java)
        assertThatThrownBy { service.createDepositAddress(logical.accountId, "UNMAPPED_NET", "USDC") }
            .isInstanceOf(AssetNotSupportedException::class.java)
        assertThatThrownBy { service.createDepositAddress(logical.accountId, "TRON", "USDC") }
            .isInstanceOf(AssetNotSupportedException::class.java)
        verify(exactly = 0) { provisioning.provisionedWallet(any(), any()) }
    }

    @Test
    fun `VAULT 계정과 없는 계정은 주소를 발급하지 않는다`() {
        val vault = Account("acct_vault", AccountType.CUSTOMER, "ref-v", "77", "20260915000000")
        every { accounts.requiredAccount("acct_vault") } returns vault

        assertThatThrownBy {
            service.createDepositAddress(
                "acct_vault",
                "ETHEREUM_SEPOLIA",
                "USDC",
            )
        }.isInstanceOf(ConflictException::class.java)
        assertThatThrownBy { service.createDepositAddress("acct_missing", "ETHEREUM_SEPOLIA", "USDC") }
            .isInstanceOf(AccountNotFoundException::class.java)
        verify(exactly = 0) { provisioning.provisionedWallet(any(), any()) }
    }

    @Test
    fun `여러 네트워크 발급은 미지원이 섞이면 아무것도 발급하지 않고 그 외에는 네트워크별 보류·충돌·성공을 항목으로 돌려준다`() {
        assertThatThrownBy { service.createDepositAddresses(logical.accountId, "USDC", listOf("ETHEREUM_SEPOLIA", "SOLANA_DEVNET")) }
            .isInstanceOf(AssetNotSupportedException::class.java)
        verify(exactly = 0) { provisioning.provisionedWallet(any(), any()) }

        every { provisioning.provisionedWallet(any(), 7) } throws ProvisioningPendingException("intent", "NOT_OBSERVED", 7)
        val outcomes = service.createDepositAddresses(logical.accountId, "USDC", listOf("ETHEREUM_SEPOLIA", "ETHEREUM_SEPOLIA"))

        assertThat(outcomes).hasSize(2)
        assertThat(outcomes.map { it.failure }).allSatisfy { assertThat(it).isInstanceOf(ProvisioningPendingException::class.java) }
        verify(exactly = 1) { provisioning.provisionedWallet(any(), 7) }
    }

    @Test
    fun `같은 네트워크의 다른 토큰은 같은 지갑 의도에 합류해 같은 주소를 받는다`() {
        every { provisioning.provisionedWallet(any(), 7) } returns wallet("wa-1", "0xabc")
        every { depositAddresses.insert(any()) } answers { firstArg() }

        val usdc = service.createDepositAddress(logical.accountId, "ETHEREUM_SEPOLIA", "USDC")
        val krwk = service.createDepositAddress(logical.accountId, "ETHEREUM_SEPOLIA", "KRWK")

        assertThat(usdc.address).isEqualTo(krwk.address)
        val seeds = mutableListOf<NetworkWalletCreationSeed>()
        verify(exactly = 2) { provisioning.provisionedWallet(capture(seeds), 7) }
        assertThat(seeds.map { it.request.scope }.toSet()).containsExactly(scope)
        // 재요청·다른 토큰이 원장의 기존 의도에 합류하도록 seed는 scope에서 결정적이다.
        assertThat(seeds.toSet()).hasSize(1)
    }

    @Test
    fun `주소 저장 경합은 먼저 저장된 값을 돌려주고 검증된 지갑에 주소가 없으면 저장하지 않는다`() {
        every { provisioning.provisionedWallet(any(), 7) } returns wallet("wa-1", "0xabc")
        val winner = DepositAddress(logical.accountId, "ETHEREUM_SEPOLIA", "USDC", "0xabc", "20260915000000")
        every { depositAddresses.insert(any()) } throws ConflictException("depositAddress", "race")
        every { depositAddresses.find(logical.accountId, "ETHEREUM_SEPOLIA", "USDC") } returnsMany listOf(null, winner)
        assertThat(service.createDepositAddress(logical.accountId, "ETHEREUM_SEPOLIA", "USDC")).isEqualTo(winner)

        every { depositAddresses.find(any(), any(), any()) } returns null
        every { provisioning.provisionedWallet(any(), 7) } returns wallet("wa-1", null)
        assertThatThrownBy { service.createDepositAddress(logical.accountId, "ETHEREUM_SEPOLIA", "USDC") }
            .isInstanceOf(IllegalStateException::class.java)
        verify(exactly = 1) { depositAddresses.insert(any()) }
    }

    @Test
    fun `잔액 조회는 계약 확정 전이라 벤더 호출 없이 422로 거절하고 주소 조회는 저장된 매핑만 돌려준다`() {
        assertThatThrownBy { service.balancesOf(logical.accountId, null, null) }.isInstanceOf(UnprocessableRequestException::class.java)
        assertThatThrownBy { service.balancesOf("acct_missing", null, null) }.isInstanceOf(AccountNotFoundException::class.java)
        every { depositAddresses.findAll(logical.accountId, "USDC", null) } returns emptyList()
        assertThat(service.depositAddressesOf(logical.accountId, "USDC", null)).isEmpty()
    }

    @Test
    fun `Dfns가 아닌 원천으로는 조립되지 않는다`() {
        val fireblocks = ProviderOrigin("fb", "fireblocks", "fireblocks", "inst", "org", "TESTNET")
        assertThatThrownBy {
            DfnsAccountService(
                logicalAccounts,
                accounts,
                assetMappings,
                depositAddresses,
                provisioning,
                submissions,
                policy,
                fireblocks,
                clock,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun mapping(
        network: String,
        symbol: String = "USDC",
    ) = VendorAssetMapping(network, symbol, "vendor-$network-$symbol", "0xcontract", "20260915000000", "SYSTEM", "9999")

    private fun wallet(
        id: String,
        address: String?,
    ) = NetworkWalletObservation(ORIGIN, scope.network, id, "corr", NetworkWalletOwnership.ORGANIZATION, address)

    private fun sha256(value: String) =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private val ORIGIN = ProviderOrigin("test-dfns-origin", "dfns", "dfns", "test-dfns-platform", "test-dfns-organization", "TESTNET")
    }
}
