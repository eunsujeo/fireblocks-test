package com.whatto.bcm.app.application.submission

import com.whatto.bcm.app.application.account.AccountQueryService
import com.whatto.bcm.app.application.account.DepositAddressQueryService
import com.whatto.bcm.app.application.account.fixture.AccountFixture
import com.whatto.bcm.app.application.asset.VendorAssetMappingQueryService
import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.account.DepositAddress
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.exception.AccountNotFoundException
import com.whatto.bcm.domain.exception.AssetNotSupportedException
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.InvalidRequestException
import com.whatto.bcm.domain.exception.RelayRejectedException
import com.whatto.bcm.domain.exception.SubmissionInProgressException
import com.whatto.bcm.domain.exception.UnprocessableRequestException
import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.submission.SubmissionRecipientType
import com.whatto.bcm.domain.submission.SubmissionRecord
import com.whatto.bcm.domain.submission.SubmissionRecordRepository
import com.whatto.bcm.domain.submission.SubmissionStatus
import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.submission.SubmissionVendorCanonical
import com.whatto.bcm.domain.vendor.NetworkTransferObservation
import com.whatto.bcm.domain.vendor.NetworkTransferPort
import com.whatto.bcm.domain.vendor.NetworkTransferRequest
import com.whatto.bcm.domain.vendor.NetworkTransferStatus
import com.whatto.bcm.domain.vendor.NetworkTransferSubmission
import com.whatto.bcm.domain.vendor.NetworkWalletObservation
import com.whatto.bcm.domain.vendor.NetworkWalletOwnership
import com.whatto.bcm.domain.wallet.NetworkWalletProvisioningRepository
import io.mockk.MockKAnnotations
import io.mockk.every
import io.mockk.impl.annotations.MockK
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/**
 * Dfns 출금 제출 유스케이스(계약13 "출금 제출 유스케이스 — 구현").
 * 02의 선기록·소유권은 Fireblocks 경로와 같고, **회수가 조회가 아니라 같은 본문 재제출**이라는 점만 다르다.
 */
class DfnsTransferSubmissionServiceTest {
    @MockK
    lateinit var submissions: SubmissionRecordRepository

    @MockK
    lateinit var wallets: NetworkWalletProvisioningRepository

    @MockK
    lateinit var accounts: AccountQueryService

    @MockK
    lateinit var mappings: VendorAssetMappingQueryService

    @MockK
    lateinit var depositAddresses: DepositAddressQueryService

    @MockK
    lateinit var vendor: NetworkTransferPort

    private lateinit var runner: RecordingRunner
    private lateinit var service: DfnsTransferSubmissionService

    @BeforeEach
    fun setUp() {
        MockKAnnotations.init(this)
        runner = RecordingRunner()
        service =
            DfnsTransferSubmissionService(
                submissions,
                wallets,
                accounts,
                mappings,
                depositAddresses,
                vendor,
                runner,
                ORIGIN,
                FIXED_CLOCK,
                TransactionSubmissionProperties(claimTtlSeconds = 30),
            )
        every { mappings.requiredCurrentMapping(NETWORK, "USDC") } returns MAPPING
        every { wallets.findWallet(any()) } returns WALLET
        every { submissions.findByExternalTransactionId(EXTERNAL_ID) } returns null
    }

    @Test
    fun `신규 요청은 원장을 먼저 커밋하고 벤더를 트랜잭션 밖에서 부른 뒤 벤더 전송 ID로 SUBMITTED로 바꾼다`() {
        val inserted = slot<SubmissionRecord>()
        every { submissions.insert(capture(inserted)) } answers { firstArg() }
        every { vendor.submit(any()) } returns NetworkTransferSubmission.Accepted(observation())
        every { submissions.markSubmittedByClaim(EXTERNAL_ID, any(), TRANSFER_ID, NOW) } answers {
            inserted.captured.copy(status = SubmissionStatus.SUBMITTED, vendorTransactionId = TRANSFER_ID)
        }

        val result = service.submit(command())

        assertThat(result.transactionId).isEqualTo(TRANSFER_ID)
        assertThat(inserted.captured.status).isEqualTo(SubmissionStatus.REQUESTED)
        assertThat(inserted.captured.transactionType).isEqualTo(SubmissionTransactionType.WITHDRAWAL)
        assertThat(inserted.captured.claimId).isNotBlank()
        // 회수가 "같은 본문"을 다시 만들 수 있도록 제출 시점 값을 함께 적는다(03 V28).
        assertThat(inserted.captured.vendorCanonical).isEqualTo(CANONICAL)
        // 벤더 호출은 트랜잭션 밖이다 — 외부 통신을 트랜잭션에 넣으면 커넥션이 그 시간만큼 잠긴다(02).
        assertThat(runner.insideTransaction).isFalse()
        verify(exactly = 1) { vendor.submit(any()) }
    }

