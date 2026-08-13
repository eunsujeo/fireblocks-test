package com.whatto.bcm.app.application.webhook

import com.whatto.bcm.domain.tx.FinalityPolicyConfigurationException
import com.whatto.bcm.domain.webhook.PoisonWebhookAlertPort
import com.whatto.bcm.domain.webhook.UnattributedDepositAlertPort
import com.whatto.bcm.domain.webhook.UnregisteredVaultTransferAlertPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class WebhookDecisionProcessorTest {
    @Test
    fun `finality 운영 설정 오류는 poison 재시도 횟수를 쓰지 않고 worker 경계로 전파한다`() {
        val transaction = mockk<WebhookDecisionTransaction>()
        every { transaction.processNext() } throws FinalityPolicyConfigurationException("ETHEREUM")
        val processor =
            WebhookDecisionProcessor(
                transaction,
                mockk<UnattributedDepositAlertPort>(relaxed = true),
                mockk<UnregisteredVaultTransferAlertPort>(relaxed = true),
                mockk<PoisonWebhookAlertPort>(relaxed = true),
            )

        assertThatThrownBy(processor::processNext)
            .isExactlyInstanceOf(FinalityPolicyConfigurationException::class.java)
        verify(exactly = 0) { transaction.recordUnexpectedFailure(any()) }
    }
}
