package com.whatto.bcm.app.application.admin

import com.whatto.bcm.app.application.account.AccountQueryService
import com.whatto.bcm.app.application.account.DepositAddressQueryService
import com.whatto.bcm.app.application.asset.VendorAssetMappingQueryService
import com.whatto.bcm.app.application.sweep.SweepAuthorizationService
import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountRepository
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.account.DepositAddress
import com.whatto.bcm.domain.account.DepositAddressRepository
import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminChangeRequest
import com.whatto.bcm.domain.admin.AdminContractBinding
import com.whatto.bcm.domain.admin.AdminContractRepository
import com.whatto.bcm.domain.admin.AdminContractVersion
import com.whatto.bcm.domain.admin.AdminPolicyRepository
import com.whatto.bcm.domain.admin.AdminRole
import com.whatto.bcm.domain.admin.AllowanceRevocationExecution
import com.whatto.bcm.domain.admin.AllowanceRevocationRepository
import com.whatto.bcm.domain.admin.AllowanceRevocationTarget
import com.whatto.bcm.domain.admin.ChangeRisk
import com.whatto.bcm.domain.admin.ChangeTargetType
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.asset.VendorAssetMappingRepository
import com.whatto.bcm.domain.event.EventIdGenerator
import com.whatto.bcm.domain.sweep.Erc20ContractPort
import com.whatto.bcm.domain.sweep.SweepAllowanceObservation
import com.whatto.bcm.domain.sweep.SweepAuthorization
import com.whatto.bcm.domain.sweep.SweepAuthorizationKey
import com.whatto.bcm.domain.sweep.SweepAuthorizationRepository
import com.whatto.bcm.domain.sweep.SweepAuthorizationStatus
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class AllowanceRevocationCommandServiceTest {
    @MockK lateinit var revocations: AllowanceRevocationRepository

    @MockK lateinit var policies: AdminPolicyRepository

    @MockK lateinit var contracts: AdminContractRepository

    @MockK lateinit var authorizations: SweepAuthorizationRepository

    @MockK lateinit var accounts: AccountRepository

    @MockK lateinit var addresses: DepositAddressRepository

    @MockK lateinit var mappings: VendorAssetMappingRepository

    @MockK lateinit var erc20: Erc20ContractPort

    @MockK lateinit var ids: EventIdGenerator

    private lateinit var transactions: TrackingTransactionRunner
    private lateinit var service: AllowanceRevocationCommandService

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)
        transactions = TrackingTransactionRunner()
        service =
            AllowanceRevocationCommandService(
                revocations,
                policies,
                contracts,
                SweepAuthorizationService(authorizations),
                AccountQueryService(accounts),
                DepositAddressQueryService(addresses),
                VendorAssetMappingQueryService(mappings),
                erc20,
                transactions,
                ids,
                CLOCK,
            )
        every { policies.findChangeRequestByIdempotency(OPERATOR.employeeNo, "revoke-1") } returns null
        every { contracts.lockBinding("BASE:SWEEP") } returns binding()
        every { contracts.findVersion("contract-v1") } returns version()
        every { authorizations.findByNetworkAndContract("BASE", SWEEP_CONTRACT) } returns listOf(authorization())
        every { accounts.findByAccountId("account-1") } returns
            Account("account-1", AccountType.CUSTOMER, "customer-1", "vault-1", "20260818010000")
        every { addresses.find("account-1", "BASE", "USDC") } returns
            DepositAddress("account-1", "BASE", "USDC", OWNER, "20260818010000")
        every { mappings.find("BASE", "USDC") } returns
            VendorAssetMapping("BASE", "USDC", "BASE_USDC", TOKEN, "20260818010000", "SYSTEM", "9999")
        every { erc20.allowance("BASE", TOKEN, OWNER, SWEEP_CONTRACT) } answers {
            assertThat(transactions.active).isFalse()
            SweepAllowanceObservation("25", 6)
        }
        every { erc20.approvalCallData(SWEEP_CONTRACT, "0", 6) } returns CALL_DATA
        every { ids.nextId() } returnsMany listOf("execution-1", "external-1", "request-1")
        every { authorizations.findByKeyForUpdate(authorization().key) } returns authorization()
        every { authorizations.update(any()) } answers { firstArg() }
    }

    @Test
    fun `온체인 allowance를 트랜잭션 밖에서 읽고 불변 대상과 FUND 요청을 함께 저장한다`() {
        val execution = slot<AllowanceRevocationExecution>()
        val targets = slot<List<AllowanceRevocationTarget>>()
        val request = slot<AdminChangeRequest>()
        every { revocations.insertExecution(capture(execution), capture(targets)) } answers { firstArg() }
        every { policies.insertChangeRequest(capture(request)) } answers { firstArg() }

        val result = service.request(command())

        assertThat(result.execution.executionId).isEqualTo("execution-1")
        assertThat(targets.captured).hasSize(1)
        assertThat(targets.captured.single().externalTransactionId).isEqualTo("arv-external-1")
        assertThat(targets.captured.single().beforeObservedAllowance).isEqualByComparingTo("25")
        assertThat(request.captured.targetType).isEqualTo(ChangeTargetType.ALLOWANCE_REVOKE)
        assertThat(request.captured.lifecycle.risk).isEqualTo(ChangeRisk.FUND)
        assertThat(request.captured.lifecycle.targetVersionId).isEqualTo("execution-1")
        assertThat(request.captured.lifecycle.targetSnapshotHash).isEqualTo(execution.captured.targetSnapshotHash)
    }

    @Test
    fun `0인 allowance도 관찰 projection을 갱신하되 회수 target에서는 제외한다`() {
        val zero = authorization("account-2", "0", SweepAuthorizationStatus.ACTIVE)
        every { authorizations.findByNetworkAndContract("BASE", SWEEP_CONTRACT) } returns listOf(authorization(), zero)
        every { accounts.findByAccountId("account-2") } returns
            Account("account-2", AccountType.CUSTOMER, "customer-2", "vault-2", "20260818010000")
        every { addresses.find("account-2", "BASE", "USDC") } returns
            DepositAddress("account-2", "BASE", "USDC", OWNER_2, "20260818010000")
        every { erc20.allowance("BASE", TOKEN, OWNER_2, SWEEP_CONTRACT) } returns SweepAllowanceObservation("0", 6)
        every { authorizations.findByKeyForUpdate(zero.key) } returns zero
        every { revocations.insertExecution(any(), any()) } answers { firstArg() }
        every { policies.insertChangeRequest(any()) } answers { firstArg() }

        val result = service.request(command())

        assertThat(result.execution.itemCount).isEqualTo(1)
        verify {
            authorizations.update(
                match { it.key == zero.key && it.observedAllowance == "0" && it.status == SweepAuthorizationStatus.UNAPPROVED },
            )
        }
    }

    private fun command() =
        RequestAllowanceRevocationCommand(
            "BASE",
            "sweep 사고 대응",
            "INC-1063",
            "revoke-1",
            Duration.ofMinutes(10),
            OPERATOR,
        )

    private fun authorization(
        accountId: String = "account-1",
        observedAllowance: String = "20",
        status: SweepAuthorizationStatus = SweepAuthorizationStatus.ACTIVE,
    ) = SweepAuthorization(
        SweepAuthorizationKey(accountId, "BASE", "USDC", SWEEP_CONTRACT),
        "100",
        observedAllowance,
        status,
        null,
        null,
        "20260818010000",
    )

    private fun binding() = AdminContractBinding("BASE:SWEEP", "BASE", "SWEEP", "contract-v1", 3, "evidence-1", "a".repeat(64))

    private fun version() =
        AdminContractVersion(
            "contract-v1",
            "BASE:SWEEP",
            "BASE",
            "SWEEP",
            "1.0.0",
            SWEEP_CONTRACT,
            "commit",
            "a".repeat(64),
            "b".repeat(64),
            "c".repeat(64),
            "0xdeploy",
            BigInteger.ONE,
            "{}",
            "d".repeat(64),
            "{}",
            "e".repeat(64),
            "doc://release",
            NOW,
            OPERATOR,
        )

    private class TrackingTransactionRunner : TransactionRunner {
        var active = false
            private set

        override fun <T> run(block: () -> T): T {
            check(!active)
            active = true
            return try {
                block()
            } finally {
                active = false
            }
        }
    }

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-18T01:00:00Z")
        val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
        val OPERATOR = AdminActor("830001", "0001", setOf(AdminRole.BCM_OPERATOR))
        const val SWEEP_CONTRACT = "0x0000000000000000000000000000000000000001"
        const val OWNER = "0x0000000000000000000000000000000000000002"
        const val OWNER_2 = "0x0000000000000000000000000000000000000004"
        const val TOKEN = "0x0000000000000000000000000000000000000003"
        const val CALL_DATA =
            "0x095ea7b30000000000000000000000000000000000000000000000000000000000000001" +
                "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