    @Test
    fun `금액은 등록 정밀도로 최소 단위 정수가 되고 목적지 주소 그대로 제출한다`() {
        val request = slot<NetworkTransferRequest>()
        every { submissions.insert(any()) } answers { firstArg() }
        every { vendor.submit(capture(request)) } returns
            NetworkTransferSubmission.Accepted(observation(amountBaseUnits = "1500000"))
        every { submissions.markSubmittedByClaim(any(), any(), any(), any()) } answers { requested() }

        service.submit(command(amount = "1.5"))

        assertThat(request.captured.amountBaseUnits).isEqualTo("1500000")
        assertThat(request.captured.destinationAddress).isEqualTo(ADDRESS)
        assertThat(request.captured.vendorAssetId).isEqualTo(ASSET_KEY)
        assertThat(request.captured.vendorWalletId).isEqualTo(WALLET_ID)
        assertThat(request.captured.externalId).isEqualTo(EXTERNAL_ID)
    }

    @Test
    fun `제출 키가 50자를 넘으면 원장을 만들지도 벤더를 부르지도 않는다`() {
        assertThatThrownBy { service.submit(command(externalTransactionId = "k".repeat(51))) }
            .isInstanceOfSatisfying(InvalidRequestException::class.java) {
                assertThat(it.field).isEqualTo("externalTxId")
            }

        verify(exactly = 0) { submissions.insert(any()) }
        verify(exactly = 0) { vendor.submit(any()) }
    }

    @Test
    fun `등록 정밀도보다 자릿수가 많은 금액은 반올림하지 않고 거절한다`() {
        // USDC 6자리에 7자리 요청 — 버리든 올리든 원장과 실제 이동이 어긋난다.
        assertThatThrownBy { service.submit(command(amount = "1.0000001")) }
            .isInstanceOf(IllegalArgumentException::class.java)

        verify(exactly = 0) { submissions.insert(any()) }
        verify(exactly = 0) { vendor.submit(any()) }
    }

    @Test
    fun `등록 매핑에 정밀도가 없으면 최소 단위를 만들지 않고 거절한다`() {
        every { mappings.requiredCurrentMapping(NETWORK, "USDC") } returns MAPPING.copy(decimals = null)

        assertThatThrownBy { service.submit(command()) }
            .isInstanceOfSatisfying(InvalidRequestException::class.java) {
                assertThat(it.field).isEqualTo("decimals")
            }

        verify(exactly = 0) { vendor.submit(any()) }
    }

    @Test
    fun `이미 SUBMITTED인 같은 요청은 벤더를 부르지 않고 처음의 전송 ID를 돌려준다`() {
        every { submissions.findByExternalTransactionId(EXTERNAL_ID) } returns
            requested().copy(status = SubmissionStatus.SUBMITTED, vendorTransactionId = TRANSFER_ID)

        val result = service.submit(command())

        assertThat(result.transactionId).isEqualTo(TRANSFER_ID)
        verify(exactly = 0) { vendor.submit(any()) }
        // 매핑이 해제돼도 원래 전송 ID를 돌려줘야 한다 — 결말은 원장이 이미 알고 있다.
        verify(exactly = 0) { mappings.requiredCurrentMapping(any(), any()) }
        verify(exactly = 0) { wallets.findWallet(any()) }
    }

