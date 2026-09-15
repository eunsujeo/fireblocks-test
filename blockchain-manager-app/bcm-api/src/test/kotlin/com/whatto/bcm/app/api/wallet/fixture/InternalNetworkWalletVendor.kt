package com.whatto.bcm.app.api.wallet.fixture

import com.whatto.bcm.domain.vendor.NetworkWalletCreationRequest
import com.whatto.bcm.domain.vendor.NetworkWalletObservation
import com.whatto.bcm.domain.vendor.NetworkWalletOwnership
import com.whatto.bcm.domain.vendor.NetworkWalletProvisioningPort
import com.whatto.bcm.domain.vendor.NetworkWalletResponse
import com.whatto.bcm.domain.vendor.NetworkWalletScope
import com.whatto.bcm.domain.vendor.VendorPage
import com.whatto.bcm.domain.wallet.NetworkWalletSubmissionSpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 생성 포트의 내부 대역 — 벤더가 지갑을 만든 뒤 응답이 유실되는 경우를 재현한다.
 * 응답 바이트는 BCM 내부 표기이며 Dfns wire payload가 아니다. 실제 어댑터·Baseline 수용은 별도다.
 */
class InternalNetworkWalletVendor : NetworkWalletProvisioningPort {
    private val wallets = ConcurrentHashMap<String, NetworkWalletObservation>()
    val createCalls = AtomicInteger()
    val readCalls = AtomicInteger()
    val candidateCalls = AtomicInteger()

    /** true면 다음 create는 지갑을 만든 뒤 응답을 돌려주지 못한다(timeout/연결 끊김 모사). */
    @Volatile var loseNextCreateResponse = false

    /** create가 지갑을 만든 직후 열린다 — 응답 대기 중인 다른 요청의 조회 복구를 결정적으로 재현하기 위한 신호다. */
    val createEntered = CountDownLatch(1)

    /** 설정하면 create는 지갑을 만든 뒤 이 게이트가 열릴 때까지 응답을 돌려주지 않는다(느린 응답 모사). */
    @Volatile var createResponseGate: CountDownLatch? = null

    override fun create(
        request: NetworkWalletCreationRequest,
        submission: NetworkWalletSubmissionSpec,
    ): NetworkWalletResponse<NetworkWalletObservation> {
        val sequence = createCalls.incrementAndGet()
        val wallet =
            NetworkWalletObservation(
                request.scope.origin,
                request.scope.network,
                "internal-wallet-$sequence",
                request.correlationId,
                NetworkWalletOwnership.ORGANIZATION,
                "internal-address-$sequence",
            )
        wallets[wallet.vendorWalletId] = wallet
        createEntered.countDown()
        createResponseGate?.let { gate -> check(gate.await(10, TimeUnit.SECONDS)) { "create response gate was not released" } }
        if (loseNextCreateResponse) {
            loseNextCreateResponse = false
            throw IllegalStateException("simulated response loss after vendor-side creation")
        }
        return NetworkWalletResponse(wallet, encode(listOf(wallet)))
    }

    override fun read(
        scope: NetworkWalletScope,
        vendorWalletId: String,
    ): NetworkWalletResponse<NetworkWalletObservation?> {
        readCalls.incrementAndGet()
        val wallet = wallets[vendorWalletId]?.takeIf { it.origin == scope.origin && it.network == scope.network }
        return NetworkWalletResponse(wallet, encode(listOfNotNull(wallet)))
    }

    override fun candidates(
        request: NetworkWalletCreationRequest,
        pageCursor: String?,
    ): NetworkWalletResponse<VendorPage<NetworkWalletObservation>> {
        candidateCalls.incrementAndGet()
        require(pageCursor == null) { "internal vendor returns a single page" }
        val matches =
            wallets.values
                .filter {
                    it.origin == request.scope.origin &&
                        it.network == request.scope.network &&
                        it.correlationId == request.correlationId
                }.sortedBy { it.vendorWalletId }
        return NetworkWalletResponse(VendorPage(matches, null), encode(matches))
    }

    fun walletCount(): Int = wallets.size

    private fun encode(observations: List<NetworkWalletObservation>): ByteArray =
        (listOf("bcm-internal-observation-fixture") + observations.map { "${it.vendorWalletId}|${it.correlationId}|${it.address}" })
            .joinToString("|")
            .toByteArray()
}
