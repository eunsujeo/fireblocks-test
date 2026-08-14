package com.whatto.bcm.domain.tx

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TxStateMachineTest {
    @Test
    fun `active 아닌 물리 거래의 실패는 root를 움직이지 않는다`() {
        val repository = MemoryTxRecords(record(activeVendorTxId = "tx-new"))

        val result = TxStateMachine(repository).observeRoot("tx-root", observation(TxStatus.FAILED), false)

        assertThat(result.statusesToPublish).isEmpty()
        assertThat(result.record.activeVendorTxId).isEqualTo("tx-new")
        assertThat(result.record.lastPublishedStatus).isEqualTo(TxStatus.CONFIRMED)
    }

    @Test
    fun `active 아닌 물리 거래의 성공 증거는 미채굴 root의 승자로 채택한다`() {
        val repository = MemoryTxRecords(record(activeVendorTxId = "tx-new", transactionHash = "0xnew"))

        val result =
            TxStateMachine(repository).observeRoot(
                "tx-root",
                observation(TxStatus.FINALIZED, confirmationCount = 1, transactionHash = "0xroot"),
                true,
            )

        assertThat(result.statusesToPublish).containsExactly(TxStatus.FINALIZED)
        assertThat(result.record.activeVendorTxId).isEqualTo("tx-root")
        assertThat(result.record.transactionHash).isEqualTo("0xroot")
    }

    @Test
    fun `이미 FAILED를 발행한 root는 뒤늦은 성공 증거로 되살리지 않는다`() {
        val repository = MemoryTxRecords(record(lastPublishedStatus = TxStatus.FAILED))

        val result =
            TxStateMachine(repository).observeRoot(
                "tx-root",
                observation(TxStatus.FINALIZED, confirmationCount = 1),
                true,
            )

        assertThat(result.statusesToPublish).isEmpty()
        assertThat(result.record.lastPublishedStatus).isEqualTo(TxStatus.FAILED)
    }

    @Test
    fun `미결 boost가 있으면 미확정 active의 FAILED만 보류한다`() {
        val pending = MemoryTxRecords(record(stallAlertedAt = "20260807113000"))
        val finalized = MemoryTxRecords(record(lastPublishedStatus = TxStatus.FINALIZED))

        val deferred =
            TxStateMachine(pending).observeRoot(
                "tx-root",
                observation(TxStatus.FAILED),
                successEvidence = false,
                deferFailure = true,
            )
        val reorg =
            TxStateMachine(finalized).observeRoot(
                "tx-root",
                observation(TxStatus.FAILED),
                successEvidence = false,
                deferFailure = true,
            )

        assertThat(deferred.statusesToPublish).isEmpty()
        assertThat(deferred.record.lastPublishedStatus).isEqualTo(TxStatus.CONFIRMED)
        assertThat(deferred.record.stallAlertedAt).isNull()
        assertThat(deferred.record.lastChangedAt).isEqualTo("20260807120000")
        assertThat(reorg.statusesToPublish).containsExactly(TxStatus.FAILED)
    }

    @Test
    fun `벤더 생성 시각은 최초값을 보존하고 실제 진행 관찰은 대사 확인 상태를 초기화한다`() {
        val repository =
            MemoryTxRecords(
                record().copy(
                    vendorCreatedAt = "20260807100000",
                    reconciliationCheckedAt = "20260807113000",
                    reconciliationCheckCount = 3,
                    reconciliationStoppedAt = "20260807114000",
                ),
            )

        val result =
            TxStateMachine(repository).observeRoot(
                "tx-root",
                observation(TxStatus.FINALIZED, confirmationCount = 1).copy(
                    vendorCreatedAt = "20260807115900",
                ),
                successEvidence = true,
            )

        assertThat(result.record.vendorCreatedAt).isEqualTo("20260807100000")
        assertThat(result.record.reconciliationCheckedAt).isNull()
        assertThat(result.record.reconciliationCheckCount).isZero()
        assertThat(result.record.reconciliationStoppedAt).isNull()
    }

    private fun record(
        activeVendorTxId: String = "tx-root",
        transactionHash: String? = "0xroot",
        lastPublishedStatus: TxStatus = TxStatus.CONFIRMED,
        stallAlertedAt: String? = null,
    ) = TxRecord(
        vendorTxId = "tx-root",
        activeVendorTxId = activeVendorTxId,
        externalTxId = "wd-root",
        accountId = "account-1",
        network = "ETHEREUM",
        symbol = "USDC",
        transactionHash = transactionHash,
        lastPublishedStatus = lastPublishedStatus,
        confirmationCount = 0,
        stallAlertedAt = stallAlertedAt,
        firstDetectedAt = "20260807110000",
        lastChangedAt = "20260807110000",
    )

    private fun observation(
        status: TxStatus,
        confirmationCount: Int = 0,
        transactionHash: String? = "0xroot",
    ) = TxObservation(
        vendorTransactionId = "tx-root",
        externalTransactionId = "wd-root",
        accountId = "account-1",
        network = "ETHEREUM",
        symbol = "USDC",
        transactionHash = transactionHash,
        status = status,
        confirmationCount = confirmationCount,
        vendorSubStatus = null,
        vendorNetworkStatus = null,
        observedAt = "20260807120000",
    )
}

private class MemoryTxRecords(
    private var record: TxRecord,
) : TxRecordRepository {
    override fun insert(txRecord: TxRecord): TxRecord = txRecord.also { record = it }

    override fun update(txRecord: TxRecord): TxRecord = txRecord.also { record = it }

    override fun updatePhysicalWinner(
        txRecord: TxRecord,
        previousActiveVendorTxId: String,
    ): TxRecord = txRecord.also { record = it }

    override fun findByVendorTxId(vendorTxId: String): TxRecord? = record

    override fun findByActiveVendorTxId(activeVendorTxId: String): TxRecord? = record

    override fun findByVendorTxIdForUpdate(vendorTxId: String): TxRecord? = record

    override fun findByActiveVendorTxIdForUpdate(activeVendorTxId: String): TxRecord? = record

    override fun findByExternalTxId(externalTxId: String): TxRecord? = record
}