    @Test
    fun `REQUESTED로 남은 건은 조회가 아니라 같은 본문 재제출로 회수한다`() {
        every { submissions.findByExternalTransactionId(EXTERNAL_ID) } returns requested()
        every { submissions.tryClaimRequested(EXTERNAL_ID, any(), any(), NOW) } answers {
            requested().copy(claimId = secondArg(), claimExpiresAt = thirdArg())
        }
        val request = slot<NetworkTransferRequest>()
        every { vendor.submit(capture(request)) } returns NetworkTransferSubmission.Accepted(observation())
        every { submissions.markSubmittedByClaim(EXTERNAL_ID, any(), TRANSFER_ID, NOW) } answers {
            requested().copy(status = SubmissionStatus.SUBMITTED, vendorTransactionId = TRANSFER_ID)
        }

        // 회수 시점에 자산 매핑이 다른 키·다른 정밀도로 교체됐다 — 그래도 본문은 제출 시점 값이어야 한다.
        every { mappings.requiredCurrentMapping(NETWORK, "USDC") } returns
            MAPPING.copy(vendorAssetId = "EthereumSepolia:Native", decimals = 18)

        val result = service.submit(command())

        assertThat(result.transactionId).isEqualTo(TRANSFER_ID)
        // 회수의 전제는 "같은 본문"이다 — 저장된 canonical 값에서 재구성한 요청이 최초 제출과 같아야 한다.
        assertThat(request.captured.externalId).isEqualTo(EXTERNAL_ID)
        assertThat(request.captured.vendorAssetId).isEqualTo(ASSET_KEY)
        assertThat(request.captured.amountBaseUnits).isEqualTo("1000000")
        assertThat(request.captured.destinationAddress).isEqualTo(ADDRESS)
    }

    @Test
    fun `제출 시점 값이 없는 행은 본문을 지어내지 않고 거절해 다른 본문이 나가지 않게 한다`() {
        every { submissions.findByExternalTransactionId(EXTERNAL_ID) } returns requested().copy(vendorCanonical = null)

        assertThatThrownBy { service.submit(command()) }
            .isInstanceOf(UnprocessableRequestException::class.java)

        verify(exactly = 0) { vendor.submit(any()) }
        // 어차피 거절할 요청에 진행 중 소유권을 남기면 다음 요청이 만료까지 503을 받는다.
        verify(exactly = 0) { submissions.tryClaimRequested(any(), any(), any(), any()) }
    }

    @Test
    fun `읽은 뒤 다른 요청이 FAILED로 바꿨으면 금지된 재제출을 열지 않고 거절한다`() {
        // 공용 tryClaim은 FAILED를 REQUESTED로 되살린다 — Dfns는 REQUESTED 전용 소유권만 쓰고,
        // 못 잡았을 때 최신 상태가 FAILED면 그 사실을 그대로 알린다.
        every { submissions.findByExternalTransactionId(EXTERNAL_ID) } returnsMany
            listOf(requested(), requested().copy(status = SubmissionStatus.FAILED))
        every { submissions.tryClaimRequested(EXTERNAL_ID, any(), any(), NOW) } returns null

        assertThatThrownBy { service.submit(command()) }
            .isInstanceOf(UnprocessableRequestException::class.java)

        verify(exactly = 0) { vendor.submit(any()) }
        verify(exactly = 0) { submissions.tryClaim(any(), any(), any(), any()) }
    }

    @Test
    fun `회수 재제출의 409는 FAILED로 굳히지 않고 REQUESTED를 유지한다`() {
        every { submissions.findByExternalTransactionId(EXTERNAL_ID) } returns requested()
        every { submissions.tryClaimRequested(EXTERNAL_ID, any(), any(), NOW) } answers {
            requested().copy(claimId = secondArg(), claimExpiresAt = thirdArg())
        }
        every { vendor.submit(any()) } returns NetworkTransferSubmission.Conflict("xfr-other", CONFLICT_BODY)

        // 진행 중 재제출이 409로 보일 가능성이 아직 수용 항목이라, 종결로 적으면 나간 전송에 확정 거절을 돌려주게 된다.
        assertThatThrownBy { service.submit(command()) }
            .isInstanceOfSatisfying(VendorApiException::class.java) {
                // 자금이 나갔는지 모르는 구간이라 증적을 버리지 않는다.
                assertThat(it.httpStatus).isEqualTo(409)
                assertThat(it.responseBody()).isEqualTo(CONFLICT_BODY)
            }

        verify(exactly = 0) { submissions.markFailedByClaim(any(), any(), any()) }
    }

