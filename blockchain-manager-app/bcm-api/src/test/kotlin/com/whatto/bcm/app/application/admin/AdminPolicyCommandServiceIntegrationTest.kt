package com.whatto.bcm.app.application.admin

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi
import com.whatto.bcm.app.api.BcmApiApplication
import com.whatto.bcm.app.api.support.IntegrationTestSupport
import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminRole
import com.whatto.bcm.domain.admin.ChangeDecision
import com.whatto.bcm.domain.admin.ChangeRisk
import com.whatto.bcm.domain.admin.SweepExecutionPolicy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.File
import java.math.BigDecimal

@SpringBootTest(
    classes = [BcmApiApplication::class],
    properties = [
        "bcm.admin-policy.hard-ceiling.execution-enabled=true",
        "bcm.admin-policy.hard-ceiling.maximum-batch-size=20",
        "bcm.admin-policy.hard-ceiling.maximum-allowance=1000",
        "bcm.admin-policy.hard-ceiling.maximum-item-amount=100",
        "bcm.admin-policy.hard-ceiling.maximum-batch-amount=500",
        "bcm.admin-policy.hard-ceiling.maximum-boost-attempts=2",
    ],
)
@AutoConfigureMockMvc
class AdminPolicyCommandServiceIntegrationTest : IntegrationTestSupport() {
    @Autowired
    lateinit var service: AdminPolicyCommandService

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `서버 hard ceiling 안의 정책은 독립 승인 뒤 한 번만 활성화한다`() {
        val requester = AdminActor("510001", "0001", setOf(AdminRole.BCM_OPERATOR))
        val approver = AdminActor("510002", "0002", setOf(AdminRole.BCM_APPROVER))
        val executor = AdminActor("510003", "0003", setOf(AdminRole.BCM_OPERATOR))
        val scope = "POLICY:BASE:INTEGRATION"
        val version =
            service.registerPolicy(
                RegisterSweepPolicyCommand(
                    scopeId = scope,
                    policy =
                        SweepExecutionPolicy(
                            enabled = true,
                            minimumAmount = BigDecimal("1"),
                            batchSize = 10,
                            allowanceCap = BigDecimal("1000"),
                            itemAmountCap = BigDecimal("100"),
                            batchAmountCap = BigDecimal("500"),
                            boostAttempts = 2,
                        ),
                    contractVersionId = null,
                    actor = requester,
                ),
            )
        val request =
            service.requestChange(
                RequestPolicyChangeCommand(
                    targetVersionId = version.versionId,
                    risk = ChangeRisk.GENERAL,
                    impact = PolicyImpactSnapshot(affectedAccounts = 0, openExecutions = 0, externalDriftFree = true),
                    reason = "통합 테스트 정책 활성화",
                    workTicket = "OPS-5100",
                    idempotencyKey = "request-integration",
                    actor = requester,
                ),
            )
        service.decide(
            DecidePolicyChangeCommand(
                requestId = request.lifecycle.requestId,
                decision = ChangeDecision.APPROVE,
                snapshotHash = request.lifecycle.targetSnapshotHash,
                opinion = "상한과 영향 확인",
                actor = approver,
            ),
        )

        val activated =
            service.activate(
                ActivatePolicyChangeCommand(request.lifecycle.requestId, "activate-integration", executor),
            )
        val retried =
            service.activate(
                ActivatePolicyChangeCommand(request.lifecycle.requestId, "activate-integration", executor),
            )

        assertThat(activated.bindingRevision).isEqualTo(1)
        assertThat(retried).isEqualTo(activated)
        assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM bcm_adm_actn_l WHERE req_id = ? AND actn_stcd = 'INTENT'",
                Int::class.java,
                request.lifecycle.requestId,
            ),
        ).isEqualTo(1)
        mockMvc
            .perform(get("/admin/policies"))
            .andExpect(status().isOk)
            .andExpect(openApi().isValid(SPEC))
            .andExpect(jsonPath("$.data[?(@.versionId == '${version.versionId}')].state").value("ACTIVE"))
        mockMvc
            .perform(get("/admin/change-requests/${request.lifecycle.requestId}"))
            .andExpect(status().isOk)
            .andExpect(openApi().isValid(SPEC))
            .andExpect(jsonPath("$.data.state").value("ACTIVATED"))
            .andExpect(jsonPath("$.data.approvalCount").value(1))
            .andExpect(jsonPath("$.data.activationReady").value(false))
            .andExpect(jsonPath("$.data.disabledReasons[0]").value("ALREADY_ACTIVATED"))
    }

    @Test
    fun `배포 hard ceiling을 넘긴 정책 버전은 활성화 준비 상태가 아니다`() {
        val requester = AdminActor("520001", "0001", setOf(AdminRole.BCM_OPERATOR))

        val version =
            service.registerPolicy(
                RegisterSweepPolicyCommand(
                    scopeId = "POLICY:BASE:OVER_CEILING",
                    policy =
                        SweepExecutionPolicy(
                            enabled = true,
                            minimumAmount = BigDecimal.ONE,
                            batchSize = 21,
                            allowanceCap = BigDecimal("1000"),
                            itemAmountCap = BigDecimal("100"),
                            batchAmountCap = BigDecimal("500"),
                            boostAttempts = 2,
                        ),
                    contractVersionId = null,
                    actor = requester,
                ),
            )

        assertThat(version.ceilingPassed).isFalse()
        assertThat(
            jdbc.queryForObject(
                "SELECT ceiling_pass_yn FROM bcm_plcy_vrsn_l WHERE plcy_vrsn_id = ?",
                String::class.java,
                version.versionId,
            ),
        ).isEqualTo("N")
    }

    companion object {
        private val SPEC: String = File("../../docs/api/openapi.yaml").absolutePath
    }
}
