package com.whatto.bcm.app.webhook.application.webhook

import com.whatto.bcm.app.application.account.DepositAddressQueryService
import com.whatto.bcm.app.application.asset.VendorAssetMappingQueryService
import com.whatto.bcm.app.application.event.OutboxEventService
import com.whatto.bcm.app.application.submission.SubmissionObservationService
import com.whatto.bcm.app.application.tx.TxStateService
import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.event.ChainEventSerializer
import com.whatto.bcm.domain.event.EventIdGenerator
import com.whatto.bcm.domain.tx.FinalityPolicy
import com.whatto.bcm.domain.vendor.NetworkChainEventParser
import com.whatto.bcm.domain.vendor.NetworkTransferEventParser
import com.whatto.bcm.domain.webhook.WebhookInboxRepository
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.web.client.RestClient
import java.time.Clock

/**
 * 판단 경로는 제공자마다 **하나만** 조립된다 — 워커는 `WebhookDecisionWork` 경계만 보고 벤더 어휘를 모른다.
 * `BCM_PROVIDER=dfns` 전체 기동 차단(ProviderConfiguration)은 여기서 검사하지 않는다.
 */
class WebhookDecisionAssemblyTest {
    private val runner =
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(DfnsWebhookDecisionConfig::class.java, DfnsWebhookDecisionTransaction::class.java)
            .withBean(Clock::class.java, { Clock.systemUTC() })
            .withBean(RestClient.Builder::class.java, { RestClient.builder() })
            .withBean(FinalityPolicy::class.java, { FinalityPolicy { 12 } })
            .withBean(NetworkChainEventParser::class.java, { NetworkChainEventParser { null } })
            .withBean(NetworkTransferEventParser::class.java, { NetworkTransferEventParser { null } })
            .withBean(SubmissionObservationService::class.java, { mockk<SubmissionObservationService>() })
            .withBean(WebhookInboxRepository::class.java, { mockk<WebhookInboxRepository>() })
            .withBean(TransactionRunner::class.java, {
                object : TransactionRunner {
                    override fun <T> run(block: () -> T): T = block()
                }
            })
            .withBean(VendorAssetMappingQueryService::class.java, { mockk<VendorAssetMappingQueryService>() })
            .withBean(DepositAddressQueryService::class.java, { mockk<DepositAddressQueryService>() })
            .withBean(TxStateService::class.java, { mockk<TxStateService>() })
            .withBean(OutboxEventService::class.java, { mockk<OutboxEventService>() })
            .withBean(EventIdGenerator::class.java, { EventIdGenerator { "evt" } })
            .withBean(ChainEventSerializer::class.java, { ChainEventSerializer { "{}" } })

    @Test
    fun `dfns 선택은 Dfns 판단 경계만 만들고 상태 번역·체인 head도 함께 조립한다`() {
        runner
            .withPropertyValues("bcm.provider=dfns", "bcm.evm-rpc.networks.ETHEREUM_SEPOLIA.url=https://rpc.internal.test")
            .run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context.getBean(WebhookDecisionWork::class.java))
                    .isInstanceOf(DfnsWebhookDecisionTransaction::class.java)
                assertThat(context).hasSingleBean(DfnsChainEventDecision::class.java)
                assertThat(context).hasSingleBean(DfnsTransferEventDecision::class.java)
                assertThat(context).hasSingleBean(com.whatto.bcm.domain.tx.ChainHeadPort::class.java)
                assertThat(context).hasSingleBean(com.whatto.bcm.domain.vendor.VendorStatusTranslator::class.java)
                assertThat(context).hasSingleBean(com.whatto.bcm.domain.vendor.NetworkChainLedgerLookup::class.java)
            }
    }

    @Test
    fun `fireblocks·local 선택에서는 Dfns 판단 경계를 만들지 않는다`() {
        listOf("fireblocks", "local").forEach { provider ->
            runner.withPropertyValues("bcm.provider=$provider").run { context ->
                assertThat(context).hasNotFailed()
                assertThat(context).doesNotHaveBean(DfnsWebhookDecisionTransaction::class.java)
                assertThat(context).doesNotHaveBean(DfnsChainEventDecision::class.java)
                assertThat(context).doesNotHaveBean(DfnsTransferEventDecision::class.java)
                assertThat(context).doesNotHaveBean(com.whatto.bcm.domain.tx.ChainHeadPort::class.java)
            }
        }
    }
}
