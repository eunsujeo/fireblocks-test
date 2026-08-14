package com.whatto.bcm.app.bat.stall

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountRepository
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.asset.VendorAssetMappingRepository
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.submission.SubmissionRecipientType
import com.whatto.bcm.domain.submission.SubmissionRecord
import com.whatto.bcm.domain.submission.SubmissionRecordRepository
import com.whatto.bcm.domain.submission.SubmissionStatus
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.tx.BoostAttempt
import com.whatto.bcm.domain.tx.BoostAttemptAcquisition
import com.whatto.bcm.domain.tx.BoostAttemptRepository
import com.whatto.bcm.domain.tx.BoostIntentRequest
import com.whatto.bcm.domain.tx.BoostStatus
import com.whatto.bcm.domain.tx.StallAlertReason
import com.whatto.bcm.domain.tx.StallCandidate
import com.whatto.bcm.domain.tx.TxRecord
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.domain.vendor.VendorFeeLevel
import com.whatto.bcm.domain.vendor.VendorPage
import com.whatto.bcm.domain.vendor.VendorTransaction
import com.whatto.bcm.domain.vendor.VendorTransactionDestination
import com.whatto.bcm.domain.vendor.VendorTransactionLifecycleStage
import com.whatto.bcm.domain.vendor.VendorTransactionPageRequest
import com.whatto.bcm.domain.vendor.VendorTransactionPeer
import com.whatto.bcm.domain.vendor.VendorTransactionPort
import com.whatto.bcm.domain.vendor.VendorTransactionRequest
import com.whatto.bcm.domain.vendor.VendorTransactionSubmission
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class BoostSubmissionServiceTest {
    @Test
    fun `출금 RBF는 원 제출을 재구성해 HIGH fee와 gasless로 교체 제출한다`() {
        val boosts = RecordingBoosts()
        val vendor = RecordingVendorTransactions()
        val service = service(boosts, withdrawalSubmission(), vendor)

        val result = service.submit(candidate(), "0xabc")

        assertThat(result).isEqualTo(BoostSubmissionResult.Submitted("tx-replacement"))
        assertThat(vendor.submissions.single())
            .extracting(
                "externalTransactionId",
                "vendorAssetId",
                "sourceVaultId",
                "destination",
                "amount",
                "useGasless",
                "feeLevel",
                "replaceTransactionHash",
            ).containsExactly(
                "bst-test",
                "USDC_ERC20",
                "vault-source",
                VendorTransactionDestination.Address("0xrecipient"),
                "1.5",
                true,
                VendorFeeLevel.HIGH,
                "0xabc",
            )
        assertThat(boosts.submitted).containsExactly(BoostCompletion("tx-root", 1, "claim-1", "tx-replacement"))
    }

    @Test
    fun `sweep은 벤더 과거 거래 재실행 근거가 확인되기 전까지 boost하지 않는다`() {
        val boosts = RecordingBoosts()
        val service = service(boosts, sweepSubmission())

        val result = service.submit(candidate(SubmissionTransactionType.SWEEP_BATCH), "0xabc")

        assertThat(result).isEqualTo(BoostSubmissionResult.Alert(StallAlertReason.TRANSACTION_TYPE_NOT_ELIGIBLE))
        assertThat(boosts.acquiredRequests).isEmpty()
    }

    @Test
    fun `만료 intent는 externalTxId 조회로 대체 거래를 회수하고 재제출하지 않는다`() {
        val existing = attempt()
        val boosts = RecordingBoosts(acquisition = BoostAttemptAcquisition.Acquired(existing, false))
        val vendor = RecordingVendorTransactions(recovered = vendorTransaction("tx-recovered", externalTransactionId = "bst-test"))
        val service = service(boosts, withdrawalSubmission(), vendor)

        val result = service.submit(candidate(), "0xabc")

        assertThat(result).isEqualTo(BoostSubmissionResult.Submitted("tx-recovered"))
        assertThat(vendor.externalLookups).containsExactly("bst-test")
        assertThat(vendor.submissions).isEmpty()
        assertThat(boosts.submitted.single().newVendorTransactionId).isEqualTo("tx-recovered")
    }

    @Test
    fun `최대 시도에 도달하면 벤더를 제출하지 않고 경보 결과를 반환한다`() {
        val boosts = RecordingBoosts(acquisition = BoostAttemptAcquisition.MaximumAttemptsReached)
        val vendor = RecordingVendorTransactions()
        val service = service(boosts, withdrawalSubmission(), vendor)

        val result = service.submit(candidate(), "0xabc")

        assertThat(result).isEqualTo(BoostSubmissionResult.Alert(StallAlertReason.MAXIMUM_BOOST_ATTEMPTS_REACHED))
        assertThat(vendor.submissions).isEmpty()
    }

    @Test
    fun `벤더 400 후 externalTxId 조회에도 거래가 없으면 intent를 실패로 닫고 경보한다`() {
        val boosts = RecordingBoosts()
        val vendor =
            RecordingVendorTransactions(
                submissionResult =
                    VendorTransactionSubmission.BadRequestNeedsLookup(
                        VendorApiException("submitTransaction", 400),
                    ),
            )
        val service = service(boosts, withdrawalSubmission(), vendor)

        val result = service.submit(candidate(), "0xabc")

        assertThat(result).isEqualTo(BoostSubmissionResult.Alert(StallAlertReason.BOOST_SUBMISSION_REJECTED))
        assertThat(vendor.externalLookups).containsExactly("bst-test")
        assertThat(boosts.failed).containsExactly(BoostFailure("tx-root", 1, "claim-1"))
    }

    @Test
    fun `회수한 거래의 자금 이동 필드가 다르면 결과를 기록하지 않고 격리한다`() {
        val boosts = RecordingBoosts(acquisition = BoostAttemptAcquisition.Acquired(attempt(), false))
        val mismatched = vendorTransaction("tx-other", externalTransactionId = "bst-test").copy(amount = "99")
        val vendor = RecordingVendorTransactions(recovered = mismatched)
        val service = service(boosts, withdrawalSubmission(), vendor)

        org.assertj.core.api.Assertions
            .assertThatThrownBy { service.submit(candidate(), "0xabc") }
            .isInstanceOf(ConflictException::class.java)
        assertThat(vendor.submissions).isEmpty()
        assertThat(boosts.submitted).isEmpty()
    }

    @Test
    fun `제출 직전 재조회 hash가 이전 관찰과 다르면 intent를 만들지 않는다`() {
        val boosts = RecordingBoosts()
        val vendor = RecordingVendorTransactions()
        val service = service(boosts, withdrawalSubmission(), vendor)

        val result = service.submit(candidate(), "0xold")

        assertThat(result).isEqualTo(BoostSubmissionResult.Alert(StallAlertReason.TRANSACTION_HASH_MISMATCH))
        assertThat(boosts.acquiredRequests).isEmpty()
        assertThat(vendor.submissions).isEmpty()
    }

    private fun service(
        boosts: BoostAttemptRepository,
        submission: SubmissionRecord,
        vendor: RecordingVendorTransactions = RecordingVendorTransactions(),
    ) = BoostSubmissionService(
        boosts = boosts,
        submissions = SingleSubmissionRepository(submission),
        accounts = SingleAccountRepository,
        mappings = SingleMappingRepository,
        vendorTransactions = vendor,
        transactionRunner = ImmediateTransactionRunner,
        externalTransactionIds = BoostExternalTransactionIdGenerator { "bst-test" },
        clock = CLOCK,
        properties =
            StallCheckProperties(
                enabled = true,
                automaticBoostEnabledNetworks = setOf("ETHEREUM"),
                maximumBoostAttempts = 3,
                boostClaimTtlSeconds = 60,
            ),
        claimIds = BoostClaimIdGenerator { "claim-1" },
    )

    private companion object {
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-08-07T12:00:00Z"), ZoneId.of("Asia/Seoul"))
    }
}

