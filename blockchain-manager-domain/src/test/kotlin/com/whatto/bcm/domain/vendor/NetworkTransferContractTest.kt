package com.whatto.bcm.domain.vendor

import com.whatto.bcm.domain.provider.ProviderOrigin
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** 전송 요청·상태의 순수 계약(계약13 "전송 제출·조회 계약") — 벤더 상태 원어 대응과 제출 키·금액 형식이다. */
class NetworkTransferContractTest {
    private val scope = NetworkWalletScope(ORIGIN, "acct_dfns_1", "ETHEREUM_SEPOLIA")

    @Test
    fun `명세 상태 원어만 도메인 상태로 옮기고 종결·체인 제출 여부를 구분한다`() {
        assertThat(NetworkTransferStatus.ofVendorStatus("Pending")).isEqualTo(NetworkTransferStatus.PENDING)
        assertThat(NetworkTransferStatus.ofVendorStatus("Executing")).isEqualTo(NetworkTransferStatus.EXECUTING)
        assertThat(NetworkTransferStatus.ofVendorStatus("Broadcasted")).isEqualTo(NetworkTransferStatus.BROADCASTED)
        assertThat(NetworkTransferStatus.ofVendorStatus("Confirmed")).isEqualTo(NetworkTransferStatus.CONFIRMED)
        assertThat(NetworkTransferStatus.ofVendorStatus("Failed")).isEqualTo(NetworkTransferStatus.FAILED)
        assertThat(NetworkTransferStatus.ofVendorStatus("Rejected")).isEqualTo(NetworkTransferStatus.REJECTED)
        listOf("", "confirmed", "COMPLETED", "Unknown", " Pending").forEach {
            assertThat(NetworkTransferStatus.ofVendorStatus(it)).describedAs(it).isNull()
        }

        // 공식 Idempotency 문서가 externalId 영구 결속으로 규정한 종결 셋.
        assertThat(NetworkTransferStatus.entries.filter { it.terminal })
            .containsExactly(NetworkTransferStatus.CONFIRMED, NetworkTransferStatus.FAILED, NetworkTransferStatus.REJECTED)
        assertThat(NetworkTransferStatus.entries.filter { it.onChainSubmitted == true })
            .containsExactly(NetworkTransferStatus.BROADCASTED, NetworkTransferStatus.CONFIRMED)
        assertThat(NetworkTransferStatus.entries.filter { it.onChainSubmitted == false })
            .containsExactly(NetworkTransferStatus.PENDING, NetworkTransferStatus.EXECUTING, NetworkTransferStatus.REJECTED)
        // Failed는 시스템 실패와 온체인 실행 실패를 함께 뜻해 상태만으로 제출 여부를 확정하지 않는다(공식 문서).
        assertThat(NetworkTransferStatus.FAILED.onChainSubmitted).isNull()
    }

    @Test
    fun `제출 키는 50자 이하이고 금액은 최소 단위 정수 문자열이어야 한다`() {
        val request = request(externalId = "a".repeat(50))
        assertThat(request.externalId).hasSize(50)
        assertThat(request(amount = "0").amountBaseUnits).isEqualTo("0")

        assertThatThrownBy { request(externalId = "a".repeat(51)) }.isInstanceOf(IllegalArgumentException::class.java)
        listOf("", " ", " key", "key ").forEach { key ->
            assertThatThrownBy { request(externalId = key) }.describedAs(key).isInstanceOf(IllegalArgumentException::class.java)
        }
        listOf("1.5", "-1", "01", "", "1e6", "0x10", " 1").forEach { amount ->
            assertThatThrownBy { request(amount = amount) }.describedAs(amount).isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    @Test
    fun `지갑 ID·자산 키·목적지 주소의 공백은 요청으로 만들 수 없다`() {
        assertThatThrownBy { request(walletId = " ") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { request(assetId = "") }.isInstanceOf(IllegalArgumentException::class.java)
        assertThatThrownBy { request(destination = " 0xabc") }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `충돌 결과는 수신 바이트 사본과 기존 전송 ID를 보존한다`() {
        val body = """{"error":{"details":{"duplicate":{"id":"xfr-1"}}}}""".toByteArray()
        val conflict = NetworkTransferSubmission.Conflict("xfr-1", body)
        body[0] = 'X'.code.toByte()

        assertThat(conflict.duplicateTransferId).isEqualTo("xfr-1")
        assertThat(conflict.responseBody()).isNotEqualTo(body)
        assertThat(conflict.responseBody().toString(Charsets.UTF_8)).startsWith("""{"error"""")
    }

    private fun request(
        walletId: String = "wa-1f04s-lqc9q-000000000000000",
        assetId: String = "EthereumSepolia:Native",
        destination: String = "0x00e3495cf6af59008f22ffaf32d4c92ac33dac47",
        amount: String = "1500000",
        externalId: String = "wd-1",
    ) = NetworkTransferRequest(scope, walletId, assetId, destination, amount, externalId)

    private companion object {
        val ORIGIN = ProviderOrigin("test-dfns-origin", "dfns", "dfns", "test-dfns-platform", "test-dfns-organization", "TESTNET")
    }
}
