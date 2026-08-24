package com.whatto.bcm.app.bat.sweep

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminChangeRequest
import com.whatto.bcm.domain.admin.AdminContractBinding
import com.whatto.bcm.domain.admin.AdminContractRepository
import com.whatto.bcm.domain.admin.AdminDecisionRecord
import com.whatto.bcm.domain.admin.AdminPolicyRepository
import com.whatto.bcm.domain.admin.AdminRole
import com.whatto.bcm.domain.admin.AllowanceRevocationEvent
import com.whatto.bcm.domain.admin.AllowanceRevocationExecution
import com.whatto.bcm.domain.admin.AllowanceRevocationExecutionStatus
import com.whatto.bcm.domain.admin.AllowanceRevocationLifecycle
import com.whatto.bcm.domain.admin.AllowanceRevocationRepository
import com.whatto.bcm.domain.admin.AllowanceRevocationTarget
import com.whatto.bcm.domain.admin.AllowanceRevocationView
import com.whatto.bcm.domain.admin.ChangeDecision
import com.whatto.bcm.domain.admin.ChangeRisk
import com.whatto.bcm.domain.admin.ChangeTargetType
import com.whatto.bcm.domain.admin.PolicyChangeRequest
import com.whatto.bcm.domain.admin.PolicyDecision
import com.whatto.bcm.domain.submission.SubmissionRecordRepository
import com.whatto.bcm.domain.sweep.Erc20ContractPort
import com.whatto.bcm.domain.sweep.SweepAllowanceObservation
import com.whatto.bcm.domain.sweep.SweepAuthorization
import com.whatto.bcm.domain.sweep.SweepAuthorizationKey
import com.whatto.bcm.domain.sweep.SweepAuthorizationRepository
import com.whatto.bcm.domain.sweep.SweepAuthorizationStatus
import com.whatto.bcm.support.submission.SubmissionRequestHashes
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.lang.reflect.Proxy
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

private typealias InterfaceAnswer = (String, Array<out Any?>?) -> Any?

class AllowanceRevocationExecutionServiceTest {
    private lateinit var revocations: InMemoryRevocations
    private lateinit var authorizations: InMemoryAuthorizations
    private lateinit var erc20: FakeErc20
    private lateinit var service: AllowanceRevocationExecutionService

    @BeforeEach
    fun setUp() {
        revocations = InMemoryRevocations(execution(), listOf(target()))
        authorizations = InMemoryAuthorizations(authorization())
        erc20 = FakeErc20()
        val policies =
            proxy<AdminPolicyRepository> { name, _ ->
                when (name) {
                    "findChangeRequest" -> request()
                    "findDecisions" -> listOf(approval())
                    else -> error("not used: $name")
                }
            }
        val contracts =
            proxy<AdminContractRepository> { name, _ ->
                when (name) {
                    "lockBinding" -> AdminContractBinding("BASE:SWEEP", "BASE", "SWEEP", "contract-v1", 3, null, "c".repeat(64))
                    else -> error("not used: $name")
                }
            }
        val submissionRecords =
            proxy<SubmissionRecordRepository> { name, _ ->
                when (name) {
                    "findByExternalTransactionId" -> null
                    else -> error("not used: $name")
                }
            }
        val generatedIds = ArrayDeque(listOf("action-1", "correlation-1"))
        val ids =
            proxy<AllowanceRevocationIdGenerator> { name, _ ->
                if (name == "nextId") generatedIds.removeFirst() else error("not used: $name")
            }
        service =
            AllowanceRevocationExecutionService(
                revocations,
                policies,
                contracts,
                authorizations,
                erc20,
                submissionRecords,
                FakeContractCalls(),
                DirectTransactionRunner,
                ids,
                CLOCK,
            )
    }

