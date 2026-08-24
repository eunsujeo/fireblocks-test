package com.whatto.bcm.app.application.admin

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi
import com.ninjasquad.springmockk.MockkBean
import com.whatto.bcm.app.api.BcmApiApplication
import com.whatto.bcm.app.api.support.IntegrationTestSupport
import com.whatto.bcm.app.application.asset.VendorAssetMappingQueryService
import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminChangeRequest
import com.whatto.bcm.domain.admin.AdminRole
import com.whatto.bcm.domain.admin.BandSDirection
import com.whatto.bcm.domain.admin.BandSExecutionBoundary
import com.whatto.bcm.domain.admin.BandSExecutionEventStatus
import com.whatto.bcm.domain.admin.BandSExecutionStatus
import com.whatto.bcm.domain.admin.BandSLegType
import com.whatto.bcm.domain.admin.BandSNetworkAsset
import com.whatto.bcm.domain.admin.BandSRepository
import com.whatto.bcm.domain.admin.ChangeDecision
import com.whatto.bcm.domain.admin.ChangeRisk
import com.whatto.bcm.domain.admin.SweepExecutionPolicy
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.exception.RelayRejectedException
import com.whatto.bcm.domain.vendor.VendorTransactionDestination
import com.whatto.bcm.domain.vendor.VendorTransactionRequest
import com.whatto.bcm.domain.vendor.VendorTransactionSubmission
import com.whatto.bcm.infra.client.fireblocks.FireblocksClient
import io.mockk.every
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.File
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest(
    classes = [BcmApiApplication::class, BandSCommandServiceIntegrationTest.BoundaryConfig::class],
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
class BandSCommandServiceIntegrationTest : IntegrationTestSupport() {
    @Autowired
    lateinit var bandService: BandSCommandService

    @Autowired
    lateinit var policyService: AdminPolicyCommandService

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var bands: BandSRepository

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @MockkBean
    lateinit var mappings: VendorAssetMappingQueryService

    @MockkBean
    lateinit var vendor: FireblocksClient

    @Test
    fun `같은 snapshot proposal을 승인해 멱등 예약하고 item 부분 실패를 대사한다`() {
        every { mappings.requiredMapping("BASE", "USDC") } returns
            VendorAssetMapping("BASE", "USDC", "USDC_BASE", null, "20260817090000", "SYSTEM", "0000")
        val requester = actor("810001", AdminRole.BCM_OPERATOR)
        val approver = actor("810002", AdminRole.BCM_APPROVER)
        val executor = actor("810003", AdminRole.BCM_OPERATOR)
        val policy = activatePolicy(requester, approver, executor)
        val snapshot =
            bandService.registerSnapshot(
                RegisterBandSSnapshotCommand(
                    sourceRequestId = "daw-band-snapshot-1",
                    policyVersionId = policy,
                    observedAt = Instant.parse("2026-08-17T09:00:00Z"),
                    expiresAt = Instant.parse("2099-08-17T09:30:00Z"),
                    complete = true,
                    totalAssetKrwAmount = BigDecimal("1000000"),
                    observedHotKrwAmount = BigDecimal("250000"),
                    observedColdKrwAmount = BigDecimal("750000"),
                    effectiveHotKrwAmount = BigDecimal("240000"),
                    hotRatio = BigDecimal("24"),
                    lowerRatio = BigDecimal("8"),
                    targetRatio = BigDecimal("12.5"),
                    upperRatio = BigDecimal("18"),
                    inputPayload = "{\"provider\":\"DAW-CORE\",\"reservationAppliedOnce\":true}",
                    issueCodes = emptyList(),
                    actor = requester,
                ),
            )
        val proposal =
            bandService.registerProposal(
                RegisterBandSProposalCommand(
                    sourceProposalId = "daw-band-proposal-1",
                    snapshotId = snapshot.snapshotId,
                    direction = BandSDirection.HOT_TO_COLD,
                    totalKrwAmount = BigDecimal("280000"),
                    afterHotRatio = BigDecimal("12.5"),
                    executable = true,
                    blockReasons = emptyList(),
                    proposalPayload = "{\"calculatedBy\":\"DAW-CORE\"}",
                    items =
                        listOf(
                            internalItem(BigDecimal("100"), BigDecimal("140000")),
                            externalItem(BigDecimal("100"), BigDecimal("140000"), dependsOnSequence = 1),
                        ),
                    actor = requester,
                ),
            )
        val request =
            bandService.requestExecution(
                RequestBandSExecutionCommand(
                    proposal.proposalId,
                    "밴드 상한 초과 이동",
                    "OPS-8100",
                    "band-request-1",
                    requester,
                ),
            )
        policyService.decide(
            DecidePolicyChangeCommand(
                request.lifecycle.requestId,
                ChangeDecision.APPROVE,
                request.lifecycle.targetSnapshotHash,
                "snapshot과 목적지 확인",
                approver,
            ),
        )

        val execution =
            bandService.reserveExecution(
                ReserveBandSExecutionCommand(request.lifecycle.requestId, "band-execute-1", executor),
            )
        val retried =
            bandService.reserveExecution(
                ReserveBandSExecutionCommand(request.lifecycle.requestId, "band-execute-1", executor),
            )

        assertThat(retried).isEqualTo(execution)
        assertThat(execution.policyVersionId).isEqualTo(policy)
        assertThat(execution.proposalHash).isEqualTo(proposal.proposalHash)
        assertThat(execution.inputHash).isEqualTo(snapshot.inputHash)

        val vendorRequests = mutableListOf<VendorTransactionRequest>()
        every { vendor.submitTransaction(capture(vendorRequests)) } answers {
            when (firstArg<VendorTransactionRequest>().externalTransactionId) {
                "band-${execution.executionId}-1" -> VendorTransactionSubmission.Accepted("vendor-band-1")
                else -> throw RelayRejectedException("cold transfer rejected")
            }
        }
        val submitted = bandService.submitItem(SubmitBandSItemCommand(execution.executionId, 1, executor))
        val duplicate = bandService.submitItem(SubmitBandSItemCommand(execution.executionId, 1, executor))

        assertThat(submitted.itemStates.single { it.sequence == 1 }.status)
            .isEqualTo(BandSExecutionEventStatus.SUBMITTED)
        assertThat(duplicate).isEqualTo(submitted)
        assertThat(vendorRequests.first().sourceVaultId).isEqualTo("withdrawal-pool-base")
        assertThat(vendorRequests.first().destination).isEqualTo(VendorTransactionDestination.Account("omnibus-base"))
        assertThat(vendorRequests.first().useGasless).isFalse()
        assertThat(
            jdbc.queryForMap(
                "SELECT tx_dvcd, snd_acnt_id, rcv_vl, sbmt_stcd FROM bcm_sbmt_l WHERE ext_tx_id = ?",
                "band-${execution.executionId}-1",
            ),
        ).containsEntry("tx_dvcd", "BAND_S")
            .containsEntry("snd_acnt_id", "withdrawal-pool-base")
            .containsEntry("rcv_vl", "omnibus-base")
            .containsEntry("sbmt_stcd", "SUBMITTED")

        record(execution.executionId, 1, BandSExecutionEventStatus.FINALIZED, null, null, executor)
        record(execution.executionId, 1, BandSExecutionEventStatus.RECONCILED, null, null, executor)
        assertThatThrownBy {
            bandService.submitItem(SubmitBandSItemCommand(execution.executionId, 2, executor))
        }.isInstanceOf(RelayRejectedException::class.java)
        assertThat(vendorRequests.last().sourceVaultId).isEqualTo("omnibus-base")
        assertThat(vendorRequests.last().destination).isEqualTo(VendorTransactionDestination.Address("cold-base-usdc"))
        assertThat(vendorRequests.last().useGasless).isFalse()
        val partial = requireNotNull(bands.findExecution(execution.executionId))

        assertThat(partial.status).isEqualTo(BandSExecutionStatus.PARTIAL)
        assertThat(partial.itemStates).extracting<Int> { it.sequence }.containsExactly(1, 2)
        mockMvc
            .perform(get("/admin/band-s").header("X-Request-Id", "band-read-1"))
            .andExpect(status().isOk)
            .andExpect(openApi().isValid(SPEC))
            .andExpect(jsonPath("$.meta.requestId").isNotEmpty)
            .andExpect(jsonPath("$.data[0].proposalId").value(proposal.proposalId))
            .andExpect(jsonPath("$.data[0].snapshotHash").value(snapshot.snapshotHash))
            .andExpect(jsonPath("$.data[0].policyVersionId").value(policy))
            .andExpect(jsonPath("$.data[0].state").value("PARTIAL"))
            .andExpect(jsonPath("$.data[0].requestState").value("EXECUTED"))
            .andExpect(jsonPath("$.data[0].items[0].executionStatus").value("RECONCILED"))
            .andExpect(jsonPath("$.data[0].items[1].executionStatus").value("FAILED"))
            .andExpect(jsonPath("$.data[0].disabledReasons").isArray)
        verify(exactly = 2) { vendor.submitTransaction(any()) }
    }

    @Test
    fun `같은 실행 요청을 동시에 예약해도 하나의 실행 원장으로 수렴한다`() {
        val prepared = prepareApprovedExecution("concurrent")
        val barrier = CyclicBarrier(2)
        val executorService = Executors.newFixedThreadPool(2)
        try {
            val futures =
                (1..2).map {
                    executorService.submit<com.whatto.bcm.domain.admin.BandSExecutionRecord> {
                        barrier.await(5, TimeUnit.SECONDS)
                        bandService.reserveExecution(
                            ReserveBandSExecutionCommand(
                                prepared.request.lifecycle.requestId,
                                "band-execute-concurrent",
                                prepared.executor,
                            ),
                        )
                    }
                }
            val executions = futures.map { it.get(10, TimeUnit.SECONDS) }

            assertThat(executions.map { it.executionId }.distinct()).hasSize(1)
            assertThat(
                jdbc.queryForObject(
                    "SELECT count(*) FROM bcm_bnds_exec_l WHERE req_id = ?",
                    Long::class.java,
                    prepared.request.lifecycle.requestId,
                ),
            ).isEqualTo(1L)
        } finally {
            executorService.shutdownNow()
        }
    }

    private fun prepareApprovedExecution(suffix: String): PreparedBandSExecution {
        val requester = actor("820001", AdminRole.BCM_OPERATOR)
        val approver = actor("820002", AdminRole.BCM_APPROVER)
        val executor = actor("820003", AdminRole.BCM_OPERATOR)
        val policy = activatePolicy(requester, approver, executor, suffix)
        val snapshot =
            bandService.registerSnapshot(
                RegisterBandSSnapshotCommand(
                    "daw-band-snapshot-$suffix",
                    policy,
                    Instant.parse("2026-08-17T09:00:00Z"),
                    Instant.parse("2099-08-17T09:30:00Z"),
                    true,
                    BigDecimal("1000000"),
                    BigDecimal("250000"),
                    BigDecimal("750000"),
                    BigDecimal("240000"),
                    BigDecimal("24"),
                    BigDecimal("8"),
                    BigDecimal("12.5"),
                    BigDecimal("18"),
                    "{\"provider\":\"DAW-CORE\"}",
                    emptyList(),
                    requester,
                ),
            )
        val proposal =
            bandService.registerProposal(
                RegisterBandSProposalCommand(
                    "daw-band-proposal-$suffix",
                    snapshot.snapshotId,
                    BandSDirection.HOT_TO_COLD,
                    BigDecimal("140000"),
                    BigDecimal("12.5"),
                    true,
                    emptyList(),
                    "{\"calculatedBy\":\"DAW-CORE\"}",
                    listOf(externalItem(BigDecimal("100"), BigDecimal("140000"))),
                    requester,
                ),
            )
        val request =
            bandService.requestExecution(
                RequestBandSExecutionCommand(
                    proposal.proposalId,
                    "동시 예약 검증",
                    "OPS-$suffix",
                    "band-request-$suffix",
                    requester,
                ),
            )
        policyService.decide(
            DecidePolicyChangeCommand(
                request.lifecycle.requestId,
                ChangeDecision.APPROVE,
                request.lifecycle.targetSnapshotHash,
                "동시 예약 승인",
                approver,
            ),
        )
        return PreparedBandSExecution(request, executor)
    }

    private fun activatePolicy(
        requester: AdminActor,
        approver: AdminActor,
        executor: AdminActor,
        suffix: String = "1",
    ): String {
        val version =
            policyService.registerPolicy(
                RegisterSweepPolicyCommand(
                    "BAND_S:GLOBAL:INTEGRATION:$suffix",
                    SweepExecutionPolicy(
                        true,
                        BigDecimal.ONE,
                        10,
                        BigDecimal("1000"),
                        BigDecimal("100"),
                        BigDecimal("500"),
                        2,
                    ),
                    null,
                    requester,
                ),
            )
        val request =
            policyService.requestChange(
                RequestPolicyChangeCommand(
                    version.versionId,
                    ChangeRisk.GENERAL,
                    PolicyImpactSnapshot(0, 0, true),
                    "밴드S 정책 활성화",
                    "OPS-8099",
                    "band-policy-request-$suffix",
                    requester,
                ),
            )
        policyService.decide(
            DecidePolicyChangeCommand(
                request.lifecycle.requestId,
                ChangeDecision.APPROVE,
                request.lifecycle.targetSnapshotHash,
                null,
                approver,
            ),
        )
        policyService.activate(ActivatePolicyChangeCommand(request.lifecycle.requestId, "band-policy-activate-$suffix", executor))
        return version.versionId
    }

    private fun externalItem(
        amount: BigDecimal,
        krwAmount: BigDecimal,
        dependsOnSequence: Int? = null,
    ) = RegisterBandSProposalItem(
        dependsOnSequence = dependsOnSequence,
        legType = BandSLegType.EXTERNAL_COLD,
        network = "BASE",
        tokenSymbol = "USDC",
        sourceVaultId = "omnibus-base",
        destinationVaultId = null,
        destinationAddress = "cold-base-usdc",
        amount = amount,
        krwAmount = krwAmount,
        expectedFeeAmount = BigDecimal("0.1"),
        executable = true,
        blockReason = null,
    )

    private fun internalItem(
        amount: BigDecimal,
        krwAmount: BigDecimal,
    ) = RegisterBandSProposalItem(
        dependsOnSequence = null,
        legType = BandSLegType.INTERNAL_TO_EGRESS,
        network = "BASE",
        tokenSymbol = "USDC",
        sourceVaultId = "withdrawal-pool-base",
        destinationVaultId = "omnibus-base",
        destinationAddress = null,
        amount = amount,
        krwAmount = krwAmount,
        expectedFeeAmount = BigDecimal.ZERO,
        executable = true,
        blockReason = null,
    )

    private fun record(
        executionId: String,
        itemSequence: Int,
        status: BandSExecutionEventStatus,
        externalTransactionId: String?,
        vendorTransactionId: String?,
        actor: AdminActor,
    ) = bandService.recordEvent(
        RecordBandSExecutionEventCommand(
            executionId,
            itemSequence,
            status,
            externalTransactionId,
            vendorTransactionId,
            "{\"source\":\"TEST\"}",
            actor,
        ),
    )

    private fun actor(
        employeeNo: String,
        role: AdminRole,
    ) = AdminActor(employeeNo, "0001", setOf(role))

    @TestConfiguration(proxyBeanMethods = false)
    class BoundaryConfig {
        @Bean
        @Primary
        fun testBandSBoundary() =
            BandSExecutionBoundary(
                mapOf("BASE" to "omnibus-base"),
                mapOf("BASE" to setOf("withdrawal-pool-base")),
                mapOf(BandSNetworkAsset("BASE", "USDC") to "cold-base-usdc"),
            )
    }

    private data class PreparedBandSExecution(
        val request: AdminChangeRequest,
        val executor: AdminActor,
    )

    companion object {
        private val SPEC: String = File("../../docs/api/openapi.yaml").absolutePath
    }
}
