package com.whatto.bcm.app.bat.sweep

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountRepository
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.account.DepositAddress
import com.whatto.bcm.domain.account.DepositAddressRepository
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.asset.VendorAssetMappingRepository
import com.whatto.bcm.domain.sweep.Erc20ContractPort
import com.whatto.bcm.domain.sweep.SweepAllowanceObservation
import com.whatto.bcm.domain.sweep.SweepAuthorization
import com.whatto.bcm.domain.sweep.SweepAuthorizationKey
import com.whatto.bcm.domain.sweep.SweepAuthorizationRepository
import com.whatto.bcm.domain.sweep.SweepAuthorizationStatus
import com.whatto.bcm.domain.sweep.SweepTarget
import com.whatto.bcm.domain.sweep.SweepTargetKey
import com.whatto.bcm.domain.sweep.SweepTargetRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SweepAllowancePreparationServiceTest {
    @Test
    fun `정상 승인 게이트가 닫혀 있으면 온체인 조회도 하지 않는다`() {
        val erc20 = FakeErc20(SweepAllowanceObservation("0", 6))
        val service = service(erc20 = erc20, properties = properties(normalApprovalEnabled = false))

        assertThatThrownBy { service.prepare(candidate()) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("normal sweep approval gate")
        assertThat(erc20.allowanceCalls).isZero()
    }

    @Test
    fun `allowance 0이면 cap 승인을 제출하고 온체인 재관측 뒤에만 ACTIVE가 된다`() {
        val authorizations = FakeSweepAuthorizations()
        val erc20 = FakeErc20(SweepAllowanceObservation("0", 6), SweepAllowanceObservation("100", 6))
        val calls = FakeContractCalls()
        val service = service(authorizations = authorizations, erc20 = erc20, contractCalls = calls)

        val pending = service.prepare(candidate())

        assertThat(pending).isEqualTo(SweepAllowancePreparationResult.Pending("swa-1", "vendor-swa-1"))
        assertThat(calls.commands.single().semanticAmount).isEqualTo("100")
        assertThat(authorizations.required().status).isEqualTo(SweepAuthorizationStatus.APPROVING)

        assertThat(service.prepare(candidate())).isEqualTo(SweepAllowancePreparationResult.Ready)
        assertThat(authorizations.required().status).isEqualTo(SweepAuthorizationStatus.ACTIVE)
        assertThat(calls.commands).hasSize(1)
    }

    @Test
    fun `부족한 기존 allowance는 새 cap 전에 approve 0으로 먼저 회수한다`() {
        val authorizations = FakeSweepAuthorizations()
        val calls = FakeContractCalls()
        val service =
            service(
                authorizations = authorizations,
                erc20 = FakeErc20(SweepAllowanceObservation("5", 6)),
                contractCalls = calls,
            )

        val result = service.prepare(candidate(amount = "10"))

        assertThat(result).isEqualTo(SweepAllowancePreparationResult.Pending("swa-1", "vendor-swa-1"))
        assertThat(calls.commands.single().semanticAmount).isEqualTo("0")
        assertThat(authorizations.required().status).isEqualTo(SweepAuthorizationStatus.REVOKING)
    }

    @Test
    fun `운영 cap이 바뀌면 기존 allowance를 0으로 확인한 뒤 새 cap을 승인한다`() {
        val authorizations = FakeSweepAuthorizations()
        authorizations.insert(
            SweepAuthorization(
                SweepAuthorizationKey(ACCOUNT_ID, NETWORK, SYMBOL, SWEEP_CONTRACT),
                allowanceCap = "50",
                observedAllowance = "50",
                status = SweepAuthorizationStatus.ACTIVE,
                approvalExternalTransactionId = "swa-old",
                approvalVendorTransactionId = "vendor-old",
                lastCheckedAt = "20260811230000",
            ),
        )
        val calls = FakeContractCalls()
        val service =
            service(
                authorizations = authorizations,
                erc20 = FakeErc20(SweepAllowanceObservation("50", 6), SweepAllowanceObservation("0.0", 6)),
                contractCalls = calls,
            )

        assertThat(service.prepare(candidate()))
            .isEqualTo(SweepAllowancePreparationResult.Pending("swa-1", "vendor-swa-1"))
        assertThat(authorizations.required().allowanceCap).isEqualTo("100")
        assertThat(authorizations.required().status).isEqualTo(SweepAuthorizationStatus.REVOKING)

        assertThat(service.prepare(candidate()))
            .isEqualTo(SweepAllowancePreparationResult.Pending("swa-2", "vendor-swa-2"))
        assertThat(calls.commands.map { it.semanticAmount }).containsExactly("0", "100")
        assertThat(authorizations.required().status).isEqualTo(SweepAuthorizationStatus.APPROVING)
    }

    @Test
    fun `활성 배치 항목이 있으면 allowance 변경을 제출하지 않는다`() {
        val authorizations = FakeSweepAuthorizations()
        val calls = FakeContractCalls()
        val targets = FakeAllowanceTargets(activeExecutionId = "swx-active", activeItemSequence = 3)
        val service =
            service(
                authorizations = authorizations,
                targets = targets,
                erc20 = FakeErc20(SweepAllowanceObservation("5", 6)),
                contractCalls = calls,
            )

        assertThat(service.prepare(candidate()))
            .isEqualTo(SweepAllowancePreparationResult.BlockedByActiveExecution("swx-active", 3))
        assertThat(calls.commands).isEmpty()
        assertThat(authorizations.required().status).isEqualTo(SweepAuthorizationStatus.ACTIVE)
    }

    @Test
    fun `긴급 회수는 정상 승인과 별도 게이트로 approve 0을 제출하고 0 재관측 뒤 REVOKED가 된다`() {
        val authorizations = FakeSweepAuthorizations()
        val erc20 = FakeErc20(SweepAllowanceObservation("100", 6), SweepAllowanceObservation("0", 6))
        val calls = FakeContractCalls()
        val service =
            service(
                authorizations = authorizations,
                erc20 = erc20,
                contractCalls = calls,
                properties = properties(normalApprovalEnabled = false, emergencyRevocationEnabled = true),
            )
        val key = SweepAuthorizationKey(ACCOUNT_ID, NETWORK, SYMBOL, SWEEP_CONTRACT)

        assertThat(service.revoke(key))
            .isEqualTo(SweepAllowancePreparationResult.Pending("swa-1", "vendor-swa-1"))
        assertThat(calls.commands.single().semanticAmount).isEqualTo("0")
        assertThat(authorizations.required().status).isEqualTo(SweepAuthorizationStatus.REVOKING)

        assertThat(service.revoke(key)).isEqualTo(SweepAllowancePreparationResult.Revoked)
        assertThat(authorizations.required().status).isEqualTo(SweepAuthorizationStatus.REVOKED)
        assertThat(calls.commands).hasSize(1)
    }

    private fun service(
        authorizations: FakeSweepAuthorizations = FakeSweepAuthorizations(),
        targets: FakeAllowanceTargets = FakeAllowanceTargets(),
        erc20: FakeErc20 = FakeErc20(SweepAllowanceObservation("100", 6)),
        contractCalls: FakeContractCalls = FakeContractCalls(),
        properties: SweepProperties = properties(),
    ) = SweepAllowancePreparationService(
        authorizations,
        targets,
        FakeAllowanceAccounts(),
        FakeDepositAddresses(),
        FakeAllowanceMappings(),
        erc20,
        contractCalls,
        AllowanceImmediateTransactionRunner,
        SequenceApprovalExternalIds(),
        Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC),
        properties,
    )

    private fun properties(
        normalApprovalEnabled: Boolean = true,
        emergencyRevocationEnabled: Boolean = true,
    ) = SweepProperties(
        thresholds = listOf(SweepAssetThreshold(NETWORK, SYMBOL, "10", "100")),
        contracts = listOf(SweepNetworkContract(NETWORK, SWEEP_CONTRACT)),
        security =
            SweepSecurityProperties(
                normalApprovalEnabled = normalApprovalEnabled,
                emergencyRevocationEnabled = emergencyRevocationEnabled,
                tapApprovalPolicyVerified = true,
                tapRevocationPolicyVerified = true,
                callbackVerified = true,
                universalGaslessVerified = true,
            ),
    )

    private fun candidate(amount: String = "10") =
        SweepCandidate(
            target = SweepTarget(ACCOUNT_ID, NETWORK, SYMBOL, "20260812090000", null, null, 0, null),
            sourceVaultId = VAULT_ID,
            omnibusAccountId = "omnibus",
            omnibusVaultId = "vault-omnibus",
            vendorAssetId = "USDC_ERC20",
            amount = amount,
        )

    private companion object {
        const val ACCOUNT_ID = "customer-1"
        const val VAULT_ID = "vault-customer-1"
        const val NETWORK = "ETHEREUM"
        const val SYMBOL = "USDC"
        const val OWNER_ADDRESS = "0x1111111111111111111111111111111111111111"
        const val TOKEN_CONTRACT = "0x2222222222222222222222222222222222222222"
        const val SWEEP_CONTRACT = "0x3333333333333333333333333333333333333333"
    }
}

