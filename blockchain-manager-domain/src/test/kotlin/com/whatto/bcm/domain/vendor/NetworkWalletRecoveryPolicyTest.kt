package com.whatto.bcm.domain.vendor

import com.whatto.bcm.domain.vendor.fixture.NetworkWalletFixture
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class NetworkWalletRecoveryPolicyTest {
    private val request = NetworkWalletFixture.request()
    private val wallet = NetworkWalletFixture.observation(request)
    private val policy = NetworkWalletRecoveryPolicy()

    @Test
    fun `완료된 조회에서 모든 증거가 일치하는 지갑 하나만 연결한다`() {
        assertThat(policy.evaluate(request, listOf(wallet), scanComplete = true))
            .isEqualTo(NetworkWalletRecoveryDecision.Ready(wallet))
        assertThat(policy.evaluate(request, listOf(wallet), scanComplete = true, knownWalletId = wallet.vendorWalletId))
            .isEqualTo(NetworkWalletRecoveryDecision.Ready(wallet))
    }

    @Test
    fun `조회가 끝나도 후보가 없으면 재생성 없이 대기한다`() {
        assertThat(policy.evaluate(request, emptyList(), scanComplete = true))
            .isEqualTo(NetworkWalletRecoveryDecision.Pending(NetworkWalletPendingReason.NOT_OBSERVED))
        assertThat(policy.evaluate(request, emptyList(), scanComplete = true, knownWalletId = wallet.vendorWalletId))
            .isEqualTo(NetworkWalletRecoveryDecision.Pending(NetworkWalletPendingReason.NOT_OBSERVED))
    }

    @Test
    fun `페이지 조회가 끝나지 않으면 일치하는 후보도 확정하지 않는다`() {
        listOf(emptyList(), listOf(wallet)).forEach { candidates ->
            assertThat(policy.evaluate(request, candidates, scanComplete = false))
                .isEqualTo(NetworkWalletRecoveryDecision.Pending(NetworkWalletPendingReason.INCOMPLETE_SCAN))
        }
    }

    @Test
    fun `주소가 아직 없으면 같은 지갑을 기다린다`() {
        assertThat(policy.evaluate(request, listOf(wallet.copy(address = null)), scanComplete = true))
            .isEqualTo(NetworkWalletRecoveryDecision.Pending(NetworkWalletPendingReason.ADDRESS_NOT_READY))
    }

    @Test
    fun `겹친 페이지의 완전히 동일한 관찰만 중복 제거한다`() {
        assertThat(policy.evaluate(request, listOf(wallet, wallet.copy()), scanComplete = true))
            .isEqualTo(NetworkWalletRecoveryDecision.Ready(wallet))
    }

    @Test
    fun `서로 다른 지갑이 같은 의도를 가리키면 연결하지 않는다`() {
        assertConflict(listOf(wallet, wallet.copy(vendorWalletId = "another-wallet")), NetworkWalletConflictReason.MULTIPLE_WALLETS)
    }

    @Test
    fun `같은 지갑의 서로 다른 주소 관찰은 임의로 고르지 않는다`() {
        listOf("another-address", null).forEach { address ->
            assertConflict(listOf(wallet, wallet.copy(address = address)), NetworkWalletConflictReason.INCONSISTENT_OBSERVATIONS)
        }
    }

    @Test
    fun `원천 식별자와 실행 환경 중 하나라도 다르면 연결하지 않는다`() {
        val origin = request.scope.origin
        listOf(
            origin.copy(originId = "another-origin"),
            origin.copy(platformInstanceId = "another-instance"),
            origin.copy(vendorOrganizationId = "another-organization"),
            origin.copy(chainMode = "MAINNET"),
            origin.copy(executionMode = "fireblocks", protocolProvider = "fireblocks"),
            origin.copy(executionMode = "local", protocolProvider = "fireblocks", chainMode = "LOCAL"),
        ).forEach { other ->
            assertConflict(listOf(wallet.copy(origin = other)), NetworkWalletConflictReason.ORIGIN_MISMATCH)
        }
    }

    @Test
    fun `주소가 같아도 네트워크가 다르면 연결하지 않는다`() {
        assertConflict(listOf(wallet.copy(network = "BASE_SEPOLIA")), NetworkWalletConflictReason.NETWORK_MISMATCH)
    }

    @Test
    fun `상관관계가 없거나 다른 후보는 이름으로 추정하지 않는다`() {
        listOf(null, "another-intent").forEach { correlation ->
            assertConflict(listOf(wallet.copy(correlationId = correlation)), NetworkWalletConflictReason.CORRELATION_MISMATCH)
        }
    }

    @Test
    fun `조직 소유임을 확인하지 못한 지갑은 연결하지 않는다`() {
        listOf(NetworkWalletOwnership.OTHER, NetworkWalletOwnership.UNVERIFIED).forEach { ownership ->
            assertConflict(listOf(wallet.copy(ownership = ownership)), NetworkWalletConflictReason.OWNERSHIP_NOT_VERIFIED)
        }
    }

    @Test
    fun `이미 기록된 지갑 ID를 다른 후보로 교체하지 않는다`() {
        assertThat(policy.evaluate(request, listOf(wallet), scanComplete = true, knownWalletId = "previous-wallet"))
            .isEqualTo(NetworkWalletRecoveryDecision.Conflict(NetworkWalletConflictReason.KNOWN_WALLET_MISMATCH))
    }

    @Test
    fun `불완전한 조회에서 발견한 불일치도 대기로 숨기지 않는다`() {
        assertThat(policy.evaluate(request, listOf(wallet.copy(network = "BASE_SEPOLIA")), scanComplete = false))
            .isEqualTo(NetworkWalletRecoveryDecision.Conflict(NetworkWalletConflictReason.NETWORK_MISMATCH))
    }

    @Test
    fun `빈 식별자와 앞뒤 공백을 정규화해 다른 자원과 합치지 않는다`() {
        listOf("", " ", " value", "value ").forEach { invalid ->
            assertThatThrownBy { request.copy(correlationId = invalid) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { request.scope.copy(accountId = invalid) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { request.scope.copy(network = invalid) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { wallet.copy(vendorWalletId = invalid) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { wallet.copy(network = invalid) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { wallet.copy(correlationId = invalid) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { wallet.copy(address = invalid) }.isInstanceOf(IllegalArgumentException::class.java)
            assertThatThrownBy { policy.evaluate(request, listOf(wallet), scanComplete = true, knownWalletId = invalid) }
                .isInstanceOf(IllegalArgumentException::class.java)
        }
    }

    private fun assertConflict(
        candidates: List<NetworkWalletObservation>,
        reason: NetworkWalletConflictReason,
    ) {
        assertThat(policy.evaluate(request, candidates, scanComplete = true))
            .isEqualTo(NetworkWalletRecoveryDecision.Conflict(reason))
    }
}