private fun candidate(type: SubmissionTransactionType = SubmissionTransactionType.WITHDRAWAL) =
    StallCandidate(
        record =
            TxRecord(
                vendorTxId = "tx-root",
                externalTxId = "wd-root",
                accountId = "account-1",
                network = "ETHEREUM",
                symbol = "USDC",
                lastPublishedStatus = TxStatus.CONFIRMED,
                confirmationCount = 0,
                firstDetectedAt = "20260807110000",
                lastChangedAt = "20260807110000",
            ),
        submissionType = type,
    )

private fun withdrawalSubmission() = submission(SubmissionTransactionType.WITHDRAWAL, "0xrecipient", null)

private fun sweepSubmission() = submission(SubmissionTransactionType.SWEEP_BATCH, "0xcontract", "0x1234")

private fun submission(
    type: SubmissionTransactionType,
    recipient: String,
    callData: String?,
) = SubmissionRecord(
    externalTransactionId = "wd-root",
    requestHash = "a".repeat(64),
    hashVersion = if (callData == null) "v1" else "cc-v1",
    status = SubmissionStatus.SUBMITTED,
    claimId = null,
    claimExpiresAt = null,
    transactionType = type,
    vendorTransactionId = "tx-root",
    senderAccountId = "account-1",
    recipientType = SubmissionRecipientType.ADDRESS,
    recipientValue = recipient,
    network = "ETHEREUM",
    symbol = "USDC",
    amount = "1.5",
    requestedAt = "20260807100000",
    respondedAt = "20260807100001",
    callData = callData,
)

