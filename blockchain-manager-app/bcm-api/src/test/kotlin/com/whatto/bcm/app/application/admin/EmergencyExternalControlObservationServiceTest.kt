package com.whatto.bcm.app.application.admin

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminContractRepository
import com.whatto.bcm.domain.admin.AdminContractVersion
import com.whatto.bcm.domain.admin.AdminRole
import com.whatto.bcm.domain.admin.EmergencyContractObservation
import com.whatto.bcm.domain.admin.EmergencyExternalControlCandidate
import com.whatto.bcm.domain.admin.EmergencyExternalControlEvidence
import com.whatto.bcm.domain.admin.EmergencyExternalControlRepository
import com.whatto.bcm.domain.admin.EmergencyExternalControlStatus
import com.whatto.bcm.domain.admin.EmergencyExternalControlVerificationPort
import com.whatto.bcm.domain.admin.TapBatchObservation
import com.whatto.bcm.domain.event.EventIdGenerator
import com.whatto.bcm.domain.exception.ConflictException
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class EmergencyExternalControlObservationServiceTest {
    @MockK
    lateinit var contracts: AdminContractRepository

    @MockK
    lateinit var evidence: EmergencyExternalControlRepository

    @MockK
    lateinit var verification: EmergencyExternalControlVerificationPort

    @MockK
    lateinit var ids: EventIdGenerator

    private lateinit var service: EmergencyExternalControlObservationService
    private lateinit var transactions: TrackingTransactionRunner

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)
        transactions = TrackingTransactionRunner()
        service =
            EmergencyExternalControlObservationService(
                contracts,
                evidence,
                verification,
                transactions,
                ids,
                CLOCK,
            )
        every { evidence.findByIdempotency("830001", "observe-1") } returns null
        every { contracts.findVersion("contract-v1") } returns version()
    }

    @Test
    fun `운영자는 외부 조치를 서명하지 않고 새 관찰 증적을 남긴다`() {
        val inserted = slot<EmergencyExternalControlEvidence>()
        every { verification.collect(any(), NOW) } returns candidate()
        every { ids.nextId() } returns "external-evidence-1"
        every { evidence.insert(capture(inserted)) } answers { firstArg() }

        val result = service.observe(command())

        assertThat(result).isEqualTo(inserted.captured)
        assertThat(result.evaluation.status).isEqualTo(EmergencyExternalControlStatus.CONFIRMED)
        assertThat(result.candidate.expectedOperatorSetHash).isEqualTo(EMPTY_HASH)
        verify(exactly = 1) { verification.collect(version(), NOW) }
    }

    @Test
    fun `외부 관찰은 DB 트랜잭션 밖에서 실행하고 저장만 짧게 묶는다`() {
        var externalCallInsideTransaction: Boolean? = null
        var insertInsideTransaction: Boolean? = null
        every { verification.collect(any(), NOW) } answers {
            externalCallInsideTransaction = transactions.active
            candidate()
        }
        every { ids.nextId() } returns "external-evidence-transaction"
        every { evidence.insert(any()) } answers {
            insertInsideTransaction = transactions.active
            firstArg()
        }

        service.observe(command())

        assertThat(externalCallInsideTransaction).isFalse()
        assertThat(insertInsideTransaction).isTrue()
    }

    @Test
    fun `snapshot hash는 네트워크와 컨트랙트 버전에 바인딩된다`() {
        val inserted = mutableListOf<EmergencyExternalControlEvidence>()
        every { evidence.findByIdempotency("830001", any()) } returns null
        every { contracts.findVersion("contract-v2") } returns version().copy(versionId = "contract-v2")
        every { verification.collect(any(), NOW) } returns candidate()
        every { ids.nextId() } returnsMany listOf("external-evidence-1", "external-evidence-2")
        every { evidence.insert(capture(inserted)) } answers { firstArg() }

        service.observe(command())
        service.observe(command().copy(versionId = "contract-v2", idempotencyKey = "observe-2"))

        assertThat(inserted).extracting("snapshotHash").doesNotHaveDuplicates()
    }

    @Test
    fun `같은 멱등 키와 내용은 기존 증적을 반환하고 다른 내용은 충돌한다`() {
        val existing = existingEvidence()
        every { evidence.findByIdempotency("830001", "observe-1") } returns existing

        assertThat(service.observe(command())).isEqualTo(existing)
        assertThatThrownBy { service.observe(command().copy(reason = "다른 사유")) }
            .isInstanceOf(ConflictException::class.java)
        verify(exactly = 0) { verification.collect(any(), any()) }
    }

    @Test
    fun `외부 조회 예외도 ERROR 증적으로 남기고 완료로 처리하지 않는다`() {
        val inserted = slot<EmergencyExternalControlEvidence>()
        every { verification.collect(any(), NOW) } throws IllegalStateException("secret endpoint detail")
        every { ids.nextId() } returns "external-evidence-error"
        every { evidence.insert(capture(inserted)) } answers { firstArg() }

        val result = service.observe(command())

        assertThat(result.evaluation.status).isEqualTo(EmergencyExternalControlStatus.ERROR)
        assertThat(result.evaluation.completionReady).isFalse()
        assertThat(result.evaluation.issues).containsExactly("EXTERNAL_CONTROL_SOURCE_ERROR")
    }

    @Test
    fun `조회 역할만 가진 사용자는 외부 관찰을 실행할 수 없다`() {
        val viewer = AdminActor("830002", "0001", setOf(AdminRole.BCM_VIEWER))

        assertThatThrownBy { service.observe(command().copy(actor = viewer)) }
            .isInstanceOf(IllegalStateException::class.java)
        verify(exactly = 0) { evidence.findByIdempotency(any(), any()) }
    }

    private fun command() =
        ObserveEmergencyExternalControlsCommand(
            versionId = "contract-v1",
            reason = "비상 외부 통제 확인",
            workTicket = "INC-200",
            idempotencyKey = "observe-1",
            actor = OPERATOR,
        )

    private fun existingEvidence(): EmergencyExternalControlEvidence {
        val candidate = candidate()
        return EmergencyExternalControlEvidence(
            "external-evidence-1",
            "BASE",
            "contract-v1",
            "a".repeat(64),
            candidate,
            com.whatto.bcm.domain.admin.EmergencyExternalControlEvaluator
                .evaluate(candidate, NOW),
            "비상 외부 통제 확인",
            "INC-200",
            "observe-1",
            OPERATOR,
        )
    }

    private fun candidate() =
        EmergencyExternalControlCandidate(
            "tap-policy-api",
            TapBatchObservation(true, NOW),
            BigInteger.valueOf(1234),
            EMPTY_HASH,
            "rpc-a",
            observation(),
            "rpc-b",
            observation(),
            emptyList(),
            NOW,
            NOW.plusSeconds(300),
        )

    private fun observation() = EmergencyContractObservation(BigInteger.valueOf(1234), true, EMPTY_HASH, NOW)

    private fun version() =
        AdminContractVersion(
            "contract-v1",
            "BASE:SWEEP",
            "BASE",
            "SWEEP",
            "1.0.0",
            "0xabc",
            "commit",
            "a".repeat(64),
            "b".repeat(64),
            "c".repeat(64),
            "0xdeploy",
            BigInteger.valueOf(100),
            "{}",
            "d".repeat(64),
            "{}",
            "e".repeat(64),
            "doc://release",
            NOW,
            OPERATOR,
        )

    private class TrackingTransactionRunner : TransactionRunner {
        var active: Boolean = false
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
        const val EMPTY_HASH = "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945"
    }
}