    @Test
    fun `소유권을 못 잡으면 기다리지 않고 재시도 안내로 즉시 답한다`() {
        every { submissions.findByExternalTransactionId(EXTERNAL_ID) } returns
            requested().copy(claimId = "other", claimExpiresAt = "20260917090030")
        every { submissions.tryClaimRequested(EXTERNAL_ID, any(), any(), NOW) } returns null

        assertThatThrownBy { service.submit(command()) }
            .isInstanceOfSatisfying(SubmissionInProgressException::class.java) {
                assertThat(it.retryAfterSeconds).isGreaterThan(0)
            }

        verify(exactly = 0) { vendor.submit(any()) }
    }

    @Test
    fun `FAILED 재시도는 거절하고 벤더를 부르지 않는다`() {
        every { submissions.findByExternalTransactionId(EXTERNAL_ID) } returns requested().copy(status = SubmissionStatus.FAILED)

        assertThatThrownBy { service.submit(command()) }
            .isInstanceOfSatisfying(UnprocessableRequestException::class.java) {
                assertThat(it.key).isEqualTo(EXTERNAL_ID)
            }

        // 새 키를 자동 발급해 다시 보내지 않는다 — 벤더 Failed는 체인 제출 여부를 확정하지 못한다.
        verify(exactly = 0) { vendor.submit(any()) }
        verify(exactly = 0) { submissions.tryClaimRequested(any(), any(), any(), any()) }
    }

    @Test
    fun `같은 키에 다른 내용이면 벤더를 부르기 전에 409로 막는다`() {
        every { submissions.findByExternalTransactionId(EXTERNAL_ID) } returns
            requested().copy(amount = "2", requestHash = "f".repeat(64))

        assertThatThrownBy { service.submit(command()) }.isInstanceOf(ConflictException::class.java)

        verify(exactly = 0) { vendor.submit(any()) }
    }

    @Test
    fun `표식 있는 409는 FAILED로 굳히고 행을 지우지 않는다`() {
        every { submissions.insert(any()) } answers { firstArg() }
        every { vendor.submit(any()) } returns NetworkTransferSubmission.Conflict("xfr-other", CONFLICT_BODY)
        every { submissions.markFailedByClaim(EXTERNAL_ID, any(), NOW) } answers {
            requested().copy(status = SubmissionStatus.FAILED)
        }

        assertThatThrownBy { service.submit(command()) }
            .isInstanceOfSatisfying(RelayRejectedException::class.java) {
                // 확정 거절이어도 벤더 응답 원문은 원인으로 남긴다.
                assertThat(it.cause).isInstanceOfSatisfying(VendorApiException::class.java) { vendor ->
                    assertThat(vendor.httpStatus).isEqualTo(409)
                    assertThat(vendor.responseBody()).isEqualTo(CONFLICT_BODY)
                }
            }

        verify(exactly = 1) { submissions.markFailedByClaim(EXTERNAL_ID, any(), NOW) }
    }

    @Test
    fun `벤더 오류·무응답은 REQUESTED를 그대로 두고 전파한다`() {
        every { submissions.insert(any()) } answers { firstArg() }
        every { vendor.submit(any()) } throws VendorApiException("submitTransfer", null, null)

        assertThatThrownBy { service.submit(command()) }.isInstanceOf(VendorApiException::class.java)

        // 나갔는지 모르므로 FAILED로 굳히지 않는다(02).
        verify(exactly = 0) { submissions.markFailedByClaim(any(), any(), any()) }
        verify(exactly = 0) { submissions.markSubmittedByClaim(any(), any(), any(), any()) }
    }