private fun attempt() =
    BoostAttempt(
        rootVendorTransactionId = "tx-root",
        trySequence = 1,
        externalTransactionId = "bst-test",
        status = BoostStatus.REQUESTED,
        claimId = "claim-1",
        claimExpiresAt = "20260807120100",
        replacementVendorTransactionId = "tx-root",
        replacementTransactionHash = "0xabc",
        feeLevel = VendorFeeLevel.HIGH,
        useGasless = true,
        newVendorTransactionId = null,
        requestedAt = "20260807120000",
        respondedAt = null,
    )

private fun vendorTransaction(
    transactionId: String = "tx-root",
    externalTransactionId: String? = "wd-root",
) = VendorTransaction(
    transactionId = transactionId,
    externalTransactionId = externalTransactionId,
    vendorAssetId = "USDC_ERC20",
    rawStatus = "CONFIRMING",
    subStatus = null,
    transactionHash = "0xabc",
    source = VendorTransactionPeer("VAULT_ACCOUNT", "vault-source"),
    destination = VendorTransactionPeer("ONE_TIME_ADDRESS", null),
    sourceAddress = null,
    destinationAddress = "0xrecipient",
    amount = "1.5",
    confirmationCount = 0,
    createdAtEpochMillis = 0,
    lastUpdatedEpochMillis = 0,
    lifecycleStage = VendorTransactionLifecycleStage.CONFIRMING,
)

private data class BoostCompletion(
    val rootVendorTransactionId: String,
    val trySequence: Int,
    val claimId: String,
    val newVendorTransactionId: String,
)

private data class BoostFailure(
    val rootVendorTransactionId: String,
    val trySequence: Int,
    val claimId: String,
)

