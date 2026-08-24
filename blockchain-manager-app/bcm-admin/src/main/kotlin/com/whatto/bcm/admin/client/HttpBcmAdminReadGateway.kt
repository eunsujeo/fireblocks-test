package com.whatto.bcm.admin.client

import com.whatto.bcm.admin.config.AdminProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

@Component
class HttpBcmAdminReadGateway(
    private val httpClient: HttpClient,
    private val objectMapper: ObjectMapper,
    private val properties: AdminProperties,
) : BcmAdminReadGateway {
    override fun networks(
        q: String?,
        chainId: Long?,
        adopted: Boolean?,
        testnet: Boolean?,
    ): List<AdminNetwork> =
        get(
            source = "networks",
            path = "/admin/networks",
            query = mapOf("q" to q, "chainId" to chainId, "adopted" to adopted, "testnet" to testnet),
            responseType = BcmNetworkListResponse::class.java,
        ).data

    override fun assetMappings(
        network: String?,
        symbol: String?,
    ): List<AdminAssetMapping> =
        get(
            source = "assets",
            path = "/admin/asset-mappings",
            query = mapOf("network" to network, "symbol" to symbol),
            responseType = BcmAssetMappingListResponse::class.java,
        ).data

    override fun assetCandidates(
        query: String,
        network: String?,
    ): AdminAssetCandidateSearchResult =
        get(
            source = "assetCandidates",
            path = "/admin/asset-candidates",
            query = mapOf("q" to query, "network" to network),
            responseType = BcmAssetCandidateListResponse::class.java,
        ).data

    override fun adoptNetwork(command: AdoptAdminNetwork): AdminNetwork =
        send(
            source = "networkAdoption",
            request =
                HttpRequest
                    .newBuilder(uri("/admin/networks/${encode(command.code)}", emptyMap()))
                    .timeout(Duration.ofMillis(properties.readTimeoutMillis))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("X-Employee-No", command.employeeNo)
                    .header("X-Branch-Code", command.branchCode)
                    .PUT(
                        HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(mapOf("candidateId" to command.candidateId)),
                            StandardCharsets.UTF_8,
                        ),
                    ).build(),
            expectedStatus = 200,
            responseType = BcmNetworkResponse::class.java,
        ).data

    override fun registerAssetMapping(command: RegisterAdminAssetMapping): AdminAssetMapping =
        send(
            source = "assetRegistration",
            request =
                HttpRequest
                    .newBuilder(uri("/admin/asset-mappings", emptyMap()))
                    .timeout(Duration.ofMillis(properties.readTimeoutMillis))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json")
                    .header("X-Employee-No", command.employeeNo)
                    .header("X-Branch-Code", command.branchCode)
                    .POST(
                        HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(
                                mapOf(
                                    "network" to command.network,
                                    "symbol" to command.symbol,
                                    "contractAddress" to command.contractAddress,
                                ),
                            ),
                            StandardCharsets.UTF_8,
                        ),
                    ).build(),
            expectedStatus = 201,
            responseType = BcmAssetMappingResponse::class.java,
        ).data

    override fun transactionInvestigation(identifier: String): AdminTransactionInvestigation =
        get(
            source = "transaction",
            path = "/admin/transaction-investigations/${encode(identifier)}",
            query = emptyMap(),
            responseType = BcmAdminTransactionInvestigationResponse::class.java,
        ).data

    override fun contracts(): List<AdminContract> =
        get(
            source = "contracts",
            path = "/admin/contracts",
            query = emptyMap(),
            responseType = BcmAdminContractListResponse::class.java,
        ).data

    override fun policies(): List<AdminPolicy> =
        get(
            source = "policies",
            path = "/admin/policies",
            query = emptyMap(),
            responseType = BcmAdminPolicyListResponse::class.java,
        ).data

    override fun bandS(): List<AdminBandS> =
        get(
            source = "bandS",
            path = "/admin/band-s",
            query = emptyMap(),
            responseType = BcmAdminBandSListResponse::class.java,
        ).data

    override fun executionGates(): AdminExecutionGateOverview =
        get(
            source = "executionGates",
            path = "/admin/execution-gates",
            query = emptyMap(),
            responseType = BcmAdminExecutionGateOverviewResponse::class.java,
        ).data

    override fun runtimeReadiness(): AdminRuntimeReadiness =
        get(
            source = "runtimeReadiness",
            path = "/admin/runtime-readiness",
            query = emptyMap(),
            responseType = BcmAdminRuntimeReadinessResponse::class.java,
        ).data

    override fun changeRequest(requestId: String): AdminChangeRequest =
        get(
            source = "changeRequest",
            path = "/admin/change-requests/${encode(requestId)}",
            query = emptyMap(),
            responseType = BcmAdminChangeRequestResponse::class.java,
        ).data

    private fun <T> get(
        source: String,
        path: String,
        query: Map<String, Any?>,
        responseType: Class<T>,
    ): T {
        val request =
            HttpRequest
                .newBuilder(uri(path, query))
                .timeout(Duration.ofMillis(properties.readTimeoutMillis))
                .header("Accept", "application/json")
                .GET()
                .build()
        return send(source, request, 200, responseType)
    }

    private fun <T> send(
        source: String,
        request: HttpRequest,
        expectedStatus: Int,
        responseType: Class<T>,
    ): T {
        val response =
            try {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw SourceFailure(source, 502, "BCM Admin source request interrupted", exception)
            } catch (exception: Exception) {
                throw SourceFailure(source, 502, "BCM Admin source unavailable", exception)
            }
        if (response.statusCode() != expectedStatus) {
            throw SourceFailure(source, response.statusCode(), "BCM Admin source rejected request")
        }
        return try {
            objectMapper.readValue(response.body(), responseType)
        } catch (exception: Exception) {
            throw SourceFailure(source, 502, "BCM Admin source returned an invalid contract", exception)
        }
    }

    private fun uri(
        path: String,
        query: Map<String, Any?>,
    ): URI {
        val encoded =
            query
                .filterValues { it != null }
                .entries
                .joinToString("&") { (key, value) ->
                    "${encode(key)}=${encode(value.toString())}"
                }
        val base = properties.targetBaseUrl.trimEnd('/')
        return URI.create(base + path + if (encoded.isEmpty()) "" else "?$encoded")
    }

    private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)
}
