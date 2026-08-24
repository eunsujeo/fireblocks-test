package com.whatto.bcm.infra.persistence.admin

import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminContractEvidence
import com.whatto.bcm.domain.admin.AdminContractRepository
import com.whatto.bcm.domain.admin.AdminContractVersion
import com.whatto.bcm.domain.admin.AdminRole
import com.whatto.bcm.domain.admin.ContractEvidenceCandidate
import com.whatto.bcm.domain.admin.ContractEvidenceEvaluator
import com.whatto.bcm.domain.admin.ContractEvidenceStatus
import com.whatto.bcm.domain.admin.ContractExpectedState
import com.whatto.bcm.domain.admin.ExternalControlEvidence
import com.whatto.bcm.domain.admin.RpcObservation
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import java.math.BigInteger
import java.security.MessageDigest
import java.time.Instant

@DataJdbcTest
@Import(AdminContractJdbcAdapter::class)
class AdminContractPersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var contracts: AdminContractRepository

    private val actor = AdminActor("610001", "0001", setOf(AdminRole.BCM_OPERATOR))
    private val now = Instant.parse("2026-08-17T12:00:00Z")

    @Test
    fun `불변 컨트랙트 버전과 독립 2 RPC VALID evidence를 저장한다`() {
        val version = contracts.insertVersion(version())
        val binding = contracts.initializeBinding(version, actor, now)
        val candidate = evidenceCandidate(version)
        val evidence =
            AdminContractEvidence(
                evidenceId = "contract-evidence-1",
                contractVersionId = version.versionId,
                snapshotHash = hash("contract-snapshot-1"),
                candidate = candidate,
                evaluation = ContractEvidenceEvaluator.evaluate(candidate, now.plusSeconds(60)),
                documentEvidence = "{\"audit\":\"doc://audit/1\"}",
                documentEvidenceHash = hash("document-evidence-1"),
                firstEndpointId = "RPC_GATEWAY_A",
                secondEndpointId = "RPC_GATEWAY_B",
                registeredBy = actor,
            )

        contracts.insertEvidence(evidence)
        val loaded = contracts.findLatestEvidence(version.versionId)

        assertThat(binding.revision).isZero()
        assertThat(binding.activeVersionId).isNull()
        assertThat(loaded?.contractVersionId).isEqualTo(version.versionId)
        assertThat(loaded?.evaluation?.status).isEqualTo(ContractEvidenceStatus.VALID)
        assertThat(loaded?.candidate?.first?.endpointId).isEqualTo("RPC_GATEWAY_A")
        assertThat(loaded?.candidate?.second?.endpointId).isEqualTo("RPC_GATEWAY_B")
        assertThat(loaded?.candidate?.expected?.pinnedBlockNumber).isEqualTo(BigInteger.valueOf(1234))
    }

    private fun version() =
        AdminContractVersion(
            versionId = "contract-version-1",
            scopeId = "BASE:SWEEP",
            network = "BASE",
            use = "SWEEP",
            version = "1.0.0",
            address = "0x1234567890abcdef",
            releaseCommit = "release-commit-1",
            artifactHash = hash("artifact-1"),
            abiHash = hash("abi-1"),
            runtimeCodeHash = hash("runtime-1"),
            deploymentTransactionHash = "0xdeployment",
            deploymentBlockNumber = BigInteger.valueOf(1234),
            immutableValues = "{\"destination\":\"0xomnibus\"}",
            immutableHash = hash("immutable-1"),
            ceilingSnapshot = "{\"maximumBatchSize\":20}",
            ceilingHash = hash("ceiling-1"),
            releaseUri = "repo://contracts/releases/1.0.0",
            registeredAt = now,
            registeredBy = actor,
        )

    private fun evidenceCandidate(version: AdminContractVersion): ContractEvidenceCandidate {
        val expected =
            ContractExpectedState(
                chainId = 8453,
                codeHash = version.runtimeCodeHash,
                immutableHash = version.immutableHash,
                pinnedBlockNumber = version.deploymentBlockNumber,
            )
        return ContractEvidenceCandidate(
            expected = expected,
            first = observation("RPC_GATEWAY_A", expected),
            second = observation("RPC_GATEWAY_B", expected),
            controls = ExternalControlEvidence.allPassed(),
            observedAt = now,
            validUntil = Instant.parse("2099-12-31T23:59:59Z"),
        )
    }

    private fun observation(
        endpoint: String,
        expected: ContractExpectedState,
    ) = RpcObservation(
        endpointId = endpoint,
        chainId = expected.chainId,
        codeHash = expected.codeHash,
        immutableHash = expected.immutableHash,
        blockNumber = expected.pinnedBlockNumber,
        observedAt = now,
    )

    private fun hash(seed: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(seed.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