private class RecordingBoosts(
    private val acquisition: BoostAttemptAcquisition = BoostAttemptAcquisition.Acquired(attempt(), true),
) : BoostAttemptRepository {
    val acquiredRequests = mutableListOf<BoostIntentRequest>()
    val submitted = mutableListOf<BoostCompletion>()
    val failed = mutableListOf<BoostFailure>()

    override fun acquire(
        candidate: TxRecord,
        request: BoostIntentRequest,
        maximumAttempts: Int,
        now: String,
    ): BoostAttemptAcquisition {
        acquiredRequests += request
        return acquisition
    }

    override fun markSubmittedByClaim(
        rootVendorTransactionId: String,
        trySequence: Int,
        claimId: String,
        newVendorTransactionId: String,
        respondedAt: String,
    ): BoostAttempt {
        submitted += BoostCompletion(rootVendorTransactionId, trySequence, claimId, newVendorTransactionId)
        return attempt().copy(
            status = BoostStatus.SUBMITTED,
            claimId = null,
            claimExpiresAt = null,
            newVendorTransactionId = newVendorTransactionId,
            respondedAt = respondedAt,
        )
    }

    override fun markSubmittedByObservation(
        externalTransactionId: String,
        newVendorTransactionId: String,
        respondedAt: String,
    ): BoostAttempt = error("not used")

    override fun markFailedByClaim(
        rootVendorTransactionId: String,
        trySequence: Int,
        claimId: String,
        respondedAt: String,
    ): BoostAttempt {
        failed += BoostFailure(rootVendorTransactionId, trySequence, claimId)
        return attempt().copy(status = BoostStatus.FAILED)
    }

    override fun findByExternalTransactionId(externalTransactionId: String): BoostAttempt? = null

    override fun findByNewVendorTransactionId(newVendorTransactionId: String): BoostAttempt? = null

    override fun findLatestViableByRoot(rootVendorTransactionId: String): BoostAttempt? = null

    override fun findViableByRoot(rootVendorTransactionId: String): List<BoostAttempt> = emptyList()

    override fun findByRootAndSequence(
        rootVendorTransactionId: String,
        trySequence: Int,
    ): BoostAttempt? = null
}

private class SingleSubmissionRepository(
    private val submission: SubmissionRecord,
) : SubmissionRecordRepository {
    override fun findByVendorTransactionId(vendorTransactionId: String): SubmissionRecord? = submission

    override fun findByExternalTransactionId(externalTransactionId: String): SubmissionRecord? = submission

    override fun insert(record: SubmissionRecord): SubmissionRecord = error("not used")

    override fun tryClaim(
        externalTransactionId: String,
        claimId: String,
        claimExpiresAt: String,
        now: String,
    ) = error("not used")

    override fun markSubmitted(
        externalTransactionId: String,
        vendorTransactionId: String,
        respondedAt: String,
    ) = error("not used")

    override fun markSubmittedByClaim(
        externalTransactionId: String,
        claimId: String,
        vendorTransactionId: String,
        respondedAt: String,
    ) = error("not used")

    override fun markFailedByClaim(
        externalTransactionId: String,
        claimId: String,
        respondedAt: String,
    ) = error("not used")
}

private object SingleAccountRepository : AccountRepository {
    override fun findByAccountId(accountId: String) = Account(accountId, AccountType.CUSTOMER, "ref", "vault-source", "20260807100000")

    override fun insert(account: Account) = error("not used")

    override fun findByTypeAndRef(
        accountType: AccountType,
        ref: String,
    ) = null
}

private object SingleMappingRepository : VendorAssetMappingRepository {
    override fun find(
        network: String,
        symbol: String,
    ) = VendorAssetMapping(network, symbol, "USDC_ERC20", "0xtoken", "20260807100000", "SYSTEM", "9999")

    override fun findByVendorAssetId(vendorAssetId: String) = null

    override fun findAll(
        network: String?,
        symbol: String?,
    ) = emptyList<VendorAssetMapping>()

    override fun existsByNetwork(network: String) = true

    override fun insert(mapping: VendorAssetMapping) = error("not used")

    override fun delete(
        network: String,
        symbol: String,
    ) = error("not used")
}

private class RecordingVendorTransactions(
    private val recovered: VendorTransaction? = null,
    private val submissionResult: VendorTransactionSubmission = VendorTransactionSubmission.Accepted("tx-replacement"),
) : VendorTransactionPort {
    val submissions = mutableListOf<VendorTransactionRequest>()
    val externalLookups = mutableListOf<String>()

    override fun transaction(transactionId: String): VendorTransaction = vendorTransaction(transactionId)

    override fun transactionByExternalTransactionId(externalTransactionId: String): VendorTransaction? {
        externalLookups += externalTransactionId
        return recovered
    }

    override fun submitTransaction(request: VendorTransactionRequest): VendorTransactionSubmission {
        submissions += request
        return submissionResult
    }

    override fun transactions(request: VendorTransactionPageRequest): VendorPage<VendorTransaction> = VendorPage(emptyList(), null)
}

private object ImmediateTransactionRunner : TransactionRunner {
    override fun <T> run(block: () -> T): T = block()
}
