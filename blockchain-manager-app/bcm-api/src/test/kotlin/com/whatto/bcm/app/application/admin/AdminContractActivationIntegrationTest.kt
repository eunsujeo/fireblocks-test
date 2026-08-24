package com.whatto.bcm.app.application.admin

import com.whatto.bcm.app.api.BcmApiApplication
import com.whatto.bcm.app.api.support.IntegrationTestSupport
import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminRole
import com.whatto.bcm.domain.admin.ChangeDecision
import com.whatto.bcm.domain.admin.ChangeTargetNotReady
import com.whatto.bcm.domain.admin.ContractEvidenceCandidate
import com.whatto.bcm.domain.admin.ContractExpectedState
import com.whatto.bcm.domain.admin.ContractVerificationPort
import com.whatto.bcm.domain.admin.ContractVerificationResult
import com.whatto.bcm.domain.admin.ExternalControlEvidence
import com.whatto.bcm.domain.admin.RpcObservation
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Duration

@SpringBootTest(classes = [BcmApiApplication::class])
@Import(AdminContractActivationIntegrationTest.VerificationConfig::class)
class AdminContractActivationIntegrationTest : IntegrationTestSupport() {
    @Autowired
    lateinit var contracts: AdminContractCommandService

    @Autowired
    lateinit var policies: AdminPolicyCommandService

    @Autowired
    lateinit var verification: MutableContractVerificationPort

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun resetVerification() {
        verification.drift = false
    }

    @Test
    fun `강화 정족수와 활성화 직전 2 RPC 재검사를 통과한 컨트랙트만 한 번 활성화한다`() {
        val requester = actor("710001", AdminRole.BCM_OPERATOR)
        val version = register("BASE:SWEEP_ACTIVATION", "BASE", "SWEEP_ACTIVATION", requester)
        contracts.verify(VerifyContractCommand(version.versionId, requester))
        val request = request(version.versionId, "contract-request-activation", requester)
        approve(request.lifecycle.requestId, request.lifecycle.targetSnapshotHash)
        val executor = actor("710004", AdminRole.BCM_OPERATOR)

        val activated =
            contracts.activate(
                ActivateContractChangeCommand(request.lifecycle.requestId, "contract-activate-once", executor),
            )
        val retried =
            contracts.activate(
                ActivateContractChangeCommand(request.lifecycle.requestId, "contract-activate-once", executor),
            )

        assertThat(activated.bindingRevision).isEqualTo(1)
        assertThat(retried).isEqualTo(activated)
        assertThat(
            jdbc.queryForObject(
                "SELECT actv_ctrt_vrsn_id FROM bcm_ctrt_bind_m WHERE ctrt_scope_id = ?",
                String::class.java,
                version.scopeId,
            ),
        ).isEqualTo(version.versionId)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM bcm_adm_actn_l WHERE req_id = ? AND actn_stcd = 'INTENT'",
                Int::class.java,
                request.lifecycle.requestId,
            ),
        ).isEqualTo(1)
    }

    @Test
    fun `승인 뒤 2 RPC code hash가 drift하면 binding과 intent를 남기지 않는다`() {
        val requester = actor("720001", AdminRole.BCM_OPERATOR)
        val version = register("BASE:SWEEP_DRIFT", "BASE", "SWEEP_DRIFT", requester)
        contracts.verify(VerifyContractCommand(version.versionId, requester))
        val request = request(version.versionId, "contract-request-drift", requester)
        approve(request.lifecycle.requestId, request.lifecycle.targetSnapshotHash, employeeOffset = 10)
        verification.drift = true

        assertThatThrownBy {
            contracts.activate(
                ActivateContractChangeCommand(
                    request.lifecycle.requestId,
                    "contract-activate-drift",
                    actor("720004", AdminRole.BCM_OPERATOR),
                ),
            )
        }.isInstanceOf(ChangeTargetNotReady::class.java)

        assertThat(
            jdbc.queryForObject(
                "SELECT actv_ctrt_vrsn_id FROM bcm_ctrt_bind_m WHERE ctrt_scope_id = ?",
                String::class.java,
                version.scopeId,
            ),
        ).isNull()
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM bcm_adm_actn_l WHERE req_id = ?",
                Int::class.java,
                request.lifecycle.requestId,
            ),
        ).isZero()
    }

    private fun register(
        scope: String,
        network: String,
        use: String,
        requester: AdminActor,
    ) = contracts.register(
        RegisterContractCommand(
            scopeId = scope,
            network = network,
            use = use,
            version = "1.0.0",
            address = "0x$scope",
            releaseCommit = "release-$scope",
            artifactHash = hash("artifact-$scope"),
            abiHash = hash("abi-$scope"),
            runtimeCodeHash = hash("runtime-$scope"),
            deploymentTransactionHash = "0xdeployment-$scope",
            deploymentBlockNumber = BigInteger.valueOf(1234),
            immutableValues = "{\"destination\":\"0xomnibus\"}",
            ceilingSnapshot = "{\"maximumBatchSize\":20}",
            releaseUri = "repo://contracts/$scope/1.0.0",
            actor = requester,
        ),
    )

    private fun request(
        versionId: String,
        idempotencyKey: String,
        requester: AdminActor,
    ) = contracts.requestActivation(
        RequestContractActivationCommand(
            versionId = versionId,
            impact = ContractActivationImpact(affectedAccounts = 0, openExecutions = 0, activeAllowances = 0),
            reason = "컨트랙트 활성화 검증",
            workTicket = "SEC-7100",
            idempotencyKey = idempotencyKey,
            actor = requester,
            validFor = Duration.ofHours(3),
        ),
    )

    private fun approve(
        requestId: String,
        snapshotHash: String,
        employeeOffset: Int = 0,
    ) {
        policies.decide(
            DecidePolicyChangeCommand(
                requestId,
                ChangeDecision.APPROVE,
                snapshotHash,
                "일반 승인",
                actor("${710002 + employeeOffset}", AdminRole.BCM_APPROVER),
            ),
        )
        policies.decide(
            DecidePolicyChangeCommand(
                requestId,
                ChangeDecision.APPROVE,
                snapshotHash,
                "보안 승인",
                actor("${710003 + employeeOffset}", AdminRole.BCM_SECURITY_APPROVER),
            ),
        )
    }

    private fun actor(
        employeeNo: String,
        role: AdminRole,
    ) = AdminActor(employeeNo, "0001", setOf(role))

    private fun hash(seed: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(seed.toByteArray())
            .joinToString("") { "%02x".format(it) }

    @TestConfiguration
    class VerificationConfig {
        @Bean
        fun contractVerificationPort() = MutableContractVerificationPort()
    }
}

