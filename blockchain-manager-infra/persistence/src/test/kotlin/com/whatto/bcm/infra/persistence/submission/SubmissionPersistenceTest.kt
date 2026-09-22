package com.whatto.bcm.infra.persistence.submission

import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.submission.SubmissionRecipientType
import com.whatto.bcm.domain.submission.SubmissionRecord
import com.whatto.bcm.domain.submission.SubmissionStatus
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.submission.SubmissionVendorCanonical
import com.whatto.bcm.infra.persistence.submission.fixture.SubmissionRecordFixture.fixture
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import com.whatto.bcm.support.submission.SubmissionRequestHashes
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@DataJdbcTest
@Import(SubmissionJdbcAdapter::class)
class SubmissionPersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var submissions: SubmissionJdbcAdapter

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var dataSource: DataSource

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `REQUESTED 전용 소유권은 잠금 대기 뒤 FAILED가 된 행을 되살리지 않는다`() {
        // 이번 슬라이스 Critical의 직접 원인이 이 경합이었다 — 판정 시점의 상태가 아니라
        // 잠금을 얻은 시점의 상태로 조건이 다시 평가돼야 한다(단일 조건부 UPDATE).
        val externalTransactionId = "wd-claim-race"
        submissions.insert(fixture(externalTransactionId = externalTransactionId, claimId = null, claimExpiresAt = null))
        val executor = Executors.newSingleThreadExecutor()

        try {
            dataSource.connection.use { failing ->
                failing.autoCommit = false
                failing
                    .prepareStatement("UPDATE bcm_sbmt_l SET sbmt_stcd = 'FAILED' WHERE ext_tx_id = ?")
                    .use { statement ->
                        statement.setString(1, externalTransactionId)
                        statement.executeUpdate()
                    }

                // 같은 행을 노리는 소유권 시도는 위 트랜잭션이 끝날 때까지 잠금 대기한다.
                val claimed =
                    executor.submit<SubmissionRecord?> {
                        submissions.tryClaimRequested(externalTransactionId, "claim-race", "20260917090030", "20260917090000")
                    }
                Thread.sleep(500)
                assertThat(claimed.isDone).isFalse()
                failing.commit()

                // 잠금이 풀린 뒤 상태는 FAILED다 — 되살리지 않고 못 잡았다고 답해야 한다.
                assertThat(claimed.get(5, TimeUnit.SECONDS)).isNull()
            }
            assertThat(submissions.findByExternalTransactionId(externalTransactionId)?.status)
                .isEqualTo(SubmissionStatus.FAILED)
        } finally {
            executor.shutdownNow()
            jdbc.update("DELETE FROM bcm_sbmt_l WHERE ext_tx_id = ?", externalTransactionId)
        }
    }

    @Test
    fun `REQUESTED 전용 소유권은 FAILED 행을 되살리지 않는다`() {
        // 공용 tryClaim은 FAILED를 REQUESTED로 되돌리지만(02 Fireblocks 규칙), Dfns는 그 전이를 금지한다(03 전이 표).
        val failed =
            fixture(externalTransactionId = "wd-claim-failed", status = SubmissionStatus.FAILED, claimId = null, claimExpiresAt = null)
        submissions.insert(failed)

        val claimed = submissions.tryClaimRequested("wd-claim-failed", "claim-1", "20260917090030", "20260917090000")

        assertThat(claimed).isNull()
        assertThat(submissions.findByExternalTransactionId("wd-claim-failed")?.status).isEqualTo(SubmissionStatus.FAILED)
    }

    @Test
    fun `REQUESTED 전용 소유권은 만료된 소유권만 뺏는다`() {
        val requested = fixture(externalTransactionId = "wd-claim-req", claimId = "old", claimExpiresAt = "20260917090030")
        submissions.insert(requested)

        // 아직 살아 있는 소유권은 못 뺏는다.
        assertThat(submissions.tryClaimRequested("wd-claim-req", "claim-2", "20260917090100", "20260917090000")).isNull()
        // 만료 뒤에는 뺏는다.
        val claimed = submissions.tryClaimRequested("wd-claim-req", "claim-2", "20260917090100", "20260917090031")
        assertThat(claimed?.claimId).isEqualTo("claim-2")
    }

    @Test
    fun `제출 시점의 벤더 canonical 값을 함께 저장하고 그대로 되찾는다`() {
        // Dfns 회수는 이 값들로만 본문을 다시 만든다 — 저장·복원이 어긋나면 "같은 본문"이 깨진다(03 V28).
        val canonical =
            SubmissionVendorCanonical(
                vendorWalletId = "wa-1",
                vendorAssetId = "EthereumSepolia:Erc20:0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48",
                amountBaseUnits = "1500000",
                decimals = 6,
                destinationAddress = "0xabc",
            )
        val requested = fixture(externalTransactionId = "wd-v28-canonical", vendorCanonical = canonical)

        submissions.insert(requested)

        assertThat(submissions.findByExternalTransactionId("wd-v28-canonical")?.vendorCanonical).isEqualTo(canonical)
    }

    @Test
    fun `목적지 주소는 논리 목적지와 따로 저장한다`() {
        // 내부이체는 논리 목적지가 accountId다 — 회수가 그걸 to 로 쓰면 주소 자리에 계정이 나간다(03 V30).
        val canonical =
            SubmissionVendorCanonical(
                vendorWalletId = "wa-1",
                vendorAssetId = "EthereumSepolia:Native",
                amountBaseUnits = "1000000",
                decimals = 6,
                destinationAddress = "0xdead00000000000000000000000000000000beef",
            )
        val requested =
            fixture(
                externalTransactionId = "wd-v30-internal",
                recipientType = SubmissionRecipientType.ACCOUNT,
                recipientValue = "acct-receiver",
                vendorCanonical = canonical,
            )

        submissions.insert(requested)

        val stored = submissions.findByExternalTransactionId("wd-v30-internal")
        assertThat(stored?.recipientValue).isEqualTo("acct-receiver")
        assertThat(stored?.vendorCanonical?.destinationAddress).isEqualTo("0xdead00000000000000000000000000000000beef")
    }

    @Test
    fun `목적지 주소만 빠진 canonical 은 DB 가 받지 않는다`() {
        // 다섯은 한 벌이다 — 넷만 검사하면 본문을 재구성할 수 없는 반쪽 snapshot 이 남는다(03 V30 CHECK).
        assertThatThrownBy {
            jdbc.update(
                """
                INSERT INTO bcm_sbmt_l
                  (ext_tx_id, req_hash, hash_vrsn, sbmt_stcd, tx_dvcd, snd_acnt_id, rcv_dvcd, rcv_vl,
                   ntwk_cd, tkn_smbl, trsf_amt, req_dttm,
                   vndr_wlt_id, vndr_ast_id, base_amt, dcml_cnt,
                   frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES ('wd-v30-partial', ?, 'v1', 'REQUESTED', 'WITHDRAWAL', 'acct-1', 'ADDRESS', '0xabc',
                        'ETHEREUM', 'USDC', 1, '20260918000000',
                        'wa-1', 'EthereumSepolia:Native', '1000000', 6,
                        'SYSTEM', '9999', 'SYSTEM', '9999')
                """.trimIndent(),
                "0".repeat(64),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `벤더 canonical 값이 없는 행도 그대로 저장된다 — Fireblocks 경로는 벤더 조회로 회수한다`() {
        val requested = fixture(externalTransactionId = "wd-v28-none", vendorCanonical = null)

        submissions.insert(requested)

        assertThat(submissions.findByExternalTransactionId("wd-v28-none")?.vendorCanonical).isNull()
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `V28 네 값 제약과 V30 다섯 값 제약이 함께 남아 있다`() {
        // V30은 transaction=off라 문장마다 커밋된다. 옛 제약을 지우는 문장을 두면 그 뒤에 죽었을 때
        // 재실행이 유일하게 남은 _v30을 지우고 다시 만들다 또 죽어 canonical 제약이 하나도 없는 상태가 될 수 있다.
        // 그래서 둘을 함께 남긴다(03 V30) — 오류 메시지가 아니라 카탈로그로 직접 확인한다.
        val names =
            jdbc.queryForList(
                """
                SELECT conname FROM pg_constraint
                 WHERE conrelid = 'bcm_sbmt_l'::regclass
                   AND contype = 'c'
                   AND conname LIKE 'ck_bcm_sbmt_vndr_canonical%'
                """.trimIndent(),
                String::class.java,
            )

        assertThat(names).containsExactlyInAnyOrder("ck_bcm_sbmt_vndr_canonical", "ck_bcm_sbmt_vndr_canonical_v30")
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `벤더 canonical 값은 한 벌이어야 하고 최소 단위·정밀도 형식을 DB가 막는다`() {
        val base =
            """
            INSERT INTO bcm_sbmt_l
              (ext_tx_id, req_hash, hash_vrsn, sbmt_stcd, tx_dvcd, snd_acnt_id, rcv_dvcd, rcv_vl,
               ntwk_cd, tkn_smbl, trsf_amt, req_dttm,
               vndr_wlt_id, vndr_ast_id, base_amt, dcml_cnt,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES (?, 'a', 'v1', 'REQUESTED', 'WITHDRAWAL', 'acct-1', 'ADDRESS', '0x1',
                    'ETHEREUM', 'USDC', 1, '20260917090000', ?, ?, ?, ?, 'SYSTEM', '9999', 'SYSTEM', '9999')
            """.trimIndent()

        // 일부만 채우면 본문을 재구성할 수 없다. 두 제약을 다 위반하지만 CHECK는 이름 알파벳순으로 평가되므로
        // 접미사 없는 V28 제약이 먼저 걸린다 — 그래서 이 이름이 나온다는 것 자체가 옛 제약이 살아 있다는 증거다.
        assertThatThrownBy { jdbc.update(base, "v28-partial", "wa-1", null, null, null) }
            .hasMessageContaining("ck_bcm_sbmt_vndr_canonical\"")
        // 넷은 다 있는데 목적지 주소만 없다 — V28 제약은 통과하므로 V30 제약만이 이 행을 막는다(03 V30).
        assertThatThrownBy { jdbc.update(base, "v30-missing-dst", "wa-1", "key", "100", 6) }
            .hasMessageContaining("ck_bcm_sbmt_vndr_canonical_v30")
        // 선행 0이 있는 최소 단위는 같은 금액의 표기를 둘로 만든다.
        assertThatThrownBy { jdbc.update(base, "v28-zero", "wa-1", "key", "0100", 6) }
            .hasMessageContaining("ck_bcm_sbmt_base_amt")
        // 모델 한계를 넘는 정밀도.
        assertThatThrownBy { jdbc.update(base, "v28-dcml", "wa-1", "key", "100", 256) }
            .hasMessageContaining("ck_bcm_sbmt_dcml")
    }

    @Test
    fun `REQUESTED 제출 원장을 저장하고 externalTxId로 모든 canonical 필드를 되찾는다`() {
        val requested = fixture()

        val saved = submissions.insert(requested)

        assertThat(saved).isEqualTo(requested)
        assertThat(submissions.findByExternalTransactionId(requested.externalTransactionId)).isEqualTo(requested)
        val audit = jdbc.queryForMap("SELECT * FROM bcm_sbmt_l WHERE ext_tx_id = ?", requested.externalTransactionId)
        assertThat(audit["frst_reg_empno"]).isEqualTo("SYSTEM")
        assertThat(audit["frst_reg_brcd"]).isEqualTo("9999")
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `WITHDRAWAL 중지가 먼저 선기록되면 신규 REQUESTED를 만들지 않는다`() {
        val network = "WDSTOP"
        val externalTransactionId = "wd-stop-before-request"
        jdbc.update(
            """
            INSERT INTO bcm_blkc_m
              (vndr_blkc_id, ntwk_cd, chain_id, dspl_nm, test_yn, deprc_yn, sync_dttm, chain_mdl_dvcd,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('withdrawal-stop-test', ?, 31338, 'Withdrawal Stop', 'Y', 'N', '20260807120000', 'EVM',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (ntwk_cd) DO NOTHING
            """.trimIndent(),
            network,
        )
        val executor = Executors.newSingleThreadExecutor()

        try {
            dataSource.connection.use { stopConnection ->
                stopConnection.autoCommit = false
                stopConnection
                    .prepareStatement(
                        """
                        INSERT INTO bcm_exec_gate_evt_l
                          (gate_evt_id, ntwk_cd, gate_dvcd, evt_seq, gate_stcd, req_rsn, work_tckt,
                           idmp_key, rsm_req_id, occr_dttm, frst_reg_empno, frst_reg_brcd,
                           last_chng_empno, last_chng_brcd)
                        VALUES ('gate-stop-before-withdrawal', ?, 'WITHDRAWAL', 1, 'STOPPED', 'test stop',
                                'SEC-WD-STOP', 'gate-stop-before-withdrawal', NULL, '20260807120001',
                                '810001', '0001', '810001', '0001')
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, network)
                        statement.executeUpdate()
                    }
                val result =
                    executor.submit<Throwable?> {
                        runCatching {
                            submissions.insert(fixture(externalTransactionId = externalTransactionId, network = network))
                        }.exceptionOrNull()
                    }

                Thread.sleep(500)
                assertThat(result.isDone).isFalse()
                stopConnection.commit()

                assertThat(result.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ConflictException::class.java)
            }
        } finally {
            executor.shutdownNow()
            jdbc.update("DELETE FROM bcm_sbmt_l WHERE ext_tx_id = ?", externalTransactionId)
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `REQUESTED 회수 중 FAILED가 확정되면 중지 게이트 없이 재시도하지 않는다`() {
        val network = "WDRACESTOP"
        val externalTransactionId = "wd-failed-during-claim"
        jdbc.update(
            """
            INSERT INTO bcm_blkc_m
              (vndr_blkc_id, ntwk_cd, chain_id, dspl_nm, test_yn, deprc_yn, sync_dttm, chain_mdl_dvcd,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('withdrawal-claim-race-test', ?, 31340, 'Withdrawal Claim Race', 'Y', 'N', '20260807120000', 'EVM',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (ntwk_cd) DO NOTHING
            """.trimIndent(),
            network,
        )
        submissions.insert(fixture(externalTransactionId = externalTransactionId, network = network))
        val executor = Executors.newSingleThreadExecutor()

        try {
            dataSource.connection.use { failureConnection ->
                failureConnection.autoCommit = false
                failureConnection
                    .prepareStatement(
                        """
                        UPDATE bcm_sbmt_l
                        SET sbmt_stcd = 'FAILED', rsp_dttm = '20260807120010',
                            claim_id = NULL, claim_exp_dttm = NULL
                        WHERE ext_tx_id = ? AND sbmt_stcd = 'REQUESTED' AND claim_id = 'claim-owner-1'
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, externalTransactionId)
                        assertThat(statement.executeUpdate()).isEqualTo(1)
                    }
                jdbc.update(
                    """
                    INSERT INTO bcm_exec_gate_evt_l
                      (gate_evt_id, ntwk_cd, gate_dvcd, evt_seq, gate_stcd, req_rsn, work_tckt,
                       idmp_key, rsm_req_id, occr_dttm, frst_reg_empno, frst_reg_brcd,
                       last_chng_empno, last_chng_brcd)
                    VALUES ('gate-stop-during-withdrawal-claim', ?, 'WITHDRAWAL', 1, 'STOPPED', 'test stop',
                            'SEC-WD-RACE', 'gate-stop-during-withdrawal-claim', NULL, '20260807120011',
                            '810001', '0001', '810001', '0001')
                    """.trimIndent(),
                    network,
                )
                val result =
                    executor.submit<Throwable?> {
                        runCatching {
                            submissions.tryClaim(
                                externalTransactionId,
                                "claim-owner-2",
                                "20260807120200",
                                "20260807120101",
                            )
                        }.exceptionOrNull()
                    }

                Thread.sleep(500)
                assertThat(result.isDone).isFalse()
                failureConnection.commit()

                assertThat(result.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ConflictException::class.java)
                assertThat(submissions.findByExternalTransactionId(externalTransactionId)?.status)
                    .isEqualTo(SubmissionStatus.FAILED)
            }
        } finally {
            executor.shutdownNow()
            jdbc.update("DELETE FROM bcm_sbmt_l WHERE ext_tx_id = ?", externalTransactionId)
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `WITHDRAWAL claim은 submission row보다 gate를 먼저 잠근다`() {
        val network = "WDLOCKORDER"
        val externalTransactionId = "wd-gate-before-row"
        jdbc.update(
            """
            INSERT INTO bcm_blkc_m
              (vndr_blkc_id, ntwk_cd, chain_id, dspl_nm, test_yn, deprc_yn, sync_dttm, chain_mdl_dvcd,
               frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('withdrawal-lock-order-test', ?, 31341, 'Withdrawal Lock Order', 'Y', 'N', '20260807120000', 'EVM',
                    'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (ntwk_cd) DO NOTHING
            """.trimIndent(),
            network,
        )
        submissions.insert(fixture(externalTransactionId = externalTransactionId, network = network))
        val executor = Executors.newSingleThreadExecutor()

        try {
            dataSource.connection.use { failureConnection ->
                failureConnection.autoCommit = false
                failureConnection
                    .prepareStatement(
                        """
                        UPDATE bcm_sbmt_l
                        SET sbmt_stcd = 'FAILED', rsp_dttm = '20260807120010',
                            claim_id = NULL, claim_exp_dttm = NULL
                        WHERE ext_tx_id = ? AND sbmt_stcd = 'REQUESTED' AND claim_id = 'claim-owner-1'
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, externalTransactionId)
                        assertThat(statement.executeUpdate()).isEqualTo(1)
                    }
                val result =
                    executor.submit<SubmissionRecord?> {
                        submissions.tryClaim(
                            externalTransactionId,
                            "claim-owner-2",
                            "20260807120200",
                            "20260807120101",
                        )
                    }

                Thread.sleep(500)
                assertThat(result.isDone).isFalse()
                dataSource.connection.use { probeConnection ->
                    probeConnection.autoCommit = false
                    probeConnection
                        .prepareStatement("SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))")
                        .use { statement ->
                            statement.setString(1, "BCM:EXECUTION_GATE:$network:WITHDRAWAL")
                            statement.executeQuery().use { rs ->
                                assertThat(rs.next()).isTrue()
                                assertThat(rs.getBoolean(1)).isFalse()
                            }
                        }
                    probeConnection.rollback()
                }
                failureConnection.commit()

                assertThat(result.get(5, TimeUnit.SECONDS)?.status).isEqualTo(SubmissionStatus.REQUESTED)
            }
        } finally {
            executor.shutdownNow()
            jdbc.update("DELETE FROM bcm_sbmt_l WHERE ext_tx_id = ?", externalTransactionId)
        }
    }

    @Test
    fun `cc-v1 contract call은 정규화 calldata를 저장해 원장 필드만으로 요청 hash를 재계산한다`() {
        val fingerprint =
            SubmissionRequestHashes.contractCallV1(
                senderAccountId = "customer-1",
                contractAddress = "0xABCDEF",
                network = "ETHEREUM",
                symbol = "USDC",
                amount = "100.00",
                callData = "0x095EA7B3AA",
            )
        val requested =
            fixture(
                externalTransactionId = "swa-cc-v1",
                requestHash = fingerprint.requestHash,
                hashVersion = fingerprint.hashVersion,
                transactionType = SubmissionTransactionType.SWEEP_APPROVE,
                senderAccountId = "customer-1",
                recipientType = SubmissionRecipientType.ADDRESS,
                recipientValue = "0xABCDEF",
                amount = fingerprint.normalizedAmount,
                callData = fingerprint.normalizedCallData,
            )

        submissions.insert(requested)
        val restored = submissions.findByExternalTransactionId(requested.externalTransactionId)!!
        val recalculated =
            SubmissionRequestHashes.contractCallV1(
                restored.senderAccountId,
                restored.recipientValue,
                restored.network,
                restored.symbol,
                restored.amount,
                restored.callData!!,
            )

        assertThat(restored.callData).isEqualTo("0x095ea7b3aa")
        assertThat(recalculated.requestHash).isEqualTo(restored.requestHash)
    }

    @Test
    fun `sweep contract call은 calldata가 필수다`() {
        assertThatThrownBy {
            submissions.insert(
                fixture(
                    externalTransactionId = "swa-missing-calldata",
                    hashVersion = "cc-v1",
                    transactionType = SubmissionTransactionType.SWEEP_APPROVE,
                ),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `일반 전송에는 contract call calldata를 허용하지 않는다`() {
        assertThatThrownBy {
            submissions.insert(
                fixture(
                    externalTransactionId = "wd-unexpected-calldata",
                    callData = "0x095ea7b3aa",
                ),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `contract call calldata는 소문자 hex여야 한다`() {
        assertThatThrownBy {
            submissions.insert(
                fixture(
                    externalTransactionId = "swa-invalid-calldata",
                    hashVersion = "cc-v1",
                    transactionType = SubmissionTransactionType.SWEEP_APPROVE,
                    callData = "0x095EA7B3AA",
                ),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `contract call calldata는 비어 있을 수 없다`() {
        assertThatThrownBy {
            submissions.insert(
                fixture(
                    externalTransactionId = "swa-empty-calldata",
                    hashVersion = "cc-v1",
                    transactionType = SubmissionTransactionType.SWEEP_APPROVE,
                    callData = "",
                ),
            )
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    @Test
    fun `유효한 claim은 뺏지 못하고 만료 뒤에는 CAS로 새 소유자만 잡는다`() {
        submissions.insert(fixture())

        val denied =
            submissions.tryClaim(
                "wd-260713-0042",
                "claim-owner-2",
                "20260807120100",
                "20260807120020",
            )
        val acquired =
            submissions.tryClaim(
                "wd-260713-0042",
                "claim-owner-2",
                "20260807120130",
                "20260807120031",
            )

        assertThat(denied).isNull()
        assertThat(acquired?.claimId).isEqualTo("claim-owner-2")
        assertThat(acquired?.claimExpiresAt).isEqualTo("20260807120130")
    }

    @Test
    fun `미결 점검은 오래된 REQUESTED 중 유효 claim과 최근 점검을 제외해 한 번 예약한다`() {
        submissions.insert(
            fixture(
                externalTransactionId = "wd-unclaimed",
                claimId = null,
                claimExpiresAt = null,
                requestedAt = "20260807100000",
            ),
        )
        submissions.insert(
            fixture(
                externalTransactionId = "wd-expired",
                claimExpiresAt = "20260807110000",
                requestedAt = "20260807101000",
            ),
        )
        submissions.insert(
            fixture(
                externalTransactionId = "wd-owned",
                claimExpiresAt = "20260807130000",
                requestedAt = "20260807102000",
            ),
        )
        submissions.insert(
            fixture(
                externalTransactionId = "wd-recent-request",
                claimId = null,
                claimExpiresAt = null,
                requestedAt = "20260807115900",
            ),
        )
        submissions.insert(
            fixture(
                externalTransactionId = "wd-recent-check",
                claimId = null,
                claimExpiresAt = null,
                requestedAt = "20260807090000",
            ),
        )
        jdbc.update(
            "UPDATE bcm_sbmt_l SET last_chck_dttm = '20260807115100', chck_cnt = 3 WHERE ext_tx_id = 'wd-recent-check'",
        )

        val reserved =
            submissions.reserveRequestedForRecovery(
                now = "20260807120000",
                requestedBefore = "20260807115500",
                checkedBefore = "20260807115000",
                limit = 10,
            )

        assertThat(reserved.map { it.externalTransactionId }).containsExactly("wd-unclaimed", "wd-expired")
        assertThat(reserved).allSatisfy {
            assertThat(it.checkedAt).isEqualTo("20260807120000")
            assertThat(it.checkCount).isEqualTo(1)
        }
        assertThat(
            submissions.reserveRequestedForRecovery(
                now = "20260807120001",
                requestedBefore = "20260807115500",
                checkedBefore = "20260807115000",
                limit = 10,
            ),
        ).isEmpty()
        assertThat(jdbc.queryForObject("SELECT chck_cnt FROM bcm_sbmt_l WHERE ext_tx_id = 'wd-owned'", Int::class.java))
            .isZero()
        assertThat(
            jdbc.queryForObject(
                "SELECT chck_cnt FROM bcm_sbmt_l WHERE ext_tx_id = 'wd-recent-check'",
                Int::class.java,
            ),
        ).isEqualTo(3)
    }

    @Test
    fun `제출 응답은 claim 소유자만 SUBMITTED로 마감할 수 있다`() {
        submissions.insert(fixture())

        assertThatThrownBy {
            submissions.markSubmittedByClaim(
                "wd-260713-0042",
                "claim-other",
                "tx-91c",
                "20260807120010",
            )
        }.isInstanceOf(ConflictException::class.java)

        val submitted =
            submissions.markSubmittedByClaim(
                "wd-260713-0042",
                "claim-owner-1",
                "tx-91c",
                "20260807120010",
            )
        assertThat(submitted.status).isEqualTo(SubmissionStatus.SUBMITTED)
        assertThat(submitted.claimId).isNull()
        assertThat(submitted.claimExpiresAt).isNull()
    }

    @Test
    fun `같은 externalTxId를 다시 적재하면 도메인 충돌로 변환한다`() {
        submissions.insert(fixture())

        assertThatThrownBy { submissions.insert(fixture(requestHash = "b".repeat(64))) }
            .isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `하나의 vendorTxId를 서로 다른 제출에 연결할 수 없다`() {
        submissions.insert(
            fixture(
                externalTransactionId = "wd-1",
                status = SubmissionStatus.SUBMITTED,
                vendorTransactionId = "tx-shared",
                respondedAt = "20260807120100",
            ),
        )

        assertThatThrownBy {
            submissions.insert(
                fixture(
                    externalTransactionId = "wd-2",
                    status = SubmissionStatus.SUBMITTED,
                    vendorTransactionId = "tx-shared",
                    respondedAt = "20260807120200",
                ),
            )
        }.isInstanceOf(ConflictException::class.java)
    }

    @Test
    fun `vendorTxId가 있는 제출은 역방향으로 되찾는다`() {
        val submitted =
            fixture(
                status = SubmissionStatus.SUBMITTED,
                vendorTransactionId = "tx-91c",
                respondedAt = "20260807120100",
            )
        submissions.insert(submitted)

        assertThat(submissions.findByVendorTransactionId("tx-91c")).isEqualTo(submitted)
        assertThat(submissions.findByVendorTransactionId("tx-none")).isNull()
    }

    @Test
    fun `REQUESTED 원장은 SUBMITTED로 마감하며 vendorTxId를 이후 바꿀 수 없다`() {
        submissions.insert(fixture())

        val submitted = submissions.markSubmitted("wd-260713-0042", "tx-91c", "20260807120100")

        assertThat(submitted.status).isEqualTo(SubmissionStatus.SUBMITTED)
        assertThat(submitted.vendorTransactionId).isEqualTo("tx-91c")
        assertThat(submitted.respondedAt).isEqualTo("20260807120100")
        assertThatThrownBy {
            submissions.markSubmitted("wd-260713-0042", "tx-other", "20260807120200")
        }.isInstanceOf(ConflictException::class.java)
        assertThat(submissions.findByExternalTransactionId("wd-260713-0042")?.vendorTransactionId)
            .isEqualTo("tx-91c")
    }

    @Test
    fun `확정 거절은 소유 claim으로 FAILED 마감하고 새 claim CAS로 REQUESTED 재개한다`() {
        submissions.insert(fixture())

        val failed = submissions.markFailedByClaim("wd-260713-0042", "claim-owner-1", "20260807120100")
        val retried =
            submissions.tryClaim(
                "wd-260713-0042",
                "claim-owner-2",
                "20260807120200",
                "20260807120101",
            )

        assertThat(failed.status).isEqualTo(SubmissionStatus.FAILED)
        assertThat(failed.respondedAt).isEqualTo("20260807120100")
        assertThat(retried?.status).isEqualTo(SubmissionStatus.REQUESTED)
        assertThat(retried?.claimId).isEqualTo("claim-owner-2")
        assertThat(retried?.respondedAt).isNull()
    }

    @Test
    fun `이미 SUBMITTED인 원장을 FAILED 처리한 척 성공하지 않는다`() {
        submissions.insert(
            fixture(
                status = SubmissionStatus.SUBMITTED,
                vendorTransactionId = "tx-91c",
                respondedAt = "20260807120100",
            ),
        )

        assertThatThrownBy {
            submissions.markFailedByClaim("wd-260713-0042", "claim-owner-1", "20260807120200")
        }.isInstanceOf(ConflictException::class.java)
        assertThat(submissions.findByExternalTransactionId("wd-260713-0042")?.status)
            .isEqualTo(SubmissionStatus.SUBMITTED)
    }
}