    @Test
    fun `돌려받은 관찰이 내 요청과 다르면 원장을 SUBMITTED로 바꾸지 않는다`() {
        every { submissions.insert(any()) } answers { firstArg() }
        every { vendor.submit(any()) } returns
            NetworkTransferSubmission.Accepted(observation(destinationAddress = "0x9999999999999999999999999999999999999999"))

        assertThatThrownBy { service.submit(command()) }.isInstanceOf(ConflictException::class.java)

        verify(exactly = 0) { submissions.markSubmittedByClaim(any(), any(), any(), any()) }
    }

    @Test
    fun `계정 목적지는 발급된 주소로 해소해 보내고 원장에는 계정을 남긴다`() {
        // Dfns 본문은 주소만 받으므로 우리가 해소한다. 원장의 논리 목적지까지 주소로 덮으면 어느 계정으로 보냈는지를 잃는다(계약13).
        val stored = slot<SubmissionRecord>()
        val sent = slot<NetworkTransferRequest>()
        every { accounts.requiredAccount("acct-2") } returns AccountFixture.fixture("acct-2")
        every { depositAddresses.find("acct-2", NETWORK, "USDC") } returns
            DepositAddress("acct-2", NETWORK, "USDC", OTHER_ADDRESS, NOW)
        every { submissions.insert(capture(stored)) } answers { firstArg() }
        every { vendor.submit(capture(sent)) } returns
            NetworkTransferSubmission.Accepted(observation(destinationAddress = OTHER_ADDRESS))
        every { submissions.markSubmittedByClaim(EXTERNAL_ID, any(), TRANSFER_ID, NOW) } answers {
            stored.captured.copy(status = SubmissionStatus.SUBMITTED, vendorTransactionId = TRANSFER_ID)
        }

        service.submit(command(recipient = TransactionSubmissionRecipient.Account("acct-2")))

        assertThat(sent.captured.destinationAddress).isEqualTo(OTHER_ADDRESS)
        assertThat(stored.captured.recipientType).isEqualTo(SubmissionRecipientType.ACCOUNT)
        assertThat(stored.captured.recipientValue).isEqualTo("acct-2")
        assertThat(stored.captured.transactionType).isEqualTo(SubmissionTransactionType.INTERNAL)
        // 회수가 읽을 자리다 — 논리 목적지가 accountId라 그대로 두면 주소 자리에 계정이 나간다(03 V30).
        assertThat(stored.captured.vendorCanonical?.destinationAddress).isEqualTo(OTHER_ADDRESS)
    }

    @Test
    fun `목적지 계정에 그 자산 주소가 없으면 원장을 만들지 않고 보류로 거절한다`() {
        // 형식·계정·자산은 유효하고 목적지의 준비 상태 때문에 못 보내는 것이다 — 주소 발급 뒤 같은 키로 다시 제출할 수 있어야 한다.
        every { accounts.requiredAccount("acct-2") } returns AccountFixture.fixture("acct-2")
        every { depositAddresses.find("acct-2", NETWORK, "USDC") } returns null

        assertThatThrownBy { service.submit(command(recipient = TransactionSubmissionRecipient.Account("acct-2"))) }
            .isInstanceOf(UnprocessableRequestException::class.java)

        verify(exactly = 0) { submissions.insert(any()) }
        verify(exactly = 0) { vendor.submit(any()) }
    }

    @Test
    fun `자산이 미지원이면 주소가 없어도 보류가 아니라 미지원으로 거절한다`() {
        // 미지원 자산은 주소를 발급해도 해소되지 않는다 — 목적지 준비 상태(422)가 아니라 요청값이 지원 범위 밖(400)이다(02).
        every { accounts.requiredAccount("acct-2") } returns AccountFixture.fixture("acct-2")
        every { mappings.requiredCurrentMapping(NETWORK, "USDC") } throws AssetNotSupportedException(NETWORK, "USDC")
        every { depositAddresses.find("acct-2", NETWORK, "USDC") } returns null

        assertThatThrownBy { service.submit(command(recipient = TransactionSubmissionRecipient.Account("acct-2"))) }
            .isInstanceOf(AssetNotSupportedException::class.java)

        verify(exactly = 0) { submissions.insert(any()) }
        verify(exactly = 0) { vendor.submit(any()) }
    }

