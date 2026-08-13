package com.whatto.bcm.infra.persistence.submission

import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.submission.SubmissionRecipientType
import com.whatto.bcm.domain.submission.SubmissionStatus
import com.whatto.bcm.domain.submission.SubmissionTransactionType
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

@DataJdbcTest
@Import(SubmissionJdbcAdapter::class)
class SubmissionPersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var submissions: SubmissionJdbcAdapter

    @Autowired
    lateinit var jdbc: JdbcTemplate

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
