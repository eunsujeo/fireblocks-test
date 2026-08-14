package com.whatto.bcm.app.application.submission

import com.whatto.bcm.app.application.account.AccountQueryService
import com.whatto.bcm.app.application.account.fixture.AccountFixture
import com.whatto.bcm.app.application.asset.VendorAssetMappingQueryService
import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.RelayRejectedException
import com.whatto.bcm.domain.exception.SubmissionInProgressException
import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.submission.SubmissionConflictAlert
import com.whatto.bcm.domain.submission.SubmissionConflictAlertPort
import com.whatto.bcm.domain.submission.SubmissionRecipientType
import com.whatto.bcm.domain.submission.SubmissionRecord
import com.whatto.bcm.domain.submission.SubmissionRecordRepository
import com.whatto.bcm.domain.submission.SubmissionStatus
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.vendor.VendorTransaction
import com.whatto.bcm.domain.vendor.VendorTransactionDestination
import com.whatto.bcm.domain.vendor.VendorTransactionPeer
import com.whatto.bcm.domain.vendor.VendorTransactionPort
import com.whatto.bcm.domain.vendor.VendorTransactionSubmission
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.SocketTimeoutException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class TransactionSubmissionServiceTest {
    @MockK
    lateinit var submissions: SubmissionRecordRepository

    @MockK
    lateinit var accounts: AccountQueryService

    @MockK
    lateinit var mappings: VendorAssetMappingQueryService

    @MockK
    lateinit var vendor: VendorTransactionPort

    @MockK(relaxed = true)
    lateinit var conflictAlerts: SubmissionConflictAlertPort

    private lateinit var runner: RecordingTransactionRunner
    private lateinit var service: TransactionSubmissionService

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)
        runner = RecordingTransactionRunner()
        service =
            TransactionSubmissionService(
                submissions,
                accounts,
                mappings,
                vendor,
                runner,
                FIXED_CLOCK,
                TransactionSubmissionProperties(claimTtlSeconds = 30),
                conflictAlerts,
            )
        every { accounts.requiredAccount(SENDER_ID) } returns AccountFixture.fixture(SENDER_ID, vendorVaultId = "vault-source")
        every { mappings.requiredMapping("ETHEREUM", "USDC") } returns MAPPING
        every { submissions.tryClaim(EXTERNAL_ID, any(), any(), NOW) } answers {
            existing(claimId = secondArg(), claimExpiresAt = thirdArg())
        }
    }

    @Test
    fun `신규 요청은 원장을 먼저 커밋하고 벤더를 트랜잭션 밖에서 호출한 뒤 SUBMITTED로 바꾼다`() {
        val inserted = slot<SubmissionRecord>()
        every { submissions.insert(capture(inserted)) } answers {
            assertThat(runner.active).isTrue()
            inserted.captured
        }
        every { vendor.submitTransaction(any()) } answers {
            assertThat(runner.active).isFalse()
            VendorTransactionSubmission.Accepted("tx-1")
        }
        every { submissions.markSubmittedByClaim(EXTERNAL_ID, any(), "tx-1", NOW) } answers {
            assertThat(runner.active).isTrue()
            inserted.captured.copy(status = SubmissionStatus.SUBMITTED, vendorTransactionId = "tx-1", respondedAt = NOW)
        }

        val result = service.submit(command())

        assertThat(result.transactionId).isEqualTo("tx-1")
        assertThat(inserted.captured.status).isEqualTo(SubmissionStatus.REQUESTED)
        assertThat(inserted.captured.amount).isEqualTo("1.5")
        assertThat(runner.completedTransactions).isEqualTo(2)
    }

    @Test
    fun `같은 키와 같은 내용의 SUBMITTED 재요청은 처음 txId를 반환하고 벤더를 부르지 않는다`() {
        every { submissions.insert(any()) } throws ConflictException("submission", EXTERNAL_ID)
        every { submissions.findByExternalTransactionId(EXTERNAL_ID) } returns
            existing(
                status = SubmissionStatus.SUBMITTED,
                vendorId = "tx-first",
            )

        val result = service.submit(command(note = "changed", travelRule = mapOf("new" to "value")))

        assertThat(result.transactionId).isEqualTo("tx-first")
        verify(exactly = 0) { vendor.submitTransaction(any()) }
    }

    @Test
    fun `같은 키라도 금액이 다르면 409 충돌이다`() {
        every { submissions.insert(any()) } throws ConflictException("submission", EXTERNAL_ID)
        every { submissions.findByExternalTransactionId(EXTERNAL_ID) } returns existing(amount = "1.5")

        assertThatThrownBy { service.submit(command(amount = "2")) }
            .isInstanceOf(ConflictException::class.java)
        verify(exactly = 0) { vendor.submitTransaction(any()) }
    }

    @Test
    fun `1점50은 기존 1점5와 같은 금액으로 판정한다`() {
        every { submissions.insert(any()) } throws ConflictException("submission", EXTERNAL_ID)
        every { submissions.findByExternalTransactionId(EXTERNAL_ID) } returns
            existing(
                status = SubmissionStatus.SUBMITTED,
                vendorId = "tx-first",
            )

        assertThat(service.submit(command(amount = "1.50")).transactionId).isEqualTo("tx-first")
    }

    @Test
    fun `REQUESTED 재시도는 externalTxId 조회에서 회수하면 재제출하지 않는다`() {
        every { submissions.insert(any()) } throws ConflictException("submission", EXTERNAL_ID)
        every { submissions.findByExternalTransactionId(EXTERNAL_ID) } returns existing()
        every { vendor.transactionByExternalTransactionId(EXTERNAL_ID) } returns vendorTransaction("tx-recovered")
        every { submissions.markSubmittedByClaim(EXTERNAL_ID, any(), "tx-recovered", NOW) } returns
            existing(status = SubmissionStatus.SUBMITTED, vendorId = "tx-recovered")

        assertThat(service.submit(command()).transactionId).isEqualTo("tx-recovered")
        verify(exactly = 0) { vendor.submitTransaction(any()) }
    }

    @Test
    fun `벤더 400은 조회에서 거래를 찾으면 재제출 없이 202 결과로 만든다`() {
        every { submissions.insert(any()) } answers { firstArg() }
        every { vendor.submitTransaction(any()) } returns badRequest()
        every { vendor.transactionByExternalTransactionId(EXTERNAL_ID) } returns vendorTransaction("tx-duplicate")
        every { submissions.markSubmittedByClaim(EXTERNAL_ID, any(), "tx-duplicate", NOW) } returns
            existing(status = SubmissionStatus.SUBMITTED, vendorId = "tx-duplicate")

        assertThat(service.submit(command()).transactionId).isEqualTo("tx-duplicate")
        verify(exactly = 0) { submissions.markFailedByClaim(EXTERNAL_ID, any(), any()) }
    }

    @Test
    fun `벤더 400은 조회가 성공하고 거래가 없을 때만 FAILED로 굳힌다`() {
        every { submissions.insert(any()) } answers { firstArg() }
        every { vendor.submitTransaction(any()) } returns badRequest()
        every { vendor.transactionByExternalTransactionId(EXTERNAL_ID) } returns null
        every { submissions.markFailedByClaim(EXTERNAL_ID, any(), NOW) } returns
            existing(status = SubmissionStatus.FAILED)

        assertThatThrownBy { service.submit(command()) }.isInstanceOf(RelayRejectedException::class.java)
        verify(exactly = 1) { submissions.markFailedByClaim(EXTERNAL_ID, any(), NOW) }
    }

    @Test
    fun `벤더 400에서 조회 자체가 실패하면 FAILED로 굳히지 않고 REQUESTED를 유지한다`() {
        every { submissions.insert(any()) } answers { firstArg() }
        every { vendor.submitTransaction(any()) } returns badRequest()
        every { vendor.transactionByExternalTransactionId(EXTERNAL_ID) } throws
            VendorApiException("transactionByExternalTransactionId", 503)

        assertThatThrownBy { service.submit(command()) }.isInstanceOf(VendorApiException::class.java)
        verify(exactly = 0) { submissions.markFailedByClaim(EXTERNAL_ID, any(), any()) }
    }

    @Test
    fun `벤더 제출 타임아웃은 FAILED로 굳히지 않고 REQUESTED를 유지한다`() {
        every { submissions.insert(any()) } answers { firstArg() }
        every { vendor.submitTransaction(any()) } throws
            VendorApiException("submitTransaction", null, SocketTimeoutException("read timed out"))

        assertThatThrownBy { service.submit(command()) }.isInstanceOf(VendorApiException::class.java)
        verify(exactly = 0) { submissions.markSubmittedByClaim(EXTERNAL_ID, any(), any(), any()) }
        verify(exactly = 0) { submissions.markFailedByClaim(EXTERNAL_ID, any(), any()) }
    }

    @Test
    fun `externalTxId 조회로 찾은 거래 내용이 요청과 다르면 회수하지 않는다`() {
        every { submissions.insert(any()) } throws ConflictException("submission", EXTERNAL_ID)
        every { submissions.findByExternalTransactionId(EXTERNAL_ID) } returns existing()
        every { vendor.transactionByExternalTransactionId(EXTERNAL_ID) } returns
            vendorTransaction("tx-hijacked").copy(amount = "99")

        assertThatThrownBy { service.submit(command()) }.isInstanceOf(ConflictException::class.java)
        verify(exactly = 1) {
            conflictAlerts.alert(
                SubmissionConflictAlert(EXTERNAL_ID, "tx-hijacked", null, SubmissionStatus.REQUESTED),
            )
        }
        verify(exactly = 0) { submissions.markSubmittedByClaim(EXTERNAL_ID, any(), any(), any()) }
        verify(exactly = 0) { vendor.submitTransaction(any()) }
    }

    @Test
    fun `externalTxId 조회 거래의 금액 형식이 깨졌으면 충돌로 숨기지 않고 벤더 장애로 처리한다`() {
        every { submissions.insert(any()) } throws ConflictException("submission", EXTERNAL_ID)
        every { submissions.findByExternalTransactionId(EXTERNAL_ID) } returns existing()
        every { vendor.transactionByExternalTransactionId(EXTERNAL_ID) } returns
            vendorTransaction("tx-malformed").copy(amount = "not-a-number")

        assertThatThrownBy { service.submit(command()) }
            .isInstanceOf(VendorApiException::class.java)
            .satisfies({ exception ->
                exception as VendorApiException
                assertThat(exception.operation).isEqualTo("validateRecoveredTransaction")
            })
        verify(exactly = 0) { conflictAlerts.alert(any()) }
    }

    @Test
    fun `확정 4xx 거절은 FAILED로 기록하고 일시적 벤더 실패는 REQUESTED로 둔다`() {
        every { submissions.insert(any()) } answers { firstArg() }
        every { vendor.submitTransaction(any()) } throws RelayRejectedException("invalid destination")
        every { submissions.markFailedByClaim(EXTERNAL_ID, any(), NOW) } returns existing(status = SubmissionStatus.FAILED)

        assertThatThrownBy { service.submit(command()) }.isInstanceOf(RelayRejectedException::class.java)
        verify(exactly = 1) { submissions.markFailedByClaim(EXTERNAL_ID, any(), NOW) }

        every { submissions.insert(any()) } answers { firstArg() }
        every { vendor.submitTransaction(any()) } throws VendorApiException("submitTransaction", 503)
        assertThatThrownBy { service.submit(command()) }.isInstanceOf(VendorApiException::class.java)
        verify(exactly = 1) { submissions.markFailedByClaim(EXTERNAL_ID, any(), NOW) }
    }

    @Test
    fun `확정 거절 기록이 웹훅 선처리와 충돌해도 원래 벤더 거절을 유지한다`() {
        val rejected = RelayRejectedException("invalid destination")
        val stateConflict = ConflictException("submission", EXTERNAL_ID)
        every { submissions.insert(any()) } answers { firstArg() }
        every { vendor.submitTransaction(any()) } throws rejected
        every { submissions.markFailedByClaim(EXTERNAL_ID, any(), NOW) } throws stateConflict

        assertThatThrownBy { service.submit(command()) }
            .isSameAs(rejected)
            .satisfies({ exception -> assertThat(exception.suppressed).containsExactly(stateConflict) })
    }

    @Test
    fun `FAILED 같은 요청은 claim을 잡아 REQUESTED로 되돌린 뒤 다시 제출한다`() {
        every { submissions.insert(any()) } throws ConflictException("submission", EXTERNAL_ID)
        every { submissions.findByExternalTransactionId(EXTERNAL_ID) } returns existing(status = SubmissionStatus.FAILED)
        every { vendor.submitTransaction(any()) } returns VendorTransactionSubmission.Accepted("tx-retried")
        every { submissions.markSubmittedByClaim(EXTERNAL_ID, any(), "tx-retried", NOW) } returns
            existing(status = SubmissionStatus.SUBMITTED, vendorId = "tx-retried")

        assertThat(service.submit(command()).transactionId).isEqualTo("tx-retried")
        verify(exactly = 1) { submissions.tryClaim(EXTERNAL_ID, any(), any(), NOW) }
    }

    @Test
    fun `벤더 수락 뒤 갱신 실패 재시도는 조회로 txId를 회수해 이중 제출하지 않는다`() {
        every { submissions.insert(any()) } answers { firstArg() }
        every { vendor.submitTransaction(any()) } returns VendorTransactionSubmission.Accepted("tx-accepted")
        every { submissions.markSubmittedByClaim(EXTERNAL_ID, any(), "tx-accepted", NOW) } throws
            IllegalStateException("db unavailable")

        assertThatThrownBy { service.submit(command()) }.isInstanceOf(IllegalStateException::class.java)

        every { submissions.insert(any()) } throws ConflictException("submission", EXTERNAL_ID)
        every { submissions.findByExternalTransactionId(EXTERNAL_ID) } returns existing()
        every { vendor.transactionByExternalTransactionId(EXTERNAL_ID) } returns vendorTransaction("tx-accepted")
        every { submissions.markSubmittedByClaim(EXTERNAL_ID, any(), "tx-accepted", NOW) } returns
            existing(status = SubmissionStatus.SUBMITTED, vendorId = "tx-accepted")

        assertThat(service.submit(command()).transactionId).isEqualTo("tx-accepted")
        verify(exactly = 1) { vendor.submitTransaction(any()) }
    }

    @Test
    fun `늦은 벤더 응답이 이미 기록된 같은 txId와 만나면 처음 결과로 수렴한다`() {
        every { submissions.insert(any()) } answers { firstArg() }
        every { vendor.submitTransaction(any()) } returns VendorTransactionSubmission.Accepted("tx-accepted")
        every { submissions.markSubmittedByClaim(EXTERNAL_ID, any(), "tx-accepted", NOW) } throws
            ConflictException("submission", EXTERNAL_ID)
        every { submissions.findByExternalTransactionId(EXTERNAL_ID) } returns
            existing(status = SubmissionStatus.SUBMITTED, vendorId = "tx-accepted")

        assertThat(service.submit(command()).transactionId).isEqualTo("tx-accepted")
    }

    @Test
    fun `늦은 벤더 응답이 다른 txId가 기록된 원장과 만나면 충돌을 숨기지 않는다`() {
        every { submissions.insert(any()) } answers { firstArg() }
        every { vendor.submitTransaction(any()) } returns VendorTransactionSubmission.Accepted("tx-late")
        every { submissions.markSubmittedByClaim(EXTERNAL_ID, any(), "tx-late", NOW) } throws
            ConflictException("submission", EXTERNAL_ID)
        every { submissions.findByExternalTransactionId(EXTERNAL_ID) } returns
            existing(status = SubmissionStatus.SUBMITTED, vendorId = "tx-other")

        assertThatThrownBy { service.submit(command()) }
            .isInstanceOf(ConflictException::class.java)
        verify(exactly = 1) {
            conflictAlerts.alert(
                SubmissionConflictAlert(EXTERNAL_ID, "tx-late", "tx-other", SubmissionStatus.SUBMITTED),
            )
        }
        verify(exactly = 1) { vendor.submitTransaction(any()) }
    }

    @Test
    fun `늦은 벤더 응답이 다른 claim의 REQUESTED 원장과 만나면 성공을 반환하지 않고 경보한다`() {
        every { submissions.insert(any()) } answers { firstArg() }
        every { vendor.submitTransaction(any()) } returns VendorTransactionSubmission.Accepted("tx-late")
        every { submissions.markSubmittedByClaim(EXTERNAL_ID, any(), "tx-late", NOW) } throws
            ConflictException("submission", EXTERNAL_ID)
        every { submissions.findByExternalTransactionId(EXTERNAL_ID) } returns
            existing(status = SubmissionStatus.REQUESTED, claimId = "new-owner")

        assertThatThrownBy { service.submit(command()) }
            .isInstanceOf(VendorApiException::class.java)
            .satisfies({ exception ->
                exception as VendorApiException
                assertThat(exception.operation).isEqualTo("recordSubmittedTransaction")
            })
        verify(exactly = 1) {
            conflictAlerts.alert(
                SubmissionConflictAlert(EXTERNAL_ID, "tx-late", null, SubmissionStatus.REQUESTED),
            )
        }
    }

    @Test
    fun `계정 목적지는 우리 accountId를 벤더 vault로 해소하고 INTERNAL 원장으로 제출한다`() {
        every { accounts.requiredAccount("acct_dest") } returns
            AccountFixture.fixture("acct_dest", vendorVaultId = "vault-destination")
        val inserted = slot<SubmissionRecord>()
        val vendorRequest = slot<com.whatto.bcm.domain.vendor.VendorTransactionRequest>()
        every { submissions.insert(capture(inserted)) } answers { firstArg() }
        every { vendor.submitTransaction(capture(vendorRequest)) } returns VendorTransactionSubmission.Accepted("tx-internal")
        every { submissions.markSubmittedByClaim(EXTERNAL_ID, any(), "tx-internal", NOW) } returns
            existing(status = SubmissionStatus.SUBMITTED, vendorId = "tx-internal")

        val result =
            service.submit(
                command().copy(recipient = TransactionSubmissionRecipient.Account("acct_dest")),
            )

        assertThat(result.transactionId).isEqualTo("tx-internal")
        assertThat(inserted.captured.transactionType).isEqualTo(SubmissionTransactionType.INTERNAL)
        assertThat(inserted.captured.recipientValue).isEqualTo("acct_dest")
        assertThat(vendorRequest.captured.destination)
            .isEqualTo(VendorTransactionDestination.Account("vault-destination"))
        assertThat(vendorRequest.captured.useGasless).isFalse()
    }

    @Test
    fun `REQUESTED의 유효한 claim을 뺏지 못하면 503 재시도 정보를 반환하고 벤더를 부르지 않는다`() {
        val inProgress = existing(claimId = "claim-owner", claimExpiresAt = "20260807120030")
        every { submissions.insert(any()) } throws ConflictException("submission", EXTERNAL_ID)
        every { submissions.findByExternalTransactionId(EXTERNAL_ID) } returns inProgress
        every { submissions.tryClaim(EXTERNAL_ID, any(), any(), NOW) } returns null

        assertThatThrownBy { service.submit(command()) }
            .isInstanceOf(SubmissionInProgressException::class.java)
            .satisfies({ exception ->
                exception as SubmissionInProgressException
                assertThat(exception.retryAfterSeconds).isEqualTo(30)
            })
        verify(exactly = 0) { vendor.submitTransaction(any()) }
        verify(exactly = 0) { vendor.transactionByExternalTransactionId(any()) }
    }

    @Test
    fun `REQUESTED의 만료 claim을 뺏으면 벤더 조회 뒤 없을 때만 제출한다`() {
        every { submissions.insert(any()) } throws ConflictException("submission", EXTERNAL_ID)
        every { submissions.findByExternalTransactionId(EXTERNAL_ID) } returns
            existing(claimId = "expired", claimExpiresAt = "20260807115959")
        every { vendor.transactionByExternalTransactionId(EXTERNAL_ID) } returns null
        every { vendor.submitTransaction(any()) } returns VendorTransactionSubmission.Accepted("tx-after-claim")
        every { submissions.markSubmittedByClaim(EXTERNAL_ID, any(), "tx-after-claim", NOW) } returns
            existing(status = SubmissionStatus.SUBMITTED, vendorId = "tx-after-claim")

        assertThat(service.submit(command()).transactionId).isEqualTo("tx-after-claim")
        io.mockk.verifyOrder {
            vendor.transactionByExternalTransactionId(EXTERNAL_ID)
            vendor.submitTransaction(any())
        }
    }

    private fun command(
        amount: String = "1.5",
        note: String? = null,
        travelRule: Map<String, Any?>? = null,
    ) = TransactionSubmissionCommand(
        externalTransactionId = EXTERNAL_ID,
        senderAccountId = SENDER_ID,
        recipient = TransactionSubmissionRecipient.Address("0x9fE2"),
        network = "ETHEREUM",
        symbol = "USDC",
        amount = amount,
        note = note,
        travelRuleMessage = travelRule,
    )

    private fun existing(
        status: SubmissionStatus = SubmissionStatus.REQUESTED,
        vendorId: String? = null,
        amount: String = "1.5",
        claimId: String? = null,
        claimExpiresAt: String? = null,
    ): SubmissionRecord =
        SubmissionRecord(
            externalTransactionId = EXTERNAL_ID,
            requestHash = "unused-for-cross-version-comparison",
            hashVersion = "v0",
            status = status,
            claimId = claimId,
            claimExpiresAt = claimExpiresAt,
            transactionType = SubmissionTransactionType.WITHDRAWAL,
            vendorTransactionId = vendorId,
            senderAccountId = SENDER_ID,
            recipientType = SubmissionRecipientType.ADDRESS,
            recipientValue = "0x9fE2",
            network = "ETHEREUM",
            symbol = "USDC",
            amount = amount,
            requestedAt = NOW,
            respondedAt = if (status == SubmissionStatus.REQUESTED) null else NOW,
        )

    /** 벤더 400 — 중복인지 검증 실패인지는 이 결과만으로 가르지 않는다 (02 벤더 응답별 처리). */
    private fun badRequest() =
        VendorTransactionSubmission.BadRequestNeedsLookup(
            VendorApiException("submitTransaction", 400),
        )

    private fun vendorTransaction(id: String) =
        VendorTransaction(
            transactionId = id,
            externalTransactionId = EXTERNAL_ID,
            vendorAssetId = "USDC_ETH",
            rawStatus = "SUBMITTED",
            subStatus = null,
            transactionHash = null,
            source = VendorTransactionPeer("VAULT_ACCOUNT", "vault-source"),
            destination = VendorTransactionPeer("ONE_TIME_ADDRESS", null),
            sourceAddress = null,
            destinationAddress = "0x9fE2",
            amount = "1.5",
            confirmationCount = 0,
            createdAtEpochMillis = 1,
            lastUpdatedEpochMillis = 1,
        )

    private class RecordingTransactionRunner : TransactionRunner {
        var active = false
        var completedTransactions = 0

        override fun <T> run(block: () -> T): T {
            check(!active)
            active = true
            return try {
                block().also { completedTransactions++ }
            } finally {
                active = false
            }
        }
    }

    companion object {
        private const val EXTERNAL_ID = "wd-260713-0042"
        private const val SENDER_ID = "acct_pool_02"
        private const val NOW = "20260807120000"
        private val FIXED_CLOCK = Clock.fixed(Instant.parse("2026-08-07T12:00:00Z"), ZoneId.of("Asia/Seoul"))
        private val MAPPING =
            VendorAssetMapping(
                network = "ETHEREUM",
                symbol = "USDC",
                vendorAssetId = "USDC_ETH",
                contractAddress = "0xA0b8",
                registeredAt = NOW,
                registeredByEmployeeNo = "E001",
                registeredByBranchCode = "0001",
            )
    }
}