private class FakeSweepAuthorizations : SweepAuthorizationRepository {
    private val rows = linkedMapOf<SweepAuthorizationKey, SweepAuthorization>()

    override fun insert(authorization: SweepAuthorization): SweepAuthorization {
        check(rows.putIfAbsent(authorization.key, authorization) == null)
        return authorization
    }

    override fun findByKey(key: SweepAuthorizationKey): SweepAuthorization? = rows[key]

    override fun findByKeyForUpdate(key: SweepAuthorizationKey): SweepAuthorization? = rows[key]

    override fun update(authorization: SweepAuthorization): SweepAuthorization {
        check(rows.containsKey(authorization.key))
        rows[authorization.key] = authorization
        return authorization
    }

    fun required(): SweepAuthorization = rows.values.single()
}

private class FakeAllowanceTargets(
    activeExecutionId: String? = null,
    activeItemSequence: Int? = null,
) : SweepTargetRepository {
    private val target =
        SweepTarget(
            "customer-1",
            "ETHEREUM",
            "USDC",
            "20260812090000",
            activeExecutionId,
            activeItemSequence,
            0,
            null,
        )

    override fun insertIfAbsent(target: SweepTarget): Boolean = error("not used")

    override fun findByKey(key: SweepTargetKey): SweepTarget? = target.takeIf { it.key == key }

    override fun findByKeyForUpdate(key: SweepTargetKey): SweepTarget? = findByKey(key)

    override fun findPending(limit: Int): List<SweepTarget> = error("not used")

    override fun findPendingForUpdate(key: SweepTargetKey): SweepTarget? = error("not used")

    override fun releaseClaim(
        key: SweepTargetKey,
        executionId: String,
        itemSequence: Int,
    ): Boolean = error("not used")

    override fun deleteClaim(
        key: SweepTargetKey,
        executionId: String,
        itemSequence: Int,
    ): Boolean = error("not used")

    override fun deletePending(key: SweepTargetKey): Boolean = error("not used")
}

