package com.whatto.bcm.app.application.admin

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi
import com.whatto.bcm.app.api.BcmApiApplication
import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminRole
import com.whatto.bcm.domain.admin.ContractEvidenceStatus
import com.whatto.bcm.testsupport.integration.IntegrationTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.File
import java.math.BigInteger
import java.security.MessageDigest

@SpringBootTest(classes = [BcmApiApplication::class])
@AutoConfigureMockMvc
class AdminContractCommandServiceIntegrationTest : IntegrationTestSupport() {
    @Autowired
    lateinit var service: AdminContractCommandService

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `RPC 수집기가 없는 환경은 검증을 ERROR evidence로 남기고 fail closed한다`() {
        val actor = AdminActor("620001", "0001", setOf(AdminRole.BCM_OPERATOR))
        val version =
            service.register(
                RegisterContractCommand(
                    scopeId = "BASE:ALLOWANCE",
                    network = "BASE",
                    use = "ALLOWANCE",
                    version = "1.0.0-test",
                    address = "0xabcdef",
                    releaseCommit = "release-commit-fallback",
                    artifactHash = hash("artifact-fallback"),
                    abiHash = hash("abi-fallback"),
                    runtimeCodeHash = hash("runtime-fallback"),
                    deploymentTransactionHash = "0xdeployment-fallback",
                    deploymentBlockNumber = BigInteger.valueOf(5678),
                    immutableValues = "{\"destination\":\"0xomnibus\"}",
                    ceilingSnapshot = "{\"maximumBatchSize\":20}",
                    releaseUri = "repo://contracts/releases/1.0.0-test",
                    actor = actor,
                ),
            )

        val evidence = service.verify(VerifyContractCommand(version.versionId, actor))

        assertThat(evidence.evaluation.status).isEqualTo(ContractEvidenceStatus.ERROR)
        assertThat(evidence.evaluation.activationReady).isFalse()
        assertThat(evidence.evaluation.issues).containsExactly("RPC_1_UNAVAILABLE")
        assertThat(evidence.firstEndpointId).isNotEqualTo(evidence.secondEndpointId)
        mockMvc
            .perform(get("/admin/contracts"))
            .andExpect(status().isOk)
            .andExpect(openApi().isValid(SPEC))
            .andExpect(jsonPath("$.data[?(@.versionId == '${version.versionId}')].state").value("CANDIDATE"))
            .andExpect(jsonPath("$.data[?(@.versionId == '${version.versionId}')].evidenceStatus").value("ERROR"))
    }

    private fun hash(seed: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(seed.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        private val SPEC: String = File("../../docs/api/openapi.yaml").absolutePath
    }
}
