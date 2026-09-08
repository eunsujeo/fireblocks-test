package com.whatto.bcm.app.api

import com.whatto.bcm.app.webhook.application.event.OutboxRelayJob
import com.whatto.bcm.app.webhook.application.webhook.WebhookDecisionJob
import com.whatto.bcm.testsupport.integration.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.ApplicationContext
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@SpringBootTest(classes = [BcmApiApplication::class])
@AutoConfigureMockMvc
class BcmApiWebhookBoundaryTest : IntegrationTestSupport() {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var context: ApplicationContext

    @Test
    fun `업무 API는 Fireblocks Webhook endpoint를 노출하지 않는다`() {
        mockMvc
            .perform(post("/webhook").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `업무 API는 Webhook 판단과 outbox relay scheduler를 조립하지 않는다`() {
        assertThat(context.getBeansOfType(WebhookDecisionJob::class.java)).isEmpty()
        assertThat(context.getBeansOfType(OutboxRelayJob::class.java)).isEmpty()
    }
}