    @Test
    fun `승인 뒤 전 항목을 예약하고 제출 후 온체인 0을 확인해야 완료한다`() {
        erc20.observations += listOf(SweepAllowanceObservation("25", 6), SweepAllowanceObservation("0", 6))

        val reserved = service.reserve(ReserveAllowanceRevocationCommand("request-1", "execute-1", OPERATOR))
        assertThat(reserved.status).isEqualTo(AllowanceRevocationExecutionStatus.IN_PROGRESS)

        val submitted = service.processItem(ProcessAllowanceRevocationItemCommand("execution-1", 1, OPERATOR))
        assertThat(submitted.status).isEqualTo(AllowanceRevocationExecutionStatus.IN_PROGRESS)
        assertThat(submitted.events.map { it.status.name }).containsExactly("RESERVED", "SUBMIT_INTENT", "SUBMITTED")

        val completed = service.processItem(ProcessAllowanceRevocationItemCommand("execution-1", 1, OPERATOR))
        assertThat(completed.status).isEqualTo(AllowanceRevocationExecutionStatus.COMPLETED)
        val lastStatus =
            completed.events
                .last()
                .status
                .name
        assertThat(lastStatus).isEqualTo("ZERO_CONFIRMED")
        assertThat(authorizations.row.status).isEqualTo(SweepAuthorizationStatus.REVOKED)
        assertThat(authorizations.row.observedAllowance).isEqualTo("0")
    }

    @Test
    fun `제출 뒤 allowance가 남아 있으면 최신 관찰 projection을 갱신한다`() {
        erc20.observations += listOf(SweepAllowanceObservation("25", 6), SweepAllowanceObservation("20", 6))
        service.reserve(ReserveAllowanceRevocationCommand("request-1", "execute-1", OPERATOR))
        service.processItem(ProcessAllowanceRevocationItemCommand("execution-1", 1, OPERATOR))

        val pending = service.processItem(ProcessAllowanceRevocationItemCommand("execution-1", 1, OPERATOR))

        assertThat(pending.status).isEqualTo(AllowanceRevocationExecutionStatus.IN_PROGRESS)
        assertThat(authorizations.row.observedAllowance).isEqualTo("20")
        assertThat(authorizations.row.status).isEqualTo(SweepAuthorizationStatus.REVOKING)
        assertThat(authorizations.row.lastCheckedAt).isEqualTo("20260818010000")
    }

    private fun execution() =
        AllowanceRevocationExecution(
            "execution-1",
            "contract-v1",
            3,
            "BASE",
            SWEEP_CONTRACT,
            "a".repeat(64),
            1,
            "revoke-1",
            NOW,
            OPERATOR,
        )

    private fun target() =
        AllowanceRevocationTarget(
            "execution-1",
            1,
            "account-1",
            "BASE",
            "USDC",
            SWEEP_CONTRACT,
            "vault-1",
            OWNER,
            TOKEN,
            BigDecimal("25"),
            "arv-1",
            SubmissionRequestHashes.contractCallV1("account-1", TOKEN, "BASE", "USDC", "0", CALL_DATA).requestHash,
        )

    private fun request() =
        AdminChangeRequest(
            PolicyChangeRequest("request-1", OPERATOR, ChangeRisk.FUND, "a".repeat(64), 3, "execution-1", NOW.plusSeconds(600)),
            ChangeTargetType.ALLOWANCE_REVOKE,
            "BASE:SWEEP",
            null,
            null,
            "{}",
            "b".repeat(64),
            "{}",
            "c".repeat(64),
            "사고 대응",
            "INC-1063",
            "revoke-1",
            AdminRole.BCM_OPERATOR,
            NOW,
        )

    private fun approval() =
        AdminDecisionRecord(
            PolicyDecision("request-1", APPROVER, ChangeDecision.APPROVE, "a".repeat(64), NOW),
            null,
        )

    private fun authorization() =
        SweepAuthorization(
            SweepAuthorizationKey("account-1", "BASE", "USDC", SWEEP_CONTRACT),
            "100",
            "25",
            SweepAuthorizationStatus.ACTIVE,
            null,
            null,
            "20260818010000",
        )

