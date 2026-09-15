package com.whatto.bcm.domain.wallet

import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.ProvisioningPendingException
import com.whatto.bcm.domain.vendor.fixture.NetworkWalletFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** 계약13 — Pending/Conflict의 공개 계약 번역. 보류는 재생성 허가가 아니고 충돌은 재시도해도 같은 답이다. */
class NetworkWalletCreationIntentTest {
    @Test
    fun `완료된 의도만 연결된 wallet ID를 돌려준다`() {
        assertThat(
            intent(NetworkWalletCreationStatus.COMPLETED, knownWalletId = "wallet-test").requireCompleted(5),
        ).isEqualTo("wallet-test")
    }

    @Test
    fun `진행 중 상태는 저장된 사유와 재시도 시간을 담은 보류로 번역한다`() {
        listOf(NetworkWalletCreationStatus.PREPARED, NetworkWalletCreationStatus.SUBMITTING, NetworkWalletCreationStatus.RECOVERING)
            .forEach { status ->
                assertThatThrownBy { intent(status, lastReason = "INCOMPLETE_SCAN").requireCompleted(7) }
                    .isInstanceOfSatisfying(ProvisioningPendingException::class.java) {
                        assertThat(it.resourceKey).isEqualTo("intent-test")
                        assertThat(it.reason).isEqualTo("INCOMPLETE_SCAN")
                        assertThat(it.retryAfterSeconds).isEqualTo(7)
                    }
            }
        assertThatThrownBy { intent(NetworkWalletCreationStatus.PREPARED).requireCompleted(7) }
            .isInstanceOfSatisfying(ProvisioningPendingException::class.java) { assertThat(it.reason).isEqualTo("PREPARED") }
    }

    @Test
    fun `충돌 상태는 확정 오류이며 보류로 숨기지 않는다`() {
        assertThatThrownBy { intent(NetworkWalletCreationStatus.CONFLICT, lastReason = "MULTIPLE_WALLETS").requireCompleted(7) }
            .isInstanceOfSatisfying(ConflictException::class.java) {
                assertThat(it.resource).isEqualTo("networkWallet")
                assertThat(it.key).isEqualTo("intent-test")
            }
    }

    @Test
    fun `완료인데 wallet ID가 없으면 원장 손상으로 실패한다`() {
        assertThatThrownBy { intent(NetworkWalletCreationStatus.COMPLETED).requireCompleted(7) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `보류 재시도 시간은 1초 이상이어야 한다`() {
        assertThatThrownBy { intent(NetworkWalletCreationStatus.RECOVERING).requireCompleted(0) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun intent(
        status: NetworkWalletCreationStatus,
        knownWalletId: String? = null,
        lastReason: String? = null,
    ) = NetworkWalletCreationIntent(
        "intent-test",
        NetworkWalletFixture.request(),
        NetworkWalletSubmissionSpec("a".repeat(64), "test-v1", "TestNetwork"),
        status,
        1,
        "20260915000000",
        knownWalletId,
        null,
        null,
        false,
        lastReason,
        "20260915000000",
        "20260915000000",
    )
}