    @Test
    fun `목적지 계정 자체가 없으면 주소 미발급과 달리 찾을 수 없음으로 거절한다`() {
        // 계정 없음(404)과 주소 미발급(422)은 다른 답이다 — 계정이 없으면 주소를 발급해도 해소되지 않는다.
        // Fireblocks는 이미 이렇게 답한다. 같은 요청이 제공자에 따라 다르게 답하면 안 된다(계약13).
        every { accounts.requiredAccount("acct-2") } throws AccountNotFoundException("acct-2")

        assertThatThrownBy { service.submit(command(recipient = TransactionSubmissionRecipient.Account("acct-2"))) }
            .isInstanceOf(AccountNotFoundException::class.java)

        verify(exactly = 0) { submissions.insert(any()) }
        verify(exactly = 0) { vendor.submit(any()) }
    }

    @Test
    fun `자기 계정으로 보내는 요청은 원장을 만들기 전에 거절한다`() {
        // 같은 주소로 가는 온체인 전송이라 잔액은 그대로고 가스만 태운다. 시간이 지나도 해소되지 않는 요청값 모순이다(02 공통 정책).
        assertThatThrownBy { service.submit(command(recipient = TransactionSubmissionRecipient.Account(SENDER_ID))) }
            .isInstanceOfSatisfying(InvalidRequestException::class.java) {
                assertThat(it.field).isEqualTo("recipient")
            }

        verify(exactly = 0) { submissions.insert(any()) }
        verify(exactly = 0) { vendor.submit(any()) }
    }

    @Test
    fun `자기 계정의 FAILED 재시도는 제공자 재시도 규칙보다 앞서 400으로 답한다`() {
        // 자기 계정은 요청값 자체의 모순이라 제공자와 무관하게 400이다 — Dfns의 일반 FAILED 422와 갈리지 않는다(02).
        every { submissions.findByExternalTransactionId(EXTERNAL_ID) } returns
            requested().copy(
                status = SubmissionStatus.FAILED,
                transactionType = SubmissionTransactionType.INTERNAL,
                recipientType = SubmissionRecipientType.ACCOUNT,
                recipientValue = SENDER_ID,
                requestHash = SELF_TRANSFER_HASH,
            )

        assertThatThrownBy { service.submit(command(recipient = TransactionSubmissionRecipient.Account(SENDER_ID))) }
            .isInstanceOfSatisfying(InvalidRequestException::class.java) {
                assertThat(it.field).isEqualTo("recipient")
            }

        verify(exactly = 0) { vendor.submit(any()) }
    }

    @Test
    fun `화이트리스트 지갑 목적지는 아직 받지 않는다`() {
        assertThatThrownBy { service.submit(command(recipient = TransactionSubmissionRecipient.Whitelisted("wl-1"))) }
            .isInstanceOfSatisfying(InvalidRequestException::class.java) {
                assertThat(it.field).isEqualTo("recipient")
            }

        verify(exactly = 0) { vendor.submit(any()) }
    }

    @Test
    fun `발급된 네트워크 지갑이 없으면 제출하지 않는다`() {
        every { wallets.findWallet(any()) } returns null

        assertThatThrownBy { service.submit(command()) }
            .isInstanceOfSatisfying(InvalidRequestException::class.java) {
                assertThat(it.field).isEqualTo("senderAccountId")
            }

        verify(exactly = 0) { submissions.insert(any()) }
    }

    private fun command(
        externalTransactionId: String = EXTERNAL_ID,
        amount: String = "1",
        recipient: TransactionSubmissionRecipient = TransactionSubmissionRecipient.Address(ADDRESS),
    ): TransactionSubmissionCommand =
        TransactionSubmissionCommand(
            externalTransactionId = externalTransactionId,
            senderAccountId = SENDER_ID,
            recipient = recipient,
            network = NETWORK,
            symbol = "USDC",
            amount = amount,
            note = null,
            travelRuleMessage = null,
        )