private class FakeAllowanceAccounts : AccountRepository {
    private val account = Account("customer-1", AccountType.CUSTOMER, "ref-customer-1", "vault-customer-1", "20260812090000")

    override fun insert(account: Account): Account = error("not used")

    override fun findByTypeAndRef(
        accountType: AccountType,
        ref: String,
    ): Account? = error("not used")

    override fun findByAccountId(accountId: String): Account? = account.takeIf { it.accountId == accountId }
}

private class FakeDepositAddresses : DepositAddressRepository {
    private val address =
        DepositAddress(
            "customer-1",
            "ETHEREUM",
            "USDC",
            "0x1111111111111111111111111111111111111111",
            "20260812090000",
        )

    override fun insert(depositAddress: DepositAddress): DepositAddress = error("not used")

    override fun find(
        accountId: String,
        network: String,
        symbol: String,
    ): DepositAddress? = address.takeIf { it.accountId == accountId && it.network == network && it.symbol == symbol }

    override fun findAll(
        accountId: String,
        symbol: String?,
        network: String?,
    ): List<DepositAddress> = error("not used")

    override fun findByAddress(
        address: String,
        network: String,
    ): DepositAddress? = error("not used")

    override fun existsByAsset(
        network: String,
        symbol: String,
    ): Boolean = error("not used")
}

private class FakeAllowanceMappings : VendorAssetMappingRepository {
    private val mapping =
        VendorAssetMapping(
            "ETHEREUM",
            "USDC",
            "USDC_ERC20",
            "0x2222222222222222222222222222222222222222",
            "20260812090000",
            "SYSTEM",
            "9999",
        )

    override fun find(
        network: String,
        symbol: String,
    ): VendorAssetMapping? = mapping.takeIf { it.network == network && it.symbol == symbol }

    override fun findByVendorAssetId(vendorAssetId: String): VendorAssetMapping? = error("not used")

    override fun findAll(
        network: String?,
        symbol: String?,
    ): List<VendorAssetMapping> = error("not used")

    override fun existsByNetwork(network: String): Boolean = error("not used")

    override fun insert(mapping: VendorAssetMapping): VendorAssetMapping = error("not used")

    override fun delete(
        network: String,
        symbol: String,
    ) = error("not used")
}

private class FakeErc20(
    vararg observations: SweepAllowanceObservation,
) : Erc20ContractPort {
    private val observations = ArrayDeque(observations.toList())
    var allowanceCalls = 0
        private set

    override fun decimals(
        network: String,
        tokenContractAddress: String,
    ): Int = error("not used")

    override fun allowance(
        network: String,
        tokenContractAddress: String,
        ownerAddress: String,
        spenderAddress: String,
    ): SweepAllowanceObservation {
        allowanceCalls += 1
        return observations.removeFirst()
    }

    override fun approvalCallData(
        spenderAddress: String,
        amount: String,
        decimals: Int,
    ): String = "approve:$spenderAddress:$amount:$decimals"
}

private class FakeContractCalls : SweepContractCallSubmitter {
    val commands = mutableListOf<SweepContractCallCommand>()

    override fun submit(command: SweepContractCallCommand): SweepContractCallResult {
        commands += command
        return SweepContractCallResult("vendor-${command.externalTransactionId}")
    }
}

private class SequenceApprovalExternalIds : SweepApprovalExternalTransactionIdGenerator {
    private var sequence = 0

    override fun nextId(): String = "swa-${++sequence}"
}

private object AllowanceImmediateTransactionRunner : TransactionRunner {
    override fun <T> run(block: () -> T): T = block()
}
