package com.whatto.bcm.admin

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

@SpringBootTest
@AutoConfigureMockMvc
class AdminFunctionalE2eTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `독립 Admin 셸과 BFF overview가 실제 BCM 읽기 계약을 연결한다`() {
        mockMvc
            .perform(get("/admin/dashboard"))
            .andExpect(status().isOk)
            .andExpect(forwardedUrl("/admin/index.html"))

        val indexHtml =
            mockMvc
                .perform(get("/admin/index.html"))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .getContentAsString(StandardCharsets.UTF_8)
        assertThat(indexHtml)
            .contains("BCM ADMIN")
            .contains("runtime-capability")

        val appScript =
            mockMvc
                .perform(get("/admin/app.js"))
                .andExpect(status().isOk)
                .andReturn()
                .response
                .getContentAsString(StandardCharsets.UTF_8)
        assertThat(appScript)
            .contains("처음 설정하는 순서")
            .contains("Webhook 처리 상태")
            .doesNotContain("rawPayload")
            .doesNotContain("signature")

        mockMvc
            .perform(get("/bff/admin/overview").header("X-Request-Id", "test-request"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.catalogNetworkCount").value(2))
            .andExpect(jsonPath("$.data.adoptedNetworkCount").value(2))
            .andExpect(jsonPath("$.data.networkCount").value(2))
            .andExpect(jsonPath("$.data.assetMappingCount").value(1))
            .andExpect(jsonPath("$.data.webhook.state").value("NEVER_RECEIVED"))
            .andExpect(jsonPath("$.data.preparationChecks[0].owner").value("AUTO"))
            .andExpect(jsonPath("$.data.preparationChecks[4].owner").value("DIRECT"))
            .andExpect(jsonPath("$.state").value("FRESH"))
            .andExpect(jsonPath("$.meta.requestId").value("test-request"))
    }

    @Test
    fun `거래 조사 셸과 BFF는 원거래와 활성 거래 및 연결 증거를 보존한다`() {
        mockMvc
            .perform(get("/admin/transactions/tx-root"))
            .andExpect(status().isOk)
            .andExpect(forwardedUrl("/admin/index.html"))

        mockMvc
            .perform(get("/bff/admin/transactions/tx-root").header("X-Request-Id", "investigation-request"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.summary.rootTransactionId").value("tx-root"))
            .andExpect(jsonPath("$.data.summary.activeTransactionId").value("tx-new"))
            .andExpect(jsonPath("$.data.timeline[0].source").value("SUBMISSION"))
            .andExpect(jsonPath("$.data.boosts[0].newTransactionId").value("tx-new"))
            .andExpect(jsonPath("$.data.sweepExecution.items.length()").value(2))
            .andExpect(jsonPath("$.data.allowances[0].observedAllowance").value("90"))
            .andExpect(jsonPath("$.data.feeQuotes[0].gasPrice").value("2.1"))
            .andExpect(jsonPath("$.state").value("FRESH"))
            .andExpect(jsonPath("$.meta.requestId").value("investigation-request"))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("raw-payload"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("signature"))))
    }

    @Test
    fun `컨트랙트 정책 밴드S 변경 요청 셸은 읽기 전용 BFF 계약만 연결한다`() {
        listOf("/admin/contracts", "/admin/policies", "/admin/band-s", "/admin/change-requests/request-1").forEach { path ->
            mockMvc.perform(get(path)).andExpect(status().isOk).andExpect(forwardedUrl("/admin/index.html"))
        }

        mockMvc
            .perform(get("/bff/admin/contracts"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].scopeId").value("BASE:SWEEP"))
            .andExpect(jsonPath("$.data[0].evidenceStatus").value("VALID"))
        mockMvc
            .perform(get("/bff/admin/policies"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].ceilingPassed").value(true))
        mockMvc
            .perform(get("/bff/admin/band-s"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].state").value("PARTIAL"))
            .andExpect(jsonPath("$.data[0].snapshotHash").value("${"d".repeat(64)}"))
            .andExpect(jsonPath("$.data[0].items[0].executionStatus").value("RECONCILED"))
            .andExpect(jsonPath("$.state").value("PARTIAL"))
        mockMvc
            .perform(get("/bff/admin/change-requests/request-1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.approvalCount").value(1))
            .andExpect(jsonPath("$.data.activationReady").value(false))

        mockMvc.perform(post("/bff/admin/change-requests/request-1/decisions")).andExpect(status().isNotFound)
        mockMvc.perform(post("/bff/admin/band-s/proposal-1/execute")).andExpect(status().isNotFound)
    }

    @Test
    fun `비상 운영 셸과 BFF는 서버 계산 게이트를 읽기 전용으로 연결한다`() {
        mockMvc
            .perform(get("/admin/emergency"))
            .andExpect(status().isOk)
            .andExpect(forwardedUrl("/admin/index.html"))

        mockMvc
            .perform(get("/bff/admin/emergency").header("X-Request-Id", "emergency-request"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.observedAt").value("2026-08-17T12:15:00Z"))
            .andExpect(jsonPath("$.data.gates[0].network").value("BASE"))
            .andExpect(jsonPath("$.data.gates[0].state").value("STOPPED"))
            .andExpect(jsonPath("$.data.gates[0].newExecutionAllowed").value(false))
            .andExpect(jsonPath("$.data.gates[0].existingExecutionRecoveryAllowed").value(true))
            .andExpect(jsonPath("$.data.gates[2].emergencyRevocationAllowed").value(true))
            .andExpect(jsonPath("$.data.externalControls[0].status").value("CONFIRMED"))
            .andExpect(jsonPath("$.data.externalControls[0].completionReady").value(true))
            .andExpect(jsonPath("$.data.externalControls[0].tapBlocked").value(true))
            .andExpect(jsonPath("$.data.allowanceRevocations[0].status").value("PARTIAL"))
            .andExpect(jsonPath("$.data.allowanceRevocations[0].zeroConfirmedCount").value(1))
            .andExpect(jsonPath("$.data.webhookRecoveries[0].state").value("COMPLETED"))
            .andExpect(jsonPath("$.data.webhookRecoveries[0].scheduledNotificationCount").value(3))
            .andExpect(jsonPath("$.state").value("PARTIAL"))
            .andExpect(jsonPath("$.meta.requestId").value("emergency-request"))

        mockMvc.perform(post("/bff/admin/emergency/stop")).andExpect(status().isNotFound)
    }

    @Test
    fun `로컬 자산 등록은 동일 Origin과 전용 헤더를 모두 확인한다`() {
        mockMvc
            .perform(
                get("/bff/admin/asset-candidates")
                    .param("q", "USD Coin")
                    .header("Origin", "http://localhost")
                    .header("X-BCM-Local-Asset-Management", "execute"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.items[0].network").value("BASE"))
            .andExpect(jsonPath("$.data.items[0].networkDisplayName").value("Base"))
            .andExpect(jsonPath("$.data.items[0].testnet").value(false))
            .andExpect(jsonPath("$.data.items[0].fireblocksAssetId").value("USDC_BASE"))
            .andExpect(jsonPath("$.data.items[0].decimals").value(6))
            .andExpect(jsonPath("$.data.sources[0].state").value("READY"))

        mockMvc
            .perform(
                post("/bff/admin/assets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Origin", "http://localhost")
                    .header("X-BCM-Local-Asset-Management", "execute")
                    .content("""{"network":"BASE","symbol":"USDC","fireblocksAssetId":"USDC_BASE","contractAddress":"0x8335"}"""),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.symbol").value("USDC"))
            .andExpect(jsonPath("$.data.fireblocksAssetId").value("USDC_BASE"))

        mockMvc
            .perform(
                post("/bff/admin/assets")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"network":"BASE","symbol":"USDC","fireblocksAssetId":"USDC_BASE","contractAddress":"0x8335"}"""),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `로컬 네트워크 채택은 동일 Origin과 전용 헤더를 모두 확인한다`() {
        mockMvc
            .perform(
                put("/bff/admin/networks/ETHEREUM")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Origin", "http://localhost")
                    .header("X-BCM-Local-Asset-Management", "execute")
                    .content("""{"candidateId":"ethereum-candidate"}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.code").value("ETHEREUM"))

        mockMvc
            .perform(
                put("/bff/admin/networks/ETHEREUM")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"candidateId":"ethereum-candidate"}"""),
            ).andExpect(status().isForbidden)

        mockMvc
            .perform(
                put("/bff/admin/networks/ETHEREUM")
                    .with { request -> request.also { it.serverName = "evil.example" } }
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Origin", "http://evil.example")
                    .header("X-BCM-Local-Asset-Management", "execute")
                    .content("""{"candidateId":"ethereum-candidate"}"""),
            ).andExpect(status().isForbidden)
    }

    companion object {
        private val server: HttpServer =
            HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
                createContext("/admin/networks") { exchange ->
                    if (exchange.requestMethod == "PUT") {
                        check(exchange.requestHeaders.getFirst("X-Employee-No") == "LOCAL")
                        check(exchange.requestHeaders.getFirst("X-Branch-Code") == "9999")
                        check(
                            exchange.requestBody.readAllBytes().toString(StandardCharsets.UTF_8) ==
                                """{"candidateId":"ethereum-candidate"}""",
                        )
                        respond(
                            exchange,
                            """{"data":{"candidateId":"ethereum-candidate","code":"ETHEREUM","displayName":"Ethereum","chainId":1,"testnet":false,"deprecated":false,"syncedAt":"20260817080000"},"meta":{"requestId":"bcm-network-adopt"}}""",
                        )
                        return@createContext
                    }
                    respond(
                        exchange,
                        """{"data":[{"candidateId":"base-candidate","code":"BASE","displayName":"Base","chainId":8453,"testnet":false,"deprecated":false,"syncedAt":"20260817080000"},{"candidateId":"sepolia-candidate","code":"SEPOLIA","displayName":"Sepolia","chainId":11155111,"testnet":true,"deprecated":false,"syncedAt":"20260817080000"}],"meta":{"requestId":"bcm-networks"}}""",
                    )
                }
                createContext("/admin/asset-mappings") { exchange ->
                    check(exchange.requestHeaders.getFirst("X-Employee-No") == if (exchange.requestMethod == "POST") "LOCAL" else null)
                    check(exchange.requestHeaders.getFirst("X-Branch-Code") == if (exchange.requestMethod == "POST") "9999" else null)
                    respond(
                        exchange,
                        """{"data":${if (exchange.requestMethod == "POST") "{\"network\":\"BASE\",\"symbol\":\"USDC\",\"fireblocksAssetId\":\"USDC_BASE\",\"contractAddress\":\"0x8335\",\"registeredAt\":\"20260817080000\"}" else "[{\"network\":\"BASE\",\"symbol\":\"USDC\",\"fireblocksAssetId\":\"USDC_BASE\",\"contractAddress\":\"0x8335\",\"registeredAt\":\"20260817080000\"}]"},"meta":{"requestId":"bcm-assets"}}""",
                        if (exchange.requestMethod == "POST") 201 else 200,
                    )
                }
                createContext("/admin/asset-candidates") { exchange ->
                    check(exchange.requestURI.query?.contains("q=USD+Coin") == true)
                    respond(
                        exchange,
                        """{"data":{"items":[{"network":"BASE","networkDisplayName":"Base","chainId":8453,"testnet":false,"symbol":"USDC","displayName":"USD Coin","fireblocksAssetId":"USDC_BASE","assetClass":"FT","decimals":6,"contractAddress":"0x8335","catalogSyncedAt":"20260824010000","registrationAllowed":true,"registrationDisabledReason":null}],"sources":[{"network":"BASE","networkDisplayName":"Base","state":"READY","catalogSyncedAt":"20260824010000"}]},"meta":{"requestId":"bcm-candidates"}}""",
                    )
                }
                createContext("/admin/runtime-readiness") { exchange -> respond(exchange, runtimeReadinessResponse) }
                createContext("/actuator/health") { exchange -> respond(exchange, """{"status":"UP"}""") }
                createContext("/admin/transaction-investigations/tx-root") { exchange ->
                    respond(exchange, transactionInvestigationResponse)
                }
                createContext("/admin/contracts") { exchange -> respond(exchange, contractResponse) }
                createContext("/admin/policies") { exchange -> respond(exchange, policyResponse) }
                createContext("/admin/band-s") { exchange -> respond(exchange, bandSResponse) }
                createContext("/admin/execution-gates") { exchange -> respond(exchange, executionGateResponse) }
                createContext("/admin/change-requests/request-1") { exchange -> respond(exchange, changeRequestResponse) }
                start()
            }

        @JvmStatic
        @DynamicPropertySource
        fun adminProperties(registry: DynamicPropertyRegistry) {
            registry.add("bcm.admin.target-base-url") { "http://127.0.0.1:${server.address.port}" }
            registry.add("bcm.admin.webhook-management-base-url") { "http://127.0.0.1:${server.address.port}" }
            registry.add("bcm.admin.stale-after-seconds") { "31536000" }
            registry.add("bcm.admin.local-asset-management.enabled") { "true" }
            registry.add("bcm.admin.local-asset-management.employee-no") { "LOCAL" }
            registry.add("bcm.admin.local-asset-management.branch-code") { "9999" }
        }

        @JvmStatic
        @AfterAll
        fun stopServer() {
            server.stop(0)
        }

        private fun respond(
            exchange: HttpExchange,
            body: String,
            status: Int = 200,
        ) {
            val bytes = body.toByteArray(StandardCharsets.UTF_8)
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        private val transactionInvestigationResponse =
            """
            {
              "data": {
                "summary": {
                  "rootTransactionId": "tx-root",
                  "activeTransactionId": "tx-new",
                  "externalTransactionId": "wd-1",
                  "transactionHash": "0xactive",
                  "accountId": "acct-1",
                  "network": "BASE",
                  "symbol": "USDC",
                  "transactionType": "WITHDRAWAL",
                  "status": "FINALIZED",
                  "confirmationCount": 12,
                  "submissionStatus": "SUBMITTED",
                  "amount": "30",
                  "vendorCreatedAt": "2026-08-17T12:00:00Z",
                  "firstDetectedAt": "2026-08-17T12:00:10Z",
                  "lastChangedAt": "2026-08-17T12:07:00Z",
                  "reconciliationCheckCount": 2
                },
                "timeline": [
                  {"source":"SUBMISSION","code":"REQUESTED","status":"SUBMITTED","observedAt":"2026-08-17T12:00:00Z","identifier":"wd-1"},
                  {"source":"BOOST","code":"ATTEMPT_1","status":"SUBMITTED","observedAt":"2026-08-17T12:05:00Z","identifier":"bst-1"}
                ],
                "boosts": [{"attemptSequence":1,"externalTransactionId":"bst-1","status":"SUBMITTED","replacedTransactionId":"tx-root","replacedTransactionHash":"0xold","newTransactionId":"tx-new","feeLevel":"HIGH","gasless":true,"requestedAt":"2026-08-17T12:05:00Z"}],
                "sweepExecution": {"executionId":"swx-1","externalTransactionId":"swp-1","status":"PARTIAL","operatorAccountId":"operator-1","contractAddress":"0xsweeper","requestedTotalAmount":"30","actualTotalAmount":"10","transactionId":"tx-root","transactionHash":"0xactive","requestedAt":"2026-08-17T12:00:00Z","items":[{"sequence":1,"accountId":"acct-1","sourceAddress":"0xsource1","requestedAmount":"10","actualAmount":"10","status":"SUCCEEDED"},{"sequence":2,"accountId":"acct-2","sourceAddress":"0xsource2","requestedAmount":"20","status":"RETRY"}]},
                "allowances": [{"accountId":"acct-1","network":"BASE","symbol":"USDC","contractAddress":"0xsweeper","cap":"100","observedAllowance":"90","status":"ACTIVE","checkedAt":"2026-08-17T11:59:00Z"}],
                "feeQuotes": [{"context":"SUBMISSION","level":"MEDIUM","observedAt":"2026-08-17T11:59:00Z","gasPrice":"2.1"}],
                "truncatedSources": []
              },
              "meta": {"requestId": "bcm-investigation"}
            }
            """.trimIndent()

        private val runtimeReadinessResponse =
            """{"data":{"observedAt":"2026-08-21T01:00:00Z","webhook":{"state":"NEVER_RECEIVED","pendingInboxCount":0,"poisonedInboxCount":0,"pendingOutboxCount":0,"poisonedOutboxCount":0,"statusPath":"/admin/emergency"}},"meta":{"requestId":"runtime-readiness"}}"""

        private val contractResponse =
            """{"data":[{"versionId":"contract-v1","scopeId":"BASE:SWEEP","network":"BASE","use":"SWEEP","version":"1.0.0","address":"0xcontract","state":"VERIFIED","runtimeCodeHash":"${"a".repeat(
                64,
            )}","evidenceStatus":"VALID","evidenceValidUntil":"2099-12-31T23:59:59Z","active":false}],"meta":{"requestId":"contract-list"}}"""

        private val policyResponse =
            """{"data":[{"versionId":"policy-v1","scopeId":"POLICY:BASE:USDC","versionNumber":1,"schemaVersion":"v1","state":"APPROVED","policyHash":"${"b".repeat(
                64,
            )}","ceilingPassed":true,"active":false,"registeredAt":"2026-08-17T12:00:00Z"}],"meta":{"requestId":"policy-list"}}"""

        private val bandSResponse =
            """
            {
              "data": [{
                "proposalId": "proposal-1", "sourceProposalId": "daw-proposal-1",
                "snapshotId": "snapshot-1", "sourceRequestId": "daw-snapshot-1",
                "policyVersionId": "policy-v1", "snapshotHash": "${"d".repeat(64)}",
                "inputHash": "${"e".repeat(64)}", "observedAt": "2026-08-17T12:00:00Z",
                "expiresAt": "2099-08-17T12:30:00Z", "inputComplete": true, "issueCodes": [],
                "totalAssetKrwAmount": "1000000", "observedHotKrwAmount": "250000",
                "observedColdKrwAmount": "750000", "effectiveHotKrwAmount": "240000",
                "hotRatio": "24", "lowerRatio": "8", "targetRatio": "12.5", "upperRatio": "18",
                "direction": "HOT_TO_COLD", "proposalHash": "${"f".repeat(64)}",
                "totalKrwAmount": "280000", "afterHotRatio": "12.5", "state": "PARTIAL",
                "requestId": "request-1", "requestState": "EXECUTED", "approvalCount": 1,
                "requiredApprovals": 1, "executionId": "execution-1", "executionStatus": "PARTIAL",
                "reservedAt": "2026-08-17T12:10:00Z", "executionReady": false,
                "disabledReasons": ["ALREADY_RESERVED", "READ_ONLY_AUTH_BOUNDARY"],
                "items": [{
                  "sequence": 1, "dependsOnSequence": null, "legType": "EXTERNAL_COLD",
                  "network": "BASE", "tokenSymbol": "USDC", "sourceVaultId": "omnibus-base",
                  "destinationVaultId": null, "destinationAddress": "cold-base-usdc", "amount": "100",
                  "krwAmount": "140000", "expectedFeeAmount": "0.1", "itemHash": "${"1".repeat(64)}",
                  "executable": true, "blockReason": null, "executionStatus": "RECONCILED"
                }]
              }],
              "meta": {"requestId": "band-s-list"}
            }
            """.trimIndent()

        private val changeRequestResponse =
            """{"data":{"requestId":"request-1","targetType":"POLICY","scopeId":"POLICY:BASE:USDC","targetVersionId":"policy-v1","state":"ACTIVATED","risk":"GENERAL","snapshotHash":"${"c".repeat(
                64,
            )}","diff":"{}","impact":"{}","reason":"테스트","workTicket":"OPS-1","requesterEmployeeNo":"123456","requestedAt":"2026-08-17T12:00:00Z","expiresAt":"2099-12-31T23:59:59Z","requiredApprovals":1,"approvalCount":1,"securityApprovalRequired":false,"securityApprovalCount":0,"activationReady":false,"disabledReasons":["ALREADY_ACTIVATED"],"decisions":[{"employeeNo":"222222","role":"BCM_APPROVER","decision":"APPROVE","opinion":"확인","decidedAt":"2026-08-17T12:10:00Z"}]},"meta":{"requestId":"request-detail"}}"""

        private val executionGateResponse =
            """
            {
              "data": {
                "observedAt": "2026-08-17T12:15:00Z",
                "truncated": false,
                "gates": [
                  {"network":"BASE","type":"WITHDRAWAL","state":"STOPPED","stoppedAt":"2026-08-17T12:00:00Z","reason":"출금 이상 징후","workTicket":"SEC-1061","actorEmployeeNo":"810001","sequence":1,"newExecutionAllowed":false,"existingExecutionRecoveryAllowed":true,"emergencyRevocationAllowed":false,"disabledReasons":["EXECUTION_GATE_STOPPED"]},
                  {"network":"BASE","type":"SWEEP","state":"OPEN","newExecutionAllowed":true,"existingExecutionRecoveryAllowed":true,"emergencyRevocationAllowed":false,"disabledReasons":[]},
                  {"network":"BASE","type":"APPROVE","state":"STOPPED","stoppedAt":"2026-08-17T12:01:00Z","reason":"allowance 회수","workTicket":"SEC-1061","actorEmployeeNo":"810001","sequence":1,"newExecutionAllowed":false,"existingExecutionRecoveryAllowed":true,"emergencyRevocationAllowed":true,"disabledReasons":["EXECUTION_GATE_STOPPED"]}
                ],
                "externalControls": [
                  {"evidenceId":"external-evidence-1","network":"BASE","contractVersionId":"contract-v1","status":"CONFIRMED","completionReady":true,"snapshotHash":"${"a".repeat(
                64,
            )}","tapSourceId":"tap-policy-api","tapBlocked":true,"pinnedBlockNumber":"1234","expectedOperatorSetHash":"${"b".repeat(
                64,
            )}","firstEndpointId":"rpc-a","firstPaused":true,"firstOperatorSetHash":"${"b".repeat(
                64,
            )}","secondEndpointId":"rpc-b","secondPaused":true,"secondOperatorSetHash":"${"b".repeat(
                64,
            )}","observedAt":"2026-08-17T12:10:00Z","validUntil":"2099-12-31T23:59:59Z","reason":"비상 외부 통제 확인","workTicket":"SEC-1062","actorEmployeeNo":"810001","issues":[]}
                ],
                "allowanceRevocations": [{
                  "executionId":"revocation-1","requestId":"request-1","network":"BASE",
                  "contractVersionId":"contract-v1","contractBindingRevision":3,
                  "sweepContractAddress":"0xsweeper","targetSnapshotHash":"${"d".repeat(64)}",
                  "status":"PARTIAL","totalCount":2,"zeroConfirmedCount":1,"submittingCount":0,
                  "failedCount":1,"registeredAt":"2026-08-17T12:12:00Z",
                  "retryable":true,"retryCondition":"FAILED_ITEMS_CAN_RETRY","statusPath":"/admin/execution-gates","items":[{
                    "sequence":1,"accountId":"account-1","network":"BASE","symbol":"USDC",
                    "sourceVaultId":"vault-1","ownerAddress":"0xowner","tokenContractAddress":"0xtoken",
                    "beforeObservedAllowance":"25","externalTransactionId":"arv-1",
                    "latestStatus":"ZERO_CONFIRMED","vendorTransactionId":null,"observedAllowance":"0",
                    "observedAt":"2026-08-17T12:14:00Z",
                    "errorCode":null,"occurredAt":"2026-08-17T12:14:00Z"
                  }]
                }],
                "webhookRecoveries": [{
                  "requestId":"recovery-1","webhookId":"webhook-1","state":"COMPLETED",
                  "scope":"FAILED_LAST_24H","requiredEvents":["transaction.created","transaction.status.updated"],
                  "requestedAt":"2026-08-17T12:05:00Z","requestedByEmployeeNo":"810001",
                  "approvedAt":"2026-08-17T12:06:00Z","approvedByEmployeeNo":"810002",
                  "reason":"웹훅 수신 공백","workTicket":"INC-100","latestEvent":"RESEND_ACCEPTED",
                  "callType":"RESEND_FAILED","calledAt":"2026-08-17T12:10:00Z","resultAt":"2026-08-17T12:10:01Z",
                  "previousStatus":"SUSPENDED","currentStatus":"ENABLED","missingRequiredEvents":[],
                  "scopeFrom":"2026-08-16T12:10:00Z","scopeTo":"2026-08-17T12:10:00Z",
                  "scheduledNotificationCount":3,"errorCode":null,
                  "retryable":false,"retryCondition":"COMPLETED","statusPath":"/admin/execution-gates"
                }],
                "resumes":[]
              },
              "meta": {"requestId": "execution-gate-list"}
            }
            """.trimIndent()
    }
}