    private fun requested(): SubmissionRecord =
        SubmissionRecord(
            externalTransactionId = EXTERNAL_ID,
            requestHash = REQUEST_HASH,
            hashVersion = "v1",
            status = SubmissionStatus.REQUESTED,
            claimId = null,
            claimExpiresAt = null,
            transactionType = SubmissionTransactionType.WITHDRAWAL,
            vendorTransactionId = null,
            senderAccountId = SENDER_ID,
            recipientType = SubmissionRecipientType.ADDRESS,
            recipientValue = ADDRESS,
            network = NETWORK,
            symbol = "USDC",
            amount = "1",
            requestedAt = NOW,
            respondedAt = null,
            vendorCanonical = CANONICAL,
        )

    private fun observation(
        destinationAddress: String = ADDRESS,
        amountBaseUnits: String = "1000000",
    ): NetworkTransferObservation =
        NetworkTransferObservation(
            transferId = TRANSFER_ID,
            network = "EthereumSepolia",
            vendorWalletId = WALLET_ID,
            vendorAssetId = ASSET_KEY,
            destinationAddress = destinationAddress,
            amountBaseUnits = amountBaseUnits,
            status = NetworkTransferStatus.PENDING,
            externalId = EXTERNAL_ID,
            transactionHash = null,
            requestedAt = "2026-09-17T09:00:00Z",
            failureReason = null,
        )

    /** 벤더 호출이 트랜잭션 안에서 일어나지 않았음을 확인하려고 실행 중 여부를 기록한다. */
    private class RecordingRunner : TransactionRunner {
        var insideTransaction: Boolean = false
            private set

        override fun <T> run(block: () -> T): T {
            insideTransaction = true
            return try {
                block()
            } finally {
                insideTransaction = false
            }
        }
    }

    private companion object {
        const val EXTERNAL_ID = "ext-dfns-1"
        const val SENDER_ID = "acct-1"
        const val NETWORK = "ETHEREUM_SEPOLIA"
        const val WALLET_ID = "wa-1"
        const val TRANSFER_ID = "xfr-1"
        const val ASSET_KEY = "EthereumSepolia:Erc20:0xa0b86991c6218b36c1d19d4a2e9eb0ce3606eb48"
        const val ADDRESS = "0x1111111111111111111111111111111111111111"
        const val OTHER_ADDRESS = "0x2222222222222222222222222222222222222222"
        val SELF_TRANSFER_HASH =
            com.whatto.bcm.support.submission.SubmissionRequestHashes
                .v1("ACCOUNT", SENDER_ID, "ACCOUNT", SENDER_ID, NETWORK, "USDC", "1")
                .requestHash
        const val NOW = "20260917090000"
        val CONFLICT_BODY: ByteArray = """{"error":{"details":{"duplicate":{"id":"xfr-other"}}}}""".toByteArray()
        val REQUEST_HASH =
            com.whatto.bcm.support.submission.SubmissionRequestHashes
                .v1("ACCOUNT", SENDER_ID, "ADDRESS", ADDRESS, NETWORK, "USDC", "1")
                .requestHash

        val FIXED_CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-17T09:00:00Z"), ZoneId.of("UTC"))
        val ORIGIN = ProviderOrigin("test-dfns-origin", "dfns", "dfns", "test-dfns-platform", "test-dfns-organization", "TESTNET")
        val MAPPING =
            VendorAssetMapping(
                network = NETWORK,
                symbol = "USDC",
                vendorAssetId = ASSET_KEY,
                contractAddress = "0xA0b86991c6218b36c1d19D4a2e9Eb0cE3606eB48",
                registeredAt = "20260917000000",
                registeredByEmployeeNo = "000001",
                registeredByBranchCode = "0001",
                decimals = 6,
            )
        val CANONICAL =
            SubmissionVendorCanonical(
                vendorWalletId = WALLET_ID,
                vendorAssetId = ASSET_KEY,
                amountBaseUnits = "1000000",
                decimals = 6,
                destinationAddress = ADDRESS,
            )
        val WALLET =
            NetworkWalletObservation(
                origin = ORIGIN,
                network = NETWORK,
                vendorWalletId = WALLET_ID,
                correlationId = null,
                ownership = NetworkWalletOwnership.ORGANIZATION,
                address = ADDRESS,
            )
    }
}
