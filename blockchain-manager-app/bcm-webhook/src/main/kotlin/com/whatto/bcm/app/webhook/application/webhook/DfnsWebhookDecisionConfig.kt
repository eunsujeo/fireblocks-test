package com.whatto.bcm.app.webhook.application.webhook

import com.whatto.bcm.app.application.account.DepositAddressQueryService
import com.whatto.bcm.app.application.asset.VendorAssetMappingQueryService
import com.whatto.bcm.app.application.event.OutboxEventService
import com.whatto.bcm.app.application.submission.SubmissionObservationService
import com.whatto.bcm.app.application.tx.TxStateService
import com.whatto.bcm.app.application.wallet.NetworkWalletQueryService
import com.whatto.bcm.domain.event.ChainEventSerializer
import com.whatto.bcm.domain.event.EventIdGenerator
import com.whatto.bcm.domain.provider.ProviderOrigin
import com.whatto.bcm.domain.tx.ChainHeadPort
import com.whatto.bcm.domain.tx.FinalityPolicy
import com.whatto.bcm.domain.vendor.LedgerAsset
import com.whatto.bcm.domain.vendor.NetworkChainEventParser
import com.whatto.bcm.domain.vendor.NetworkChainLedgerLookup
import com.whatto.bcm.domain.vendor.NetworkTransferEventParser
import com.whatto.bcm.domain.vendor.VendorStatusTranslator
import com.whatto.bcm.infra.client.config.ConditionalOnDfnsProtocol
import com.whatto.bcm.infra.client.dfns.DfnsStatusTranslator
import com.whatto.bcm.infra.client.evm.EvmChainHeadClient
import com.whatto.bcm.infra.client.evm.EvmRpcProperties
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestClient
import java.time.Clock

/** Dfns 판단 경로 조립 — `BCM_PROVIDER=dfns`에서만 만든다. Fireblocks 판단 경로와 함께 조립되지 않는다. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnDfnsProtocol
@EnableConfigurationProperties(EvmRpcProperties::class)
class DfnsWebhookDecisionConfig {
    /** Dfns 상태 원어 → 공통 TxStatus. 확정 임계 비교는 제공자와 무관한 `bcm.finality-confirmations.*`를 쓴다. */
    @Bean
    fun dfnsStatusTranslator(finalityPolicy: FinalityPolicy): VendorStatusTranslator = DfnsStatusTranslator(finalityPolicy)

    /**
     * 확정 판정의 체인 head 조회 — 위탁 RPC(`bcm.evm-rpc.*`)다. 벤더가 컨펌 수를 주지 않으므로 BCM이 직접 읽는다(CLAUDE.md 3절).
     * Solana 확정 모델은 범위 밖이라 EVM RPC만 조립한다. 판단 경로에서만 필요하므로 Webhook 앱에서만 만든다.
     */
    @Bean
    fun dfnsChainHeadPort(
        restClientBuilder: RestClient.Builder,
        evmRpcProperties: EvmRpcProperties,
    ): ChainHeadPort = EvmChainHeadClient(restClientBuilder, evmRpcProperties)

    /**
     * 귀속에 필요한 원장 조회를 기존 조회 서비스로 잇는다. 정밀도는 등록 매핑에서 읽어 이벤트 금액 환산에 쓴다(03 V27).
     * 활성 매핑만 대상이다 — 해제된 자산의 입금을 현재 자산처럼 처리하지 않는다.
     */
    @Bean
    fun dfnsNetworkChainLedgerLookup(
        assetMappings: VendorAssetMappingQueryService,
        depositAddresses: DepositAddressQueryService,
        networkWallets: NetworkWalletQueryService,
        origin: ProviderOrigin,
    ): NetworkChainLedgerLookup =
        object : NetworkChainLedgerLookup {
            override fun assetOf(vendorAssetId: String): LedgerAsset? =
                assetMappings.findByVendorAssetId(vendorAssetId)?.let { LedgerAsset(it.network, it.symbol, it.decimals) }

            override fun accountOfDepositAddress(
                address: String,
                network: String,
                symbol: String,
            ): String? = depositAddresses.findByAddress(address, network, symbol)?.accountId

            // 자산 발급 기록이 아니라 **소유권**으로 묻는다 — 제출은 그 자산의 주소 발급을 요구하지 않는다(계약13).
            override fun ownsWalletAddress(
                network: String,
                address: String,
            ): Boolean = networkWallets.ownsAddress(origin, network, address)
        }

    @Bean
    fun dfnsTransferEventDecision(
        parser: NetworkTransferEventParser,
        submissions: SubmissionObservationService,
        statusTranslator: VendorStatusTranslator,
        txStates: TxStateService,
        outboxEvents: OutboxEventService,
        eventIdGenerator: EventIdGenerator,
        eventSerializer: ChainEventSerializer,
        clock: Clock,
        @Value("\${bcm.webhook-worker.outbox-max-attempts:5}") outboxMaxAttempts: Int,
    ): DfnsTransferEventDecision =
        DfnsTransferEventDecision(
            parser,
            submissions,
            statusTranslator,
            txStates,
            outboxEvents,
            eventIdGenerator,
            eventSerializer,
            clock,
            outboxMaxAttempts,
        )

    @Bean
    fun dfnsChainEventDecision(
        parser: NetworkChainEventParser,
        ledger: NetworkChainLedgerLookup,
        submissions: SubmissionObservationService,
        chainHeads: ChainHeadPort,
        statusTranslator: VendorStatusTranslator,
        txStates: TxStateService,
        outboxEvents: OutboxEventService,
        eventIdGenerator: EventIdGenerator,
        eventSerializer: ChainEventSerializer,
        clock: Clock,
        @Value("\${bcm.webhook-worker.outbox-max-attempts:5}") outboxMaxAttempts: Int,
    ) = DfnsChainEventDecision(
        parser = parser,
        ledger = ledger,
        submissions = submissions,
        chainHeads = chainHeads,
        statusTranslator = statusTranslator,
        txStates = txStates,
        outboxEvents = outboxEvents,
        eventIdGenerator = eventIdGenerator,
        eventSerializer = eventSerializer,
        clock = clock,
        outboxMaxAttempts = outboxMaxAttempts,
    )
}
