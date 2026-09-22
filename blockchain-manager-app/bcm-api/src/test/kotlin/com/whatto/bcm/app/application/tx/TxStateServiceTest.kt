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
    fun `한쪽에만 환산 근거가 있으면 사람 단위 금액으로 판정한다`() {
        // 근거가 불완전한 채로 최소 단위 동일성을 추정하지 않는다 — 값이 다르면 보수적으로 충돌이다.
        val repository = RecordingTxRecords(record(amount = "1.5", baseUnits = "1500000", decimals = 6))

        val consistent =
            service(repository).observeConsistently(
                VENDOR_TX_ID,
                observation(status = TxStatus.CONFIRMED, amount = "1.50"),
                false,
            )
        assertThat(applied(consistent).record.lastPublishedStatus).isEqualTo(TxStatus.CONFIRMED)

        val conflicting =
            service(RecordingTxRecords(record(amount = "1.5", baseUnits = "1500000", decimals = 6))).observeConsistently(
                VENDOR_TX_ID,
                observation(status = TxStatus.CONFIRMED, amount = "2.5"),
                false,
            )
        assertThat(conflicting).isInstanceOfSatisfying(TxObservationOutcome.Conflict::class.java) {
            assertThat(it.detail.field).isEqualTo("amount")
        }
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
    fun `호출자의 쓰기는 판정을 통과한 뒤에만 부른다`() {
        // 워커의 즉시 격리는 같은 트랜잭션에서 일어난다 — 판정 전에 쓰면 그 쓰기가 격리와 함께 커밋된다.
        val repository = RecordingTxRecords(record(amount = "1.5"))
        var between = 0

        val outcome =
            service(repository).observeConsistentlyAround(
                VENDOR_TX_ID,
                observation(status = TxStatus.CONFIRMED, amount = "2.5"),
                successEvidence = false,
            ) { between++ }

        assertThat(outcome).isInstanceOf(TxObservationOutcome.Conflict::class.java)
        assertThat(between).isZero()
        assertThat(repository.writes).isZero()
    }

    @Test
    fun `판정 뒤 다른 트랜잭션이 만든 행과 어긋나면 되돌린다`() {
        // SELECT FOR UPDATE 는 **없는 행을 잠그지 않는다** — 판정 때 없던 행이 적용 직전에 생길 수 있다.
        val repository = RecordingTxRecords(record = null)

        assertThatThrownBy {
            service(repository).observeConsistentlyAround(
                VENDOR_TX_ID,
                observation(status = TxStatus.CONFIRMED, amount = "2.5"),
                successEvidence = false,
            ) {
                // 경합한 트랜잭션이 다른 금액으로 같은 행을 만들어 커밋했다.
                repository.arrive(record(amount = "1.5"))
            }
        }.isInstanceOf(ConflictException::class.java)
        // 전이는 일어나지 않았다 — 트랜잭션이 통째로 물리고 워커가 다시 본다.
        assertThat(repository.writes).isZero()
    }

    @Test
    fun `여러 행 중 하나가 어긋나면 앞 행에도 적용하지 않는다`() {
        val repository = RecordingTxRecords(record(amount = "1.5"))

        val outcome =
            service(repository).observeAllConsistently(
                listOf(
                    TxObservationRequest(VENDOR_TX_ID, observation(status = TxStatus.CONFIRMED, amount = "1.5")),
                    TxObservationRequest(
                        "tx-2",
                        observation(status = TxStatus.CONFIRMED, amount = "2.5", vendorTransactionId = "tx-2"),
                    ),
                ),
            )

        assertThat(outcome).isInstanceOfSatisfying(TxObservationBatchOutcome.Conflict::class.java) {
            assertThat(it.detail.field).isEqualTo("amount")
        }
        assertThat(repository.writes).isZero()
    }

    @Test
    fun `같은 root 를 두 번 받으면 거절한다`() {
        // 둘 다 같은(오래된) 행으로 검사하고 두 번 전이하면 뒤 갱신이 앞 갱신을 덮거나 같은 상태가 두 번 발행된다.
        val repository = RecordingTxRecords(record(amount = "1.5"))

        assertThatThrownBy {
            service(repository).observeAllConsistently(
                listOf(
                    TxObservationRequest(VENDOR_TX_ID, observation(status = TxStatus.CONFIRMED, amount = "1.5")),
                    TxObservationRequest(VENDOR_TX_ID, observation(status = TxStatus.FINALIZED, amount = "1.5")),
                ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("duplicate roots")
        assertThat(repository.writes).isZero()
    }

    @Test
    fun `빈 목록은 아무것도 하지 않는다`() {
        val repository = RecordingTxRecords(record(amount = "1.5"))

        val outcome = service(repository).observeAllConsistently(emptyList())

        assertThat(outcome).isInstanceOfSatisfying(TxObservationBatchOutcome.Applied::class.java) {
            assertThat(it.changes).isEmpty()
        }
        assertThat(repository.writes).isZero()
    }

    @Test
    fun `호출자의 쓰기가 이 경계를 다시 부르면 그 자리에서 드러낸다`() {
        // 재진입하면 판정과 재검사 사이에 다른 전이가 끼어들어 "검사 → 쓰기 → 전이" 순서가 깨진다.
        val repository = RecordingTxRecords(record(amount = "1.5"))
        val service = service(repository)

        assertThatThrownBy {
            service.observeConsistentlyAround(
                VENDOR_TX_ID,
                observation(status = TxStatus.CONFIRMED, amount = "1.5"),
                successEvidence = false,
            ) {
                service.observeConsistently(VENDOR_TX_ID, observation(status = TxStatus.FINALIZED), false)
            }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("re-enter")
    }

    @Test
    fun `호출자의 쓰기가 실패하면 전이하지 않고 그대로 올린다`() {
        // 되돌리는 것은 호출자의 트랜잭션 경계다 — 이 경계가 삼켜 반쪽 상태를 만들지 않는다.
        val repository = RecordingTxRecords(record(amount = "1.5"))

        assertThatThrownBy {
            service(repository).observeConsistentlyAround(
                VENDOR_TX_ID,
                observation(status = TxStatus.CONFIRMED, amount = "1.5"),
                successEvidence = false,
            ) { throw IllegalStateException("submission ledger write failed") }
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("submission ledger write failed")
        assertThat(repository.writes).isZero()
    }

    @Test
    fun `한 호출 안에서 네트워크 모델을 한 번만 읽는다`() {
        // 행 잠금을 쥔 채 같은 마스터 행으로 왕복을 늘리지 않는다. 모델은 채택 때 정해지고 바뀌지 않는다(03 V35).
        val blockchains = CountingBlockchains(ChainModel.EVM)

        // 여러 행: 후보 수만큼 읽지 않는다.
        TxStateService(RecordingTxRecords(record(amount = "1.5")), blockchains).observeAllConsistently(
            listOf(
                TxObservationRequest(VENDOR_TX_ID, observation(status = TxStatus.CONFIRMED, amount = "1.5")),
                TxObservationRequest(
                    "tx-2",
                    observation(status = TxStatus.CONFIRMED, amount = "1.50", vendorTransactionId = "tx-2"),
                ),
            ),
        )
        assertThat(blockchains.reads).isEqualTo(1)

        // 재검사가 있는 경로: 판정과 재검사가 같은 값을 쓴다.
        blockchains.reset()
        TxStateService(RecordingTxRecords(record(amount = "1.5")), blockchains).observeConsistentlyAround(
            VENDOR_TX_ID,
            observation(status = TxStatus.CONFIRMED, amount = "1.5"),
            successEvidence = false,
        ) {}
        assertThat(blockchains.reads).isEqualTo(1)

        // 서로 다른 네트워크가 섞이면 네트워크마다 한 번이다.
        blockchains.reset()
        TxStateService(RecordingTxRecords(record(amount = "1.5")), blockchains).observeAllConsistently(
            listOf(
                TxObservationRequest(VENDOR_TX_ID, observation(status = TxStatus.CONFIRMED, amount = "1.5")),
                TxObservationRequest(
                    "tx-2",
                    observation(status = TxStatus.CONFIRMED, amount = "1.5", network = "BASE", vendorTransactionId = "tx-2"),
                ),
            ),
        )
        assertThat(blockchains.reads).isEqualTo(2)

        // 첫 관찰은 비교할 행이 없어 아예 읽지 않는다.
        blockchains.reset()
        TxStateService(RecordingTxRecords(record = null), blockchains).observeConsistently(
            VENDOR_TX_ID,
            observation(status = TxStatus.CONFIRMED, amount = "1.5"),
            false,
        )
        assertThat(blockchains.reads).isZero()
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
        network: String = NETWORK,
        vendorTransactionId: String = VENDOR_TX_ID,
    ) = TxObservation(
        vendorTransactionId = vendorTransactionId,
        externalTransactionId = "wd-1",
        accountId = "account-1",
        network = network,
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

/** 조회 횟수를 세는 카탈로그. */
private class CountingBlockchains(
    private val chainModel: ChainModel?,
) : VendorBlockchainCatalogRepository by mockk() {
    var reads = 0
        private set

    fun reset() {
        reads = 0
    }

    override fun findByNetwork(network: String): VendorBlockchainCatalog {
        reads++
        return catalogOf(network, chainModel)
    }
}

/** 네트워크 하나의 계정 모델만 답하는 카탈로그 — 주소 비교 규칙이 어디서 오는지만 시험한다. */
private class FixedBlockchains(
    private val chainModel: ChainModel?,
) : VendorBlockchainCatalogRepository by mockk() {
    override fun findByNetwork(network: String): VendorBlockchainCatalog = catalogOf(network, chainModel)
}

private fun catalogOf(
    network: String,
    chainModel: ChainModel?,
) = VendorBlockchainCatalog(
    candidateId = "blkc-1",
    network = network,
    chainId = 1,
    displayName = network,
    testnet = false,
    deprecated = false,
    syncedAt = "20260807110000",
    chainModel = chainModel,
)

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
