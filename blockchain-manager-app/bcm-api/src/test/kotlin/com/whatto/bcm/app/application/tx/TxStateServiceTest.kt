package com.whatto.bcm.app.application.tx

import com.whatto.bcm.domain.asset.ChainModel
import com.whatto.bcm.domain.asset.VendorBlockchainCatalog
import com.whatto.bcm.domain.asset.VendorBlockchainCatalogRepository
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.tx.TxObservation
import com.whatto.bcm.domain.tx.TxRecord
import com.whatto.bcm.domain.tx.TxRecordRepository
import com.whatto.bcm.domain.tx.TxStatus
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class TxStateServiceTest {
    @Test
    fun `상태가 진행되면 이전 막힘 경보 표식을 지운다`() {
        val repository = RecordingTxRecords(record())

        val outcome = service(repository).observeConsistently(VENDOR_TX_ID, observation(status = TxStatus.CONFIRMED), false)

        assertThat(applied(outcome).record.stallAlertedAt).isNull()
    }

    @Test
    fun `진행 없는 중복 관찰은 막힘 경보 표식을 유지한다`() {
        val repository = RecordingTxRecords(record())

        val outcome = service(repository).observeConsistently(VENDOR_TX_ID, observation(status = TxStatus.SUBMITTED), false)

        assertThat(applied(outcome).record.stallAlertedAt).isEqualTo("20260807113000")
    }

    @Test
    fun `이미 적힌 금액과 다른 금액이 오면 상태를 진행시키지 않고 충돌로 돌려준다`() {
        // 금액만 빼고 상태를 옮기면 확정을 수용하면서 공개 응답에는 다른 값이 남는다(03 V32).
        val repository = RecordingTxRecords(record(amount = "1.5"))

        val outcome =
            service(repository).observeConsistently(
                VENDOR_TX_ID,
                observation(status = TxStatus.CONFIRMED, amount = "2.5"),
                false,
            )

        assertThat(outcome).isInstanceOfSatisfying(TxObservationOutcome.Conflict::class.java) {
            assertThat(it.detail.field).isEqualTo("amount")
            // 인박스에 남길 사유에는 금액이 들어가지 않는다.
            assertThat(it.detail.safeReason).contains("field=amount").doesNotContain("1.5").doesNotContain("2.5")
        }
        // 전이가 일어나지 않았다 — 상태도 컨펌도 그대로다.
        assertThat(repository.current.lastPublishedStatus).isEqualTo(TxStatus.SUBMITTED)
    }

    @Test
    fun `표기만 다른 같은 금액은 충돌이 아니다`() {
        val repository = RecordingTxRecords(record(amount = "1.50"))

        val outcome =
            service(repository).observeConsistently(
                VENDOR_TX_ID,
                observation(status = TxStatus.CONFIRMED, amount = "1.5"),
                false,
            )

        assertThat(applied(outcome).record.lastPublishedStatus).isEqualTo(TxStatus.CONFIRMED)
    }

    @Test
    fun `EVM 주소는 대소문자가 달라도 같은 주소로 본다`() {
        // 체크섬 표기와 소문자 표기가 섞여 들어와도 격리하지 않는다 — 계정 모델이 비교 규칙을 가른다(03 chain_mdl_dvcd).
        val repository = RecordingTxRecords(record(destinationAddress = CHECKSUM_ADDRESS))

        val outcome =
            service(repository, ChainModel.EVM).observeConsistently(
                VENDOR_TX_ID,
                observation(status = TxStatus.CONFIRMED, destinationAddress = CHECKSUM_ADDRESS.lowercase()),
                false,
            )

        assertThat(applied(outcome).record.lastPublishedStatus).isEqualTo(TxStatus.CONFIRMED)
    }

    @Test
    fun `EVM이라도 20바이트 주소 형식이 아니면 대소문자를 무시하지 않는다`() {
        // 형식 검증 뒤에 비교한다(03 V32) — 주소가 아닌 값끼리 표기만 달라도 같다고 하면 안 된다.
        val repository = RecordingTxRecords(record(destinationAddress = "0xAbCd"))

        val outcome =
            service(repository, ChainModel.EVM).observeConsistently(
                VENDOR_TX_ID,
                observation(status = TxStatus.CONFIRMED, destinationAddress = "0xabcd"),
                false,
            )

        assertThat(outcome).isInstanceOfSatisfying(TxObservationOutcome.Conflict::class.java) {
            assertThat(it.detail.field).isEqualTo("destinationAddress")
        }
    }

    @Test
    fun `계정 모델을 모르면 정확히 같을 때만 같다고 본다`() {
        // fail-closed — 모르는 채로 같다고 보면 다른 주소의 관찰이 조용히 섞인다.
        val repository = RecordingTxRecords(record(destinationAddress = CHECKSUM_ADDRESS))

        val outcome =
            service(repository, chainModel = null).observeConsistently(
                VENDOR_TX_ID,
                observation(status = TxStatus.CONFIRMED, destinationAddress = CHECKSUM_ADDRESS.lowercase()),
                false,
            )

        assertThat(outcome).isInstanceOfSatisfying(TxObservationOutcome.Conflict::class.java) {
            assertThat(it.detail.field).isEqualTo("destinationAddress")
        }
    }

    @Test
    fun `정밀도가 바뀌어 사람 단위 금액이 달라져도 같은 최소 단위면 격리하지 않는다`() {
        // V34가 근거를 남긴 이유다 — 재처리는 현재 매핑이 아니라 저장된 최소 단위로 판정한다.
        val repository = RecordingTxRecords(record(amount = "1.5", baseUnits = "1500000", decimals = 6))

        val outcome =
            service(repository).observeConsistently(
                VENDOR_TX_ID,
                // 매핑 정밀도가 6→8로 바뀐 뒤 같은 사건을 다시 본 모습이다.
                observation(status = TxStatus.CONFIRMED, amount = "0.015", baseUnits = "1500000", decimals = 8),
                false,
            )

        assertThat(applied(outcome).record.lastPublishedStatus).isEqualTo(TxStatus.CONFIRMED)
        // 원장 금액은 최초값 그대로다 — 새 정밀도의 파생값으로 덮지 않는다.
        assertThat(repository.current.amount).isEqualTo("1.5")
    }

    @Test
    fun `최소 단위가 다르면 다른 관찰이다`() {
        val repository = RecordingTxRecords(record(amount = "1.5", baseUnits = "1500000", decimals = 6))

        val outcome =
            service(repository).observeConsistently(
                VENDOR_TX_ID,
                observation(status = TxStatus.CONFIRMED, amount = "1.5", baseUnits = "2500000", decimals = 6),
                false,
            )

        assertThat(outcome).isInstanceOfSatisfying(TxObservationOutcome.Conflict::class.java) {
            assertThat(it.detail.field).isEqualTo("amountBaseUnits")
        }
    }

    @Test
    fun `판정 뒤 다른 트랜잭션이 만든 행과 어긋나면 되돌린다`() {
        // SELECT FOR UPDATE 는 **없는 행을 잠그지 않는다** — 판정 때 없던 행이 적용 직전에 생길 수 있다.
        val repository = RecordingTxRecords(record = null)
        val checked =
            service(repository).lockAndCheck(VENDOR_TX_ID, observation(status = TxStatus.CONFIRMED, amount = "2.5"))
                as TxObservationCheck.Consistent
        // 경합한 트랜잭션이 다른 금액으로 같은 행을 만들어 커밋했다.
        repository.arrive(record(amount = "1.5"))

        assertThatThrownBy { service(repository).applyCheckedReread(checked, successEvidence = false) }
            .isInstanceOf(ConflictException::class.java)
        // 전이는 일어나지 않았다 — 트랜잭션이 통째로 물리고 인박스가 다시 본다.
        assertThat(repository.writes).isZero()
    }

    @Test
    fun `잠근 행과 다른 root 를 가리키는 토큰은 적용하지 않는다`() {
        val repository = RecordingTxRecords(record())
        val forged = TxObservationCheck.Consistent("other-tx", record(), observation(status = TxStatus.CONFIRMED))

        assertThatThrownBy { service(repository).applyChecked(forged, successEvidence = false) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("not bound")
    }

    @Test
    fun `잠그고 검사만 하는 단계는 아무것도 쓰지 않는다`() {
        // 한 트랜잭션이 여러 행을 다룰 때 전부 검사한 뒤에 쓰기를 시작할 수 있어야 한다.
        val repository = RecordingTxRecords(record())

        val check = service(repository).lockAndCheck(VENDOR_TX_ID, observation(status = TxStatus.CONFIRMED))

        assertThat(check).isInstanceOf(TxObservationCheck.Consistent::class.java)
        assertThat(repository.writes).isZero()
        assertThat(repository.current.lastPublishedStatus).isEqualTo(TxStatus.SUBMITTED)
    }

    private fun service(
        repository: TxRecordRepository,
        chainModel: ChainModel? = ChainModel.EVM,
    ) = TxStateService(repository, FixedBlockchains(chainModel))

    private fun applied(outcome: TxObservationOutcome) = (outcome as TxObservationOutcome.Applied).change

    private fun record(
        amount: String? = null,
        destinationAddress: String? = null,
        baseUnits: String? = null,
        decimals: Int? = null,
    ) = TxRecord(
        vendorTxId = VENDOR_TX_ID,
        externalTxId = "wd-1",
        accountId = "account-1",
        network = NETWORK,
        symbol = "USDC",
        lastPublishedStatus = TxStatus.SUBMITTED,
        confirmationCount = 0,
        stallAlertedAt = "20260807113000",
        // 관찰을 이미 통과한 행이다 — 첫 관찰 전 행만 vendorCreatedAt 을 비운다(03 V32).
        vendorCreatedAt = "20260807110000",
        firstDetectedAt = "20260807110000",
        lastChangedAt = "20260807110000",
        amount = amount,
        destinationAddress = destinationAddress,
        amountBaseUnits = baseUnits,
        amountDecimals = decimals,
    )

    private fun observation(
        status: TxStatus,
        amount: String? = null,
        destinationAddress: String? = null,
        baseUnits: String? = null,
        decimals: Int? = null,
    ) = TxObservation(
        vendorTransactionId = VENDOR_TX_ID,
        externalTransactionId = "wd-1",
        accountId = "account-1",
        network = NETWORK,
        symbol = "USDC",
        transactionHash = null,
        status = status,
        confirmationCount = 0,
        vendorSubStatus = null,
        vendorNetworkStatus = null,
        observedAt = "20260807120000",
        observedAmount = amount,
        observedDestinationAddress = destinationAddress,
        observedAmountBaseUnits = baseUnits,
        observedAmountDecimals = decimals,
    )

    private companion object {
        const val VENDOR_TX_ID = "tx-1"
        const val NETWORK = "ETHEREUM"
        const val CHECKSUM_ADDRESS = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"
    }
}

/** 네트워크 하나의 계정 모델만 답하는 카탈로그 — 주소 비교 규칙이 어디서 오는지만 시험한다. */
private class FixedBlockchains(
    private val chainModel: ChainModel?,
) : VendorBlockchainCatalogRepository by mockk() {
    override fun findByNetwork(network: String): VendorBlockchainCatalog =
        VendorBlockchainCatalog(
            candidateId = "blkc-1",
            network = network,
            chainId = 1,
            displayName = network,
            testnet = false,
            deprecated = false,
            syncedAt = "20260807110000",
            chainModel = chainModel,
        )
}

private class RecordingTxRecords(
    private var record: TxRecord?,
) : TxRecordRepository {
    var writes = 0
        private set

    val current: TxRecord get() = checkNotNull(record)

    /** 경합한 다른 트랜잭션이 같은 root 를 만들어 커밋한 모습. */
    fun arrive(arrived: TxRecord) {
        record = arrived
    }

    override fun insert(txRecord: TxRecord): TxRecord = txRecord.also { record = it }.also { writes++ }

    override fun update(txRecord: TxRecord): TxRecord = txRecord.also { record = it }.also { writes++ }

    override fun updatePhysicalWinner(
        txRecord: TxRecord,
        previousActiveVendorTxId: String,
    ): TxRecord = txRecord.also { record = it }.also { writes++ }

    override fun findByVendorTxId(vendorTxId: String): TxRecord? = record

    override fun findByActiveVendorTxId(activeVendorTxId: String): TxRecord? = record

    override fun findByVendorTxIdForUpdate(vendorTxId: String): TxRecord? = record

    override fun findByActiveVendorTxIdForUpdate(activeVendorTxId: String): TxRecord? = record

    override fun findByExternalTxId(externalTxId: String): TxRecord? = record

    override fun lockNetworkTransactionHash(
        network: String,
        transactionHash: String,
    ) = Unit

    override fun findByNetworkAndTransactionHash(
        network: String,
        transactionHash: String,
    ): List<TxRecord> = listOfNotNull(record).filter { it.network == network && it.transactionHash == transactionHash }
}
