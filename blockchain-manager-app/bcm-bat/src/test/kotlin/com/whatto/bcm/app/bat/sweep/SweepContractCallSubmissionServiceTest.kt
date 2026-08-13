package com.whatto.bcm.app.bat.sweep

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.RelayRejectedException
import com.whatto.bcm.domain.exception.SubmissionInProgressException
import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.submission.SubmissionRecipientType
import com.whatto.bcm.domain.submission.SubmissionRecord
import com.whatto.bcm.domain.submission.SubmissionRecordRepository
import com.whatto.bcm.domain.submission.SubmissionStatus
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.vendor.VendorContractCall
import com.whatto.bcm.domain.vendor.VendorContractCallPort
import com.whatto.bcm.domain.vendor.VendorContractCallRequest
import com.whatto.bcm.domain.vendor.VendorTransactionSubmission
import com.whatto.bcm.support.submission.SubmissionRequestHashes
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class SweepContractCallSubmissionServiceTest {
    @Test
    fun `신규 contract call을 원장에 먼저 기록하고 벤더 접수 결과로 제출 완료한다`() {
        val submissions = FakeContractCallSubmissions()
        val vendor = FakeVendorContractCalls(VendorTransactionSubmission.Accepted("vendor-tx-1"))
        val service = service(submissions, vendor)

        assertThat(service.submit(command())).isEqualTo(SweepContractCallResult("vendor-tx-1"))

        assertThat(submissions.required().status).isEqualTo(SubmissionStatus.SUBMITTED)
        assertThat(submissions.required().hashVersion).isEqualTo("cc-v1")
        assertThat(submissions.required().callData).isEqualTo(CALL_DATA.lowercase())
        assertThat(submissions.required().transactionType).isEqualTo(SubmissionTransactionType.SWEEP_APPROVE)
        assertThat(vendor.submitted.single())
            .isEqualTo(
                VendorContractCallRequest(
                    EXTERNAL_ID,
                    NETWORK,
                    VAULT_ID,
                    CONTRACT_ADDRESS,
                    CALL_DATA,
                    useGasless = true,
                ),
            )
    }

    @Test
    fun `batch contract call은 실행 식별자를 제출 원장에 함께 기록한다`() {
        val submissions = FakeContractCallSubmissions()
        val vendor = FakeVendorContractCalls(VendorTransactionSubmission.Accepted("vendor-batch-1"))
        val service = service(submissions, vendor)

        service.submit(
            command().copy(
                transactionType = SubmissionTransactionType.SWEEP_BATCH,
                sweepExecutionId = "01987654-3210-7abc-8def-0123456789ab",
            ),
        )

        assertThat(submissions.required().transactionType).isEqualTo(SubmissionTransactionType.SWEEP_BATCH)
        assertThat(submissions.required().sweepExecutionId).isEqualTo("01987654-3210-7abc-8def-0123456789ab")
    }

    @Test
    fun `같은 external id와 같은 요청은 처음 벤더 transaction id를 반환한다`() {
        val submissions = FakeContractCallSubmissions()
        val vendor = FakeVendorContractCalls(VendorTransactionSubmission.Accepted("vendor-tx-1"))
        val service = service(submissions, vendor)

        service.submit(command())

        assertThat(service.submit(command())).isEqualTo(SweepContractCallResult("vendor-tx-1"))
        assertThat(vendor.submitted).hasSize(1)
    }

    @Test
    fun `같은 external id의 calldata가 다르면 벤더를 재호출하지 않고 충돌한다`() {
        val submissions = FakeContractCallSubmissions()
        val vendor = FakeVendorContractCalls(VendorTransactionSubmission.Accepted("vendor-tx-1"))
        val service = service(submissions, vendor)
        service.submit(command())

        assertThatThrownBy { service.submit(command(callData = "0xdeadbeef")) }
            .isInstanceOf(ConflictException::class.java)
        assertThat(vendor.submitted).hasSize(1)
    }

    @Test
    fun `저장된 calldata가 요청 hash의 입력과 다르면 벤더를 호출하지 않고 충돌한다`() {
        val submissions =
            FakeContractCallSubmissions(
                requestedRecord(claimExpiresAt = "20260811235900").copy(callData = "0xdeadbeef"),
            )
        val vendor = FakeVendorContractCalls()
        val service = service(submissions, vendor)

        assertThatThrownBy { service.submit(command()) }
            .isInstanceOf(ConflictException::class.java)
        assertThat(vendor.submitted).isEmpty()
    }

    @Test
    fun `저장된 hash 판이 다르면 벤더를 호출하지 않고 충돌한다`() {
        val submissions =
            FakeContractCallSubmissions(
                requestedRecord(claimExpiresAt = "20260811235900").copy(hashVersion = "cc-v2"),
            )
        val vendor = FakeVendorContractCalls()
        val service = service(submissions, vendor)

        assertThatThrownBy { service.submit(command()) }
            .isInstanceOf(ConflictException::class.java)
        assertThat(vendor.submitted).isEmpty()
    }

    @Test
    fun `만료된 REQUESTED는 external id로 벤더 거래를 회수해 이중 제출하지 않는다`() {
        val submissions = FakeContractCallSubmissions(requestedRecord(claimExpiresAt = "20260811235900"))
        val vendor = FakeVendorContractCalls()
        vendor.recovered =
            VendorContractCall(
                "vendor-recovered",
                EXTERNAL_ID,
                VAULT_ID,
                CONTRACT_ADDRESS,
                CALL_DATA,
            )
        val service = service(submissions, vendor)

        assertThat(service.submit(command())).isEqualTo(SweepContractCallResult("vendor-recovered"))
        assertThat(vendor.submitted).isEmpty()
        assertThat(submissions.required().status).isEqualTo(SubmissionStatus.SUBMITTED)
    }

    @Test
    fun `400 뒤 external id 조회에도 거래가 없을 때만 FAILED로 확정한다`() {
        val rejection = VendorApiException("submitContractCall", 400)
        val submissions = FakeContractCallSubmissions()
        val vendor = FakeVendorContractCalls(VendorTransactionSubmission.BadRequestNeedsLookup(rejection))
        val service = service(submissions, vendor)

        assertThatThrownBy { service.submit(command()) }
            .isInstanceOf(RelayRejectedException::class.java)
            .hasCause(rejection)
        assertThat(submissions.required().status).isEqualTo(SubmissionStatus.FAILED)
    }

    @Test
    fun `유효한 claim의 REQUESTED는 벤더 조회나 제출 없이 진행 중으로 응답한다`() {
        val submissions = FakeContractCallSubmissions(requestedRecord(claimExpiresAt = "20260812000200"))
        val vendor = FakeVendorContractCalls()
        val service = service(submissions, vendor)

        assertThatThrownBy { service.submit(command()) }
            .isInstanceOf(SubmissionInProgressException::class.java)
        assertThat(vendor.submitted).isEmpty()
        assertThat(vendor.recoveryCalls).isZero()
    }

    private fun service(
        submissions: SubmissionRecordRepository,
        vendor: VendorContractCallPort,
    ) = SweepContractCallSubmissionService(
        submissions,
        vendor,
        ContractCallImmediateTransactionRunner,
        Clock.fixed(Instant.parse("2026-08-12T00:00:00Z"), ZoneOffset.UTC),
        SweepProperties(claimTtlSeconds = 120),
    )

    private fun command(callData: String = CALL_DATA) =
        SweepContractCallCommand(
            externalTransactionId = EXTERNAL_ID,
            transactionType = SubmissionTransactionType.SWEEP_APPROVE,
            senderAccountId = ACCOUNT_ID,
            sourceVaultId = VAULT_ID,
            network = NETWORK,
            symbol = SYMBOL,
            contractAddress = CONTRACT_ADDRESS,
            semanticAmount = "100",
            callData = callData,
        )

    private companion object {
        const val EXTERNAL_ID = "swa-0198"
        const val ACCOUNT_ID = "customer-1"
        const val VAULT_ID = "vault-customer-1"
        const val NETWORK = "ETHEREUM"
        const val SYMBOL = "USDC"
        const val CONTRACT_ADDRESS = "0x2222222222222222222222222222222222222222"
        const val CALL_DATA = "0x095ea7b3abcdef"

        fun requestedRecord(claimExpiresAt: String): SubmissionRecord {
            val fingerprint =
                SubmissionRequestHashes.contractCallV1(
                    ACCOUNT_ID,
                    CONTRACT_ADDRESS,
                    NETWORK,
                    SYMBOL,
                    "100",
                    CALL_DATA,
                )
            return SubmissionRecord(
                externalTransactionId = EXTERNAL_ID,
                requestHash = fingerprint.requestHash,
                hashVersion = fingerprint.hashVersion,
                status = SubmissionStatus.REQUESTED,
                claimId = "old-claim",
                claimExpiresAt = claimExpiresAt,
                transactionType = SubmissionTransactionType.SWEEP_APPROVE,
                vendorTransactionId = null,
                senderAccountId = ACCOUNT_ID,
                recipientType = SubmissionRecipientType.ADDRESS,
                recipientValue = CONTRACT_ADDRESS,
                network = NETWORK,
                symbol = SYMBOL,
                amount = "100",
                requestedAt = "20260811235800",
                respondedAt = null,
                callData = CALL_DATA.lowercase(),
            )
        }
    }
}