class MutableContractVerificationPort : ContractVerificationPort {
    @Volatile
    var drift: Boolean = false

    override fun collect(
        version: com.whatto.bcm.domain.admin.AdminContractVersion,
        now: java.time.Instant,
    ): ContractVerificationResult {
        val expected =
            ContractExpectedState(
                chainId = 8453,
                codeHash = version.runtimeCodeHash,
                immutableHash = version.immutableHash,
                pinnedBlockNumber = version.deploymentBlockNumber,
            )
        val secondCodeHash = if (drift) "f".repeat(64) else expected.codeHash
        return ContractVerificationResult(
            candidate =
                ContractEvidenceCandidate(
                    expected = expected,
                    first = observation("RPC_GATEWAY_A", expected.codeHash, expected, now),
                    second = observation("RPC_GATEWAY_B", secondCodeHash, expected, now),
                    controls = ExternalControlEvidence.allPassed(),
                    observedAt = now,
                    validUntil = now.plus(Duration.ofMinutes(5)),
                ),
            firstEndpointId = "RPC_GATEWAY_A",
            secondEndpointId = "RPC_GATEWAY_B",
            documentEvidence = "{\"audit\":\"doc://audit/current\"}",
        )
    }

    private fun observation(
        endpoint: String,
        codeHash: String,
        expected: ContractExpectedState,
        now: java.time.Instant,
    ) = RpcObservation(
        endpointId = endpoint,
        chainId = expected.chainId,
        codeHash = codeHash,
        immutableHash = expected.immutableHash,
        blockNumber = expected.pinnedBlockNumber,
        observedAt = now,
    )
}