    private class InMemoryRevocations(
        private val execution: AllowanceRevocationExecution,
        private val targets: List<AllowanceRevocationTarget>,
    ) : AllowanceRevocationRepository {
        private val events = mutableListOf<AllowanceRevocationEvent>()

        override fun insertExecution(
            execution: AllowanceRevocationExecution,
            targets: List<AllowanceRevocationTarget>,
        ) = error("not used")

        override fun findExecution(executionId: String): AllowanceRevocationView? =
            execution.takeIf { it.executionId == executionId }?.let {
                AllowanceRevocationView(
                    it,
                    targets,
                    events.toList(),
                    AllowanceRevocationLifecycle.executionStatus(it.itemCount, events),
                )
            }

        override fun findExecutionByIdempotency(
            employeeNo: String,
            idempotencyKey: String,
        ) = error("not used")

        override fun insertExecutionIntent(
            actionId: String,
            correlationId: String,
            request: AdminChangeRequest,
            idempotencyKey: String,
            requestHash: String,
            expectedState: String,
            expectedStateHash: String,
            actor: AdminActor,
            now: Instant,
        ) = Unit

        override fun appendEvent(event: AllowanceRevocationEvent): AllowanceRevocationEvent {
            val target = targets.single { it.itemSequence == event.itemSequence }
            AllowanceRevocationLifecycle.validateAppend(target, events, event)
            events += event
            return event
        }
    }

    private class InMemoryAuthorizations(
        initial: SweepAuthorization,
    ) : SweepAuthorizationRepository {
        var row = initial
            private set

        override fun insert(authorization: SweepAuthorization) = error("not used")

        override fun findByKey(key: SweepAuthorizationKey) = row.takeIf { it.key == key }

        override fun findByKeyForUpdate(key: SweepAuthorizationKey) = findByKey(key)

        override fun findByNetworkAndContract(
            network: String,
            sweepContractAddress: String,
        ) = listOf(row)

        override fun update(authorization: SweepAuthorization): SweepAuthorization = authorization.also { row = it }
    }

    private class FakeContractCalls : SweepContractCallSubmitter {
        override fun submit(command: SweepContractCallCommand) = SweepContractCallResult("vendor-1")

        override fun submit(
            command: SweepContractCallCommand,
            recordIntent: () -> Unit,
        ): SweepContractCallResult {
            recordIntent()
            return SweepContractCallResult("vendor-1")
        }
    }

    private class FakeErc20 : Erc20ContractPort {
        val observations = ArrayDeque<SweepAllowanceObservation>()

        override fun decimals(
            network: String,
            tokenContractAddress: String,
        ) = 6

        override fun allowance(
            network: String,
            tokenContractAddress: String,
            ownerAddress: String,
            spenderAddress: String,
        ): SweepAllowanceObservation = observations.removeFirst()

        override fun approvalCallData(
            spenderAddress: String,
            amount: String,
            decimals: Int,
        ) = CALL_DATA
    }

    private object DirectTransactionRunner : TransactionRunner {
        override fun <T> run(block: () -> T): T = block()
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> proxy(crossinline answer: InterfaceAnswer): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            answer(method.name, args)
        } as T

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-18T01:00:00Z")
        val CLOCK: Clock = Clock.fixed(NOW, ZoneOffset.UTC)
        val OPERATOR = AdminActor("830001", "0001", setOf(AdminRole.BCM_OPERATOR))
        val APPROVER = AdminActor("830002", "0002", setOf(AdminRole.BCM_APPROVER))
        const val SWEEP_CONTRACT = "0x0000000000000000000000000000000000000001"
        const val OWNER = "0x0000000000000000000000000000000000000002"
        const val TOKEN = "0x0000000000000000000000000000000000000003"
        const val CALL_DATA =
            "0x095ea7b30000000000000000000000000000000000000000000000000000000000000001" +
                "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