private class FakeContractCallSubmissions(
    initial: SubmissionRecord? = null,
) : SubmissionRecordRepository {
    private var row = initial

    override fun insert(record: SubmissionRecord): SubmissionRecord {
        if (row != null) throw ConflictException("submission", record.externalTransactionId)
        row = record
        return record
    }

    override fun tryClaim(
        externalTransactionId: String,
        claimId: String,
        claimExpiresAt: String,
        now: String,
    ): SubmissionRecord? {
        val current = row ?: return null
        if (current.externalTransactionId != externalTransactionId || current.status == SubmissionStatus.SUBMITTED) return null
        if (current.status == SubmissionStatus.REQUESTED && requireNotNull(current.claimExpiresAt) > now) return null
        return current
            .copy(
                status = SubmissionStatus.REQUESTED,
                claimId = claimId,
                claimExpiresAt = claimExpiresAt,
                respondedAt = null,
            ).also { row = it }
    }

    override fun markSubmitted(
        externalTransactionId: String,
        vendorTransactionId: String,
        respondedAt: String,
    ): SubmissionRecord = error("not used")

    override fun markSubmittedByClaim(
        externalTransactionId: String,
        claimId: String,
        vendorTransactionId: String,
        respondedAt: String,
    ): SubmissionRecord {
        val current = required()
        if (current.externalTransactionId != externalTransactionId || current.claimId != claimId) {
            throw ConflictException("submission", externalTransactionId)
        }
        return current
            .copy(
                status = SubmissionStatus.SUBMITTED,
                vendorTransactionId = vendorTransactionId,
                claimId = null,
                claimExpiresAt = null,
                respondedAt = respondedAt,
            ).also { row = it }
    }

    override fun markFailedByClaim(
        externalTransactionId: String,
        claimId: String,
        respondedAt: String,
    ): SubmissionRecord {
        val current = required()
        if (current.externalTransactionId != externalTransactionId || current.claimId != claimId) {
            throw ConflictException("submission", externalTransactionId)
        }
        return current
            .copy(
                status = SubmissionStatus.FAILED,
                claimId = null,
                claimExpiresAt = null,
                respondedAt = respondedAt,
            ).also { row = it }
    }

    override fun findByExternalTransactionId(externalTransactionId: String): SubmissionRecord? =
        row?.takeIf { it.externalTransactionId == externalTransactionId }

    override fun findByVendorTransactionId(vendorTransactionId: String): SubmissionRecord? =
        row?.takeIf { it.vendorTransactionId == vendorTransactionId }

    fun required(): SubmissionRecord = requireNotNull(row)
}

private class FakeVendorContractCalls(
    private val result: VendorTransactionSubmission? = null,
) : VendorContractCallPort {
    val submitted = mutableListOf<VendorContractCallRequest>()
    var recovered: VendorContractCall? = null
    var recoveryCalls = 0
        private set

    override fun submitContractCall(request: VendorContractCallRequest): VendorTransactionSubmission {
        submitted += request
        return requireNotNull(result)
    }

    override fun contractCallByExternalTransactionId(externalTransactionId: String): VendorContractCall? {
        recoveryCalls += 1
        return recovered
    }
}

private object ContractCallImmediateTransactionRunner : TransactionRunner {
    override fun <T> run(block: () -> T): T = block()
}
