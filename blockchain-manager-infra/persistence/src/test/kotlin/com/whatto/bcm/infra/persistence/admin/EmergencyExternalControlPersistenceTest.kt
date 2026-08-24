package com.whatto.bcm.infra.persistence.admin

import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.EmergencyContractObservation
import com.whatto.bcm.domain.admin.EmergencyExternalControlCandidate
import com.whatto.bcm.domain.admin.EmergencyExternalControlEvaluation
import com.whatto.bcm.domain.admin.EmergencyExternalControlEvaluator
import com.whatto.bcm.domain.admin.EmergencyExternalControlEvidence
import com.whatto.bcm.domain.admin.EmergencyExternalControlRepository
import com.whatto.bcm.domain.admin.EmergencyExternalControlStatus
import com.whatto.bcm.domain.admin.TapBatchObservation
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigInteger
import java.time.Instant

@DataJdbcTest
@Import(EmergencyExternalControlJdbcAdapter::class)
class EmergencyExternalControlPersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var evidence: EmergencyExternalControlRepository

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @BeforeEach
    fun setUp() {
        jdbc.update(
            """
            INSERT INTO bcm_blkc_m
              (vndr_blkc_id, ntwk_cd, chain_id, dspl_nm, test_yn, deprc_yn, sync_dttm,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('base-ext-control', 'BASE', 8453, 'Base', 'N', 'N', '20260818010000',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent(),
        )
        jdbc.update(
            """
            INSERT INTO bcm_ctrt_vrsn_l
              (ctrt_vrsn_id, ctrt_scope_id, ntwk_cd, use_dvcd, vrsn, ctrt_addr,
               release_cmit, artifact_hash, abi_hash, runtime_code_hash, deploy_tx_hash,
               deploy_blck_no, immut_payload, immut_hash, ceiling_payload, ceiling_hash,
               release_uri, reg_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES
              ('contract-v1', 'BASE:SWEEP', 'BASE', 'SWEEP', 'v1', '0xabc',
               'commit', repeat('a', 64), repeat('b', 64), repeat('c', 64), '0xdeploy',
               100, '{}'::jsonb, repeat('d', 64), '{}'::jsonb, repeat('e', 64),
               'doc://release', '20260818010000', '830001', '0001', '830001', '0001')
            """.trimIndent(),
        )
    }

    @Test
    fun `외부 통제 관찰은 append-only로 저장되고 멱등 키와 네트워크로 조회된다`() {
        val saved = confirmedEvidence()

        assertThat(evidence.insert(saved)).isEqualTo(saved)
        assertThat(evidence.findByIdempotency("830001", "observe-1")).isEqualTo(saved)
        assertThat(evidence.findLatestByNetwork("BASE")).isEqualTo(saved)
        assertThatThrownBy {
            jdbc.update("UPDATE bcm_ext_ctrl_evdc_l SET req_rsn = 'changed' WHERE ext_ctrl_evdc_id = 'ext-1'")
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `같은 작업자 멱등 키의 중복 증적은 DB가 거절한다`() {
        evidence.insert(confirmedEvidence())

        assertThatThrownBy {
            evidence.insert(confirmedEvidence().copy(evidenceId = "ext-2", snapshotHash = HASH_B))
        }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `snapshot hash 중복은 멱등 충돌로 숨기지 않는다`() {
        evidence.insert(confirmedEvidence())
        val duplicateSnapshot =
            confirmedEvidence().copy(
                evidenceId = "ext-2",
                idempotencyKey = "observe-2",
            )

        assertThatThrownBy { evidence.insert(duplicateSnapshot) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `확인 상태인데 TAP이 열려 있으면 DB가 거절한다`() {
        val inconsistent =
            confirmedEvidence().copy(
                candidate = candidate().copy(tap = TapBatchObservation(false, NOW)),
                evaluation = EmergencyExternalControlEvaluation(EmergencyExternalControlStatus.CONFIRMED, emptyList()),
            )

        assertThatThrownBy { evidence.insert(inconsistent) }
            .isInstanceOf(DataIntegrityViolationException::class.java)
    }

    private fun confirmedEvidence(): EmergencyExternalControlEvidence {
        val candidate = candidate()
        return EmergencyExternalControlEvidence(
            evidenceId = "ext-1",
            network = "BASE",
            contractVersionId = "contract-v1",
            snapshotHash = HASH_A,
            candidate = candidate,
            evaluation = EmergencyExternalControlEvaluator.evaluate(candidate, NOW),
            reason = "비상 외부 통제 확인",
            workTicket = "INC-200",
            idempotencyKey = "observe-1",
            registeredBy = AdminActor("830001", "0001", emptySet()),
        )
    }

    private fun candidate() =
        EmergencyExternalControlCandidate(
            tapSourceId = "tap-policy-api",
            tap = TapBatchObservation(true, NOW),
            pinnedBlockNumber = BigInteger.valueOf(1234),
            expectedOperatorSetHash = EMPTY_HASH,
            firstEndpointId = "rpc-a",
            first = observation(),
            secondEndpointId = "rpc-b",
            second = observation(),
            sourceErrors = emptyList(),
            observedAt = NOW,
            validUntil = NOW.plusSeconds(300),
        )

    private fun observation() = EmergencyContractObservation(BigInteger.valueOf(1234), true, EMPTY_HASH, NOW)

    private companion object {
        val NOW: Instant = Instant.parse("2026-08-18T01:00:00Z")
        const val EMPTY_HASH = "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945"
        const val HASH_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val HASH_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}
