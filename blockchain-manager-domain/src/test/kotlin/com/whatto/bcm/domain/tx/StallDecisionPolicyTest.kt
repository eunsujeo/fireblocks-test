package com.whatto.bcm.domain.tx

import com.whatto.bcm.domain.submission.SubmissionTransactionType
import com.whatto.bcm.domain.vendor.VendorTransactionLifecycleStage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StallDecisionPolicyTest {
    @Test
    fun `CONFIRMING hash 0컨펌인 EVM 출금과 sweep만 boost 후보다`() {
        val observation = observation()

        assertThat(StallDecisionPolicy.decide(record(), SubmissionTransactionType.WITHDRAWAL, observation))
            .isEqualTo(StallDecision.BoostEligible("0xabc"))
        assertThat(StallDecisionPolicy.decide(record(), SubmissionTransactionType.SWEEP_BATCH, observation))
            .isEqualTo(StallDecision.BoostEligible("0xabc"))
        assertThat(StallDecisionPolicy.decide(record(), SubmissionTransactionType.INTERNAL, observation))
            .isEqualTo(StallDecision.Alert(StallAlertReason.TRANSACTION_TYPE_NOT_ELIGIBLE))
        assertThat(StallDecisionPolicy.decide(record(), null, observation))
            .isEqualTo(StallDecision.Alert(StallAlertReason.TRANSACTION_NOT_OWNED))
    }

    @Test
    fun `체인 전 상태와 컨펌 1 이상은 boost하지 않고 지연 경보다`() {
        assertThat(
            StallDecisionPolicy.decide(
                record(),
                SubmissionTransactionType.WITHDRAWAL,
                observation(stage = VendorTransactionLifecycleStage.PRE_CHAIN, transactionHash = null),
            ),
        ).isEqualTo(StallDecision.Alert(StallAlertReason.PRE_CHAIN_DELAY))
        assertThat(
            StallDecisionPolicy.decide(
                record(),
                SubmissionTransactionType.WITHDRAWAL,
                observation(confirmationCount = 1),
            ),
        ).isEqualTo(StallDecision.Alert(StallAlertReason.MINED_CONFIRMATION_DELAY))
    }

    @Test
    fun `hash가 없거나 DB와 다르면 boost하지 않는다`() {
        assertThat(
            StallDecisionPolicy.decide(
                record(),
                SubmissionTransactionType.WITHDRAWAL,
                observation(transactionHash = null),
            ),
        ).isEqualTo(StallDecision.Alert(StallAlertReason.TRANSACTION_HASH_MISSING))
        assertThat(
            StallDecisionPolicy.decide(
                record(transactionHash = "0xold"),
                SubmissionTransactionType.WITHDRAWAL,
                observation(transactionHash = "0xnew"),
            ),
        ).isEqualTo(StallDecision.Alert(StallAlertReason.TRANSACTION_HASH_MISMATCH))
    }

    @Test
    fun `active 거래가 바뀌었거나 EVM 네트워크가 아니면 boost하지 않는다`() {
        assertThat(
            StallDecisionPolicy.decide(
                record().copy(activeVendorTxId = "tx-new-active"),
                SubmissionTransactionType.WITHDRAWAL,
                observation(),
            ),
        ).isEqualTo(StallDecision.Alert(StallAlertReason.ACTIVE_TRANSACTION_MISMATCH))
        assertThat(
            StallDecisionPolicy.decide(
                record().copy(network = "SOLANA"),
                SubmissionTransactionType.WITHDRAWAL,
                observation(),
            ),
        ).isEqualTo(StallDecision.Alert(StallAlertReason.NETWORK_NOT_ELIGIBLE))
    }

    @Test
    fun `종결 관찰과 알 수 없는 단계는 별도 복구 대상으로 분류한다`() {
        assertThat(
            StallDecisionPolicy.decide(
                record(),
                SubmissionTransactionType.WITHDRAWAL,
                observation(stage = VendorTransactionLifecycleStage.TERMINAL),
            ),
        ).isEqualTo(StallDecision.Alert(StallAlertReason.TERMINAL_OBSERVED))
        assertThat(
            StallDecisionPolicy.decide(
                record(),
                SubmissionTransactionType.WITHDRAWAL,
                observation(stage = VendorTransactionLifecycleStage.UNKNOWN),
            ),
        ).isEqualTo(StallDecision.Alert(StallAlertReason.UNKNOWN_VENDOR_STAGE))
    }

    private fun record(transactionHash: String? = null) =
        TxRecord(
            vendorTxId = "tx-root",
            activeVendorTxId = "tx-active",
            externalTxId = "wd-1",
            accountId = "account-1",
            network = "ETHEREUM",
            symbol = "USDC",
            transactionHash = transactionHash,
            lastPublishedStatus = TxStatus.CONFIRMED,
            confirmationCount = 0,
            firstDetectedAt = "20260807110000",
            lastChangedAt = "20260807110000",
        )

    private fun observation(
        stage: VendorTransactionLifecycleStage = VendorTransactionLifecycleStage.CONFIRMING,
        transactionHash: String? = "0xabc",
        confirmationCount: Int = 0,
    ) = StallLatestObservation(
        vendorTransactionId = "tx-active",
        lifecycleStage = stage,
        transactionHash = transactionHash,
        confirmationCount = confirmationCount,
    )
}
