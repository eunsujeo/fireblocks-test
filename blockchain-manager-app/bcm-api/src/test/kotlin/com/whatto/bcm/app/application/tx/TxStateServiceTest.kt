package com.whatto.bcm.app.application.tx

import com.whatto.bcm.domain.tx.TxObservation
import com.whatto.bcm.domain.tx.TxRecord
import com.whatto.bcm.domain.tx.TxRecordRepository
import com.whatto.bcm.domain.tx.TxStatus
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class TxStateServiceTest {
    @Test
    fun `상태가 진행되면 이전 막힘 경보 표식을 지운다`() {
        val repository = RecordingTxRecords(record())

        val change = TxStateService(repository).observe(observation(status = TxStatus.CONFIRMED))

        assertThat(change.record.stallAlertedAt).isNull()
    }

    @Test
    fun `진행 없는 중복 관찰은 막힘 경보 표식을 유지한다`() {
        val repository = RecordingTxRecords(record())

        val change = TxStateService(repository).observe(observation(status = TxStatus.SUBMITTED))

        assertThat(change.record.stallAlertedAt).isEqualTo("20260807113000")
    }

    private fun record() =
        TxRecord(
            vendorTxId = "tx-1",
            externalTxId = "wd-1",
            accountId = "account-1",
            network = "ETHEREUM",
            symbol = "USDC",
            lastPublishedStatus = TxStatus.SUBMITTED,
            confirmationCount = 0,
            stallAlertedAt = "20260807113000",
            firstDetectedAt = "20260807110000",
            lastChangedAt = "20260807110000",
        )

    private fun observation(status: TxStatus) =
        TxObservation(
            vendorTransactionId = "tx-1",
            externalTransactionId = "wd-1",
            accountId = "account-1",
            network = "ETHEREUM",
            symbol = "USDC",
            transactionHash = null,
            status = status,
            confirmationCount = 0,
            vendorSubStatus = null,
            vendorNetworkStatus = null,
            observedAt = "20260807120000",
        )
}

private class RecordingTxRecords(
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

    override fun lockNetworkTransactionHash(
        network: String,
        transactionHash: String,
    ) = Unit

    override fun findByNetworkAndTransactionHash(
        network: String,
        transactionHash: String,
    ): List<TxRecord> = listOfNotNull(record).filter { it.network == network && it.transactionHash == transactionHash }
}
