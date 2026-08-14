package com.whatto.bcm.app.application.webhook

import com.whatto.bcm.domain.tx.FinalityPolicy
import com.whatto.bcm.domain.tx.TxStatus
import com.whatto.bcm.domain.vendor.VendorStatusObservation
import com.whatto.bcm.infra.client.fireblocks.FireblocksStatusTranslator
import com.whatto.bcm.infra.client.fireblocks.FireblocksTransactionParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.ObjectMapper
import tools.jackson.databind.node.ObjectNode

class FireblocksTransactionParserTest {
    private val objectMapper = ObjectMapper()
    private val parser = FireblocksTransactionParser(objectMapper)
    private val statusTranslator = FireblocksStatusTranslator(FinalityPolicy { 1 })

    @Test
    fun `96 실물 CONFIRMING payload를 금액 문자열과 입금 판정 필드까지 읽는다`() {
        val transaction = parser.parse(realPayload())

        assertThat(transaction.vendorTransactionId).isEqualTo("f3339e5d-428e-4add-8018-631b972f3195")
        assertThat(transaction.vendorAssetId).isEqualTo("KBKRW_ETH_TEST5_6KCC")
        assertThat(transaction.managedVaultSource).isFalse()
        assertThat(transaction.sourceAddress).isEqualTo("0xC05A705eFE3f89b3a7a6Ceb6D79107529Ce20f7C")
        assertThat(transaction.destinationAddress).isEqualTo("0x628501678d302023ca4555B678581917dF8D7636")
        assertThat(transaction.amount).isEqualTo("100")
        assertThat(transaction.confirmationCount).isEqualTo(1)
        assertThat(transaction.createdAtEpochMillis).isEqualTo(1_785_738_506_296)
        assertThat(statusTranslator.translate(transaction, "ETHEREUM")).isEqualTo(TxStatus.CONFIRMED)
    }

    @Test
    fun `COMPLETED도 DCCP 확인 수에 못 미치면 CONFIRMED이고 임계에 닿아야 FINALIZED다`() {
        val belowThreshold = parser.parse(mutatedPayload(status = "COMPLETED", confirmations = 1))
        val atThreshold = parser.parse(mutatedPayload(status = "COMPLETED", confirmations = 2))
        val translator = FireblocksStatusTranslator(FinalityPolicy { 2 })

        assertThat(translator.translate(belowThreshold, "ETHEREUM")).isEqualTo(TxStatus.CONFIRMED)
        assertThat(translator.translate(atThreshold, "ETHEREUM")).isEqualTo(TxStatus.FINALIZED)
    }

    @Test
    fun `동결 subStatus는 REJECTED이고 영구 실패 status는 FAILED다`() {
        val frozen = parser.parse(mutatedPayload(status = "COMPLETED", subStatus = "AUTO_FREEZE"))
        val failed = parser.parse(mutatedPayload(status = "FAILED", subStatus = "DROPPED_BY_BLOCKCHAIN"))

        assertThat(statusTranslator.translate(frozen, "ETHEREUM")).isEqualTo(TxStatus.REJECTED)
        assertThat(statusTranslator.translate(failed, "ETHEREUM")).isEqualTo(TxStatus.FAILED)
    }

    @Test
    fun `대사 종결 상태는 벤더 번역기 한 곳에서 발신 방향까지 판정한다`() {
        assertThat(
            statusTranslator.terminalStatusForReconciliation(
                VendorStatusObservation("COMPLETED", null, 0),
                "VAULT_ACCOUNT",
            ),
        ).isEqualTo(TxStatus.FINALIZED)
        assertThat(
            statusTranslator.terminalStatusForReconciliation(
                VendorStatusObservation("FAILED", null, 0),
                "UNKNOWN",
            ),
        ).isEqualTo(TxStatus.FAILED)
        assertThat(
            statusTranslator.terminalStatusForReconciliation(
                VendorStatusObservation("REJECTED", null, 0),
                "VAULT_ACCOUNT",
            ),
        ).isEqualTo(TxStatus.REJECTED)
        assertThat(
            statusTranslator.terminalStatusForReconciliation(
                VendorStatusObservation("BLOCKED", null, 0),
                "UNKNOWN",
            ),
        ).isNull()
        assertThat(
            statusTranslator.terminalStatusForReconciliation(
                VendorStatusObservation("CONFIRMING", null, 0),
                "VAULT_ACCOUNT",
            ),
        ).isNull()
        assertThat(
            statusTranslator.terminalStatusForReconciliation(
                VendorStatusObservation("COMPLETED", "AUTO_FREEZE", 0),
                "UNKNOWN",
            ),
        ).isNull()
        assertThat(
            statusTranslator.terminalStatusForReconciliation(
                VendorStatusObservation("COMPLETED", "AUTO_FREEZE", 0),
                "VAULT_ACCOUNT",
            ),
        ).isEqualTo(TxStatus.REJECTED)
    }

    @Test
    fun `체인 등장 전 알림의 빈 destinationAddress는 null로 보존한다`() {
        val payload =
            objectMapper
                .readTree(realPayload())
                .also { (it.path("data") as ObjectNode).remove("destinationAddress") }
                .toString()

        assertThat(parser.parse(payload).destinationAddress).isNull()
    }

    private fun realPayload(): String =
        checkNotNull(javaClass.getResourceAsStream("/payload/transaction.created.json"))
            .bufferedReader()
            .use { it.readText() }

    private fun mutatedPayload(
        status: String,
        confirmations: Int = 1,
        subStatus: String = "CONFIRMED",
    ): String =
        objectMapper
            .readTree(realPayload())
            .also { root ->
                (root.path("data") as ObjectNode).apply {
                    put("status", status)
                    put("subStatus", subStatus)
                    put("numOfConfirmations", confirmations)
                }
            }.toString()
}
