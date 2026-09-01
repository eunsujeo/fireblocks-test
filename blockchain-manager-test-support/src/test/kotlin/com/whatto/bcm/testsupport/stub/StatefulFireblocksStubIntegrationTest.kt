package com.whatto.bcm.testsupport.stub

import com.whatto.bcm.domain.exception.RelayRejectedException
import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.monitoring.NoOpOperationalMetricsPort
import com.whatto.bcm.domain.sweep.SweepBatchCallItem
import com.whatto.bcm.domain.vendor.VendorContractCallRequest
import com.whatto.bcm.domain.vendor.VendorTransactionDestination
import com.whatto.bcm.domain.vendor.VendorTransactionOrder
import com.whatto.bcm.domain.vendor.VendorTransactionPageRequest
import com.whatto.bcm.domain.vendor.VendorTransactionRequest
import com.whatto.bcm.domain.vendor.VendorTransactionSubmission
import com.whatto.bcm.infra.client.evm.EvmErc20Client
import com.whatto.bcm.infra.client.evm.EvmRpcNetworkProperties
import com.whatto.bcm.infra.client.evm.EvmRpcProperties
import com.whatto.bcm.infra.client.fireblocks.FireblocksClient
import com.whatto.bcm.infra.client.fireblocks.FireblocksJwtSigner
import com.whatto.bcm.infra.client.fireblocks.FireblocksProperties
import com.whatto.bcm.infra.client.fireblocks.PooledFireblocksRestClientFactory
import com.whatto.bcm.testsupport.TestSupportApplication
import com.whatto.bcm.testsupport.chain.LocalChainConfiguration
import com.whatto.bcm.testsupport.chain.LocalChainEnvironment
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.RSAPublicKeySpec
import java.time.Clock
import java.util.Base64

@SpringBootTest(
    classes = [TestSupportApplication::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "management.server.port=0",
        "bcm.test-support.vendor-mode=STUB",
        "bcm.test-support.chain-mode=LOCAL",
        "bcm.test-support.server-address=127.0.0.1",
        "bcm.test-support.management-address=127.0.0.1",
        "bcm.test-support.fireblocks-base-url=http://127.0.0.1:18080",
        "bcm.test-support.fireblocks-api-key=bcm-local-stub",
        "bcm.test-support.webhook-jwks-url=http://127.0.0.1:18080/.well-known/jwks.json",
        "bcm.test-support.evm-chain-id=31337",
        "bcm.test-support.reset-enabled=true",
    ],
)
class StatefulFireblocksStubIntegrationTest {
    @LocalServerPort
    var serverPort: Int = 0

    @BeforeEach
    fun resetEnvironment() = resetStubAndChain()

    @Test
    fun `Vault와 EVM 자산별 주소는 멱등하게 생성되고 같은 Vault 주소를 공유한다`() {
        val client = client()

        val firstVault = client.createVault(VAULT_NAME, VAULT_IDEMPOTENCY_KEY)
        val retriedVault = client.createVault(VAULT_NAME, VAULT_IDEMPOTENCY_KEY)
        val nativeAddress = client.createDepositAddress(firstVault.vaultId, NATIVE_ASSET_ID, NATIVE_WALLET_KEY)
        val tokenAddress = client.createDepositAddress(firstVault.vaultId, TOKEN_ASSET_ID, TOKEN_WALLET_KEY)
        val retriedTokenAddress = client.createDepositAddress(firstVault.vaultId, TOKEN_ASSET_ID, TOKEN_WALLET_KEY)
        val recoveredVaults = client.vaultsByName(VAULT_NAME, null)
        val recoveredAddresses = client.depositAddresses(firstVault.vaultId, TOKEN_ASSET_ID, null)

        assertThat(retriedVault).isEqualTo(firstVault)
        assertThat(firstVault.name).isEqualTo(VAULT_NAME)
        assertThat(firstVault.vaultId).isEqualTo("1")
        assertThat(nativeAddress.address).isEqualTo(tokenAddress.address)
        assertThat(retriedTokenAddress).isEqualTo(tokenAddress)
        assertThat(tokenAddress.tag).isNull()
        assertThat(tokenAddress.address).isEqualTo(chain.manifest.customerAddresses.first())
        assertThat(recoveredVaults.data).containsExactly(firstVault.copy(walletCount = 2))
        assertThat(recoveredAddresses.data).containsExactly(tokenAddress)
        assertThat(recoveredAddresses.next).isNull()
    }

    @Test
    fun `같은 멱등 키를 다른 Vault 요청에 재사용하면 충돌로 거절한다`() {
        val client = client()
        client.createVault(VAULT_NAME, VAULT_IDEMPOTENCY_KEY)

        assertThatThrownBy { client.createVault("CUSTOMER:OTHER", VAULT_IDEMPOTENCY_KEY) }
            .isInstanceOf(VendorApiException::class.java)
            .satisfies({ exception ->
                assertThat((exception as VendorApiException).httpStatus).isEqualTo(409)
            })
    }

    @Test
    fun `Vault 자산 잔액은 실제 Anvil native와 ERC20 상태를 십진 문자열로 반환한다`() {
        val client = client()
        val vault = client.createVault(VAULT_NAME, VAULT_IDEMPOTENCY_KEY)
        val nativeAddress = client.createDepositAddress(vault.vaultId, NATIVE_ASSET_ID, NATIVE_WALLET_KEY)
        val tokenAddress = client.createDepositAddress(vault.vaultId, TOKEN_ASSET_ID, TOKEN_WALLET_KEY)

        val nativeBalance = client.balanceOf(vault.vaultId, NATIVE_ASSET_ID)
        val tokenBalance = client.balanceOf(vault.vaultId, TOKEN_ASSET_ID)
        val expectedNative = decimalAmount(chain.nativeBalance(nativeAddress.address), 18)
        val expectedToken = decimalAmount(chain.tokenBalance(tokenAddress.address), chain.manifest.tokenDecimals)

        assertThat(nativeBalance.total).isEqualTo(expectedNative)
        assertThat(nativeBalance.available).isEqualTo(expectedNative)
        assertThat(tokenBalance.total).isEqualTo(expectedToken)
        assertThat(tokenBalance.available).isEqualTo(expectedToken)
        listOf(nativeBalance, tokenBalance).forEach { balance ->
            assertThat(balance.pending).isEqualTo("0")
            assertThat(balance.frozen).isEqualTo("0")
            assertThat(balance.lockedAmount).isEqualTo("0")
        }
    }

    @Test
    fun `로컬 카탈로그는 native와 테스트 ERC20 자산을 현재 체인 manifest로 제공한다`() {
        val client = client()

        val blockchains = client.blockchains()
        val assets = client.assets(LOCAL_BLOCKCHAIN_ID)
        val blockchain = blockchains.data.single()
        val nativeAsset = assets.data.single { it.id == NATIVE_ASSET_ID }
        val tokenAsset = assets.data.single { it.id == TOKEN_ASSET_ID }

        assertThat(blockchains.next).isNull()
        assertThat(blockchain.id).isEqualTo(LOCAL_BLOCKCHAIN_ID)
        assertThat(blockchain.displayName).isEqualTo("Local EVM")
        assertThat(blockchain.deprecated).isFalse()
        assertThat(blockchain.onchain?.protocol).isEqualTo("EVM")
        assertThat(blockchain.onchain?.chainId).isEqualTo("31337")
        assertThat(blockchain.onchain?.test).isTrue()
        assertThat(blockchain.onchain?.signingAlgo).isEqualTo("MPC_ECDSA_SECP256K1")
        assertThat(assets.next).isNull()
        assertThat(assets.data.map { it.id }).containsExactly(NATIVE_ASSET_ID, TOKEN_ASSET_ID)
        assertThat(nativeAsset.blockchainId).isEqualTo(LOCAL_BLOCKCHAIN_ID)
        assertThat(nativeAsset.displaySymbol).isEqualTo("ETH")
        assertThat(nativeAsset.decimals).isEqualTo(18)
        assertThat(nativeAsset.assetClass).isEqualTo("NATIVE")
        assertThat(nativeAsset.contractAddress).isNull()
        assertThat(tokenAsset.blockchainId).isEqualTo(LOCAL_BLOCKCHAIN_ID)
        assertThat(tokenAsset.displaySymbol).isEqualTo(chain.manifest.tokenSymbol)
        assertThat(tokenAsset.decimals).isEqualTo(chain.manifest.tokenDecimals)
        assertThat(tokenAsset.assetClass).isEqualTo("ERC20")
        assertThat(tokenAsset.contractAddress).isEqualTo(chain.manifest.tokenContractAddress)
        assertThat(client.assets(LOCAL_BLOCKCHAIN_ID, chain.manifest.tokenSymbol).data.map { it.id })
            .containsExactly(TOKEN_ASSET_ID)
    }

    @Test
    fun `fee 견적과 Webhook 활성화 및 실패 재전송은 결정적 상태로 제공된다`() {
        val client = client()

        val fee = client.estimateNetworkFee(NATIVE_ASSET_ID)
        val before = client.webhook(LOCAL_WEBHOOK_ID)
        val activated = client.activateWebhook(LOCAL_WEBHOOK_ID)
        val resend = client.resendFailedWebhookNotifications(LOCAL_WEBHOOK_ID)

        assertThat(fee.low.gasPrice).isEqualByComparingTo("1")
        assertThat(fee.medium.gasPrice).isEqualByComparingTo("2")
        assertThat(fee.high.gasPrice).isEqualByComparingTo("3")
        assertThat(before.status.name).isEqualTo("SUSPENDED")
        assertThat(activated.status.name).isEqualTo("ENABLED")
        assertThat(activated.events).containsExactlyInAnyOrder(
            "transaction.created",
            "transaction.status.updated",
            "transaction.approval_status.updated",
            "transaction.network_records.processing_completed",
        )
        assertThat(resend.scheduledNotificationCount).isZero()
    }

    @Test
    fun `시스템 테스트 제어면은 공개 API로 만든 주소의 vault를 찾고 Webhook을 활성화한다`() {
        val client = client()
        val vault = client.createVault(VAULT_NAME, VAULT_IDEMPOTENCY_KEY)
        val wallet = client.createDepositAddress(vault.vaultId, TOKEN_ASSET_ID, TOKEN_WALLET_KEY)

        val lookup =
            ObjectMapper().readTree(
                get(
                    "http://127.0.0.1:$serverPort/__stub/vaults/by-address/$TOKEN_ASSET_ID" +
                        "?address=${wallet.address}",
                ),
            )
        val activated =
            ObjectMapper().readTree(
                postNoBody("http://127.0.0.1:$serverPort/__stub/webhooks/$LOCAL_WEBHOOK_ID/activate"),
            )
        val resent =
            ObjectMapper().readTree(
                postNoBody("http://127.0.0.1:$serverPort/__stub/webhooks/$LOCAL_WEBHOOK_ID/resend-failed"),
            )

        assertThat(lookup.required("vaultId").asString()).isEqualTo(vault.vaultId)
        assertThat(activated.required("id").asString()).isEqualTo(LOCAL_WEBHOOK_ID)
        assertThat(activated.required("status").asString()).isEqualTo("ENABLED")
        assertThat(resent.required("total").asInt()).isZero()
        assertThat(client.webhook(LOCAL_WEBHOOK_ID).status.name).isEqualTo("ENABLED")
    }

    @Test
    fun `Stub JWKS 공개키는 원문 byte의 RS512 detached JWS를 검증한다`() {
        val payload = "{\"id\":\"event-local-1\",\"eventType\":\"transaction.created\"}".toByteArray()
        val signatureDocument =
            postBytes(
                "http://127.0.0.1:$serverPort/__stub/webhooks/signatures",
                payload,
            )
        val signature = ObjectMapper().readTree(signatureDocument).required("signature").asString()
        val jwks = ObjectMapper().readTree(get("http://127.0.0.1:$serverPort/.well-known/jwks.json"))
        val jwk = jwks.required("keys").single()
        val publicKey =
            KeyFactory.getInstance("RSA").generatePublic(
                RSAPublicKeySpec(
                    BigInteger(1, Base64.getUrlDecoder().decode(jwk.required("n").asString())),
                    BigInteger(1, Base64.getUrlDecoder().decode(jwk.required("e").asString())),
                ),
            )
        val parts = signature.split('.')
        val protectedHeader = ObjectMapper().readTree(Base64.getUrlDecoder().decode(parts[0]))
        val encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)

        assertThat(parts).hasSize(3)
        assertThat(parts[1]).isEmpty()
        assertThat(jwk.required("kid").asString()).startsWith("bcm-local-webhook-")
        assertThat(protectedHeader.required("kid").asString()).isEqualTo(jwk.required("kid").asString())
        assertThat(
            Signature.getInstance("SHA512withRSA").run {
                initVerify(publicKey)
                update("${parts[0]}.$encodedPayload".toByteArray(StandardCharsets.US_ASCII))
                verify(Base64.getUrlDecoder().decode(parts[2]))
            },
        ).isTrue()
    }

    @Test
    fun `TRANSFER는 raw transaction 한 건으로 Anvil ERC20 잔액을 이동하고 externalTxId로 회수한다`() {
        val client = client()
        val source = client.createVault(VAULT_NAME, VAULT_IDEMPOTENCY_KEY)
        val destination = client.createVault(DESTINATION_VAULT_NAME, DESTINATION_VAULT_KEY)
        client.createDepositAddress(source.vaultId, TOKEN_ASSET_ID, TOKEN_WALLET_KEY)
        client.createDepositAddress(destination.vaultId, TOKEN_ASSET_ID, DESTINATION_TOKEN_WALLET_KEY)
        val sourceAddress = client.createDepositAddress(source.vaultId, NATIVE_ASSET_ID, NATIVE_WALLET_KEY).address
        val destinationAddress =
            client.createDepositAddress(destination.vaultId, NATIVE_ASSET_ID, DESTINATION_NATIVE_WALLET_KEY).address
        val sourceBefore = chain.tokenBalance(sourceAddress)
        val destinationBefore = chain.tokenBalance(destinationAddress)
        val nonceBefore = chain.transactionCount(sourceAddress)
        val request =
            VendorTransactionRequest(
                externalTransactionId = TRANSFER_EXTERNAL_ID,
                vendorAssetId = TOKEN_ASSET_ID,
                sourceVaultId = source.vaultId,
                destination = VendorTransactionDestination.Account(destination.vaultId),
                amount = "1.5",
                note = "local contract transfer",
                travelRuleMessage = null,
                useGasless = false,
            )

        val first = client.submitTransaction(request) as VendorTransactionSubmission.Accepted
        val retried = client.submitTransaction(request) as VendorTransactionSubmission.Accepted
        val submitted = client.transaction(first.transactionId)
        advanceTransaction(first.transactionId)
        val confirming = client.transaction(first.transactionId)
        advanceTransaction(first.transactionId)
        val recovered = client.transactionByExternalTransactionId(TRANSFER_EXTERNAL_ID)
        val byId = client.transaction(first.transactionId)
        val listed =
            client.transactions(
                VendorTransactionPageRequest(
                    sourceVaultId = source.vaultId,
                    afterEpochMillis = 0,
                    vendorStatus = "COMPLETED",
                    order = VendorTransactionOrder.ASC,
                    limit = 500,
                ),
            )

        assertThat(retried).isEqualTo(first)
        assertThat(submitted?.rawStatus).isEqualTo("SUBMITTED")
        assertThat(submitted?.confirmationCount).isZero()
        assertThat(confirming?.rawStatus).isEqualTo("CONFIRMING")
        assertThat(confirming?.confirmationCount).isEqualTo(1)
        assertThat(chain.transactionCount(sourceAddress)).isEqualTo(nonceBefore + BigInteger.ONE)
        assertThat(chain.tokenBalance(sourceAddress)).isEqualTo(sourceBefore - TOKEN_TRANSFER_RAW_AMOUNT)
        assertThat(chain.tokenBalance(destinationAddress)).isEqualTo(destinationBefore + TOKEN_TRANSFER_RAW_AMOUNT)
        assertThat(recovered).isEqualTo(byId)
        assertThat(recovered?.rawStatus).isEqualTo("COMPLETED")
        assertThat(recovered?.transactionHash).startsWith("0x").hasSize(66)
        assertThat(recovered?.source?.id).isEqualTo(source.vaultId)
        assertThat(recovered?.destination?.id).isEqualTo(destination.vaultId)
        assertThat(recovered?.sourceAddress).isEqualTo(sourceAddress)
        assertThat(recovered?.destinationAddress).isEqualTo(destinationAddress)
        assertThat(recovered?.amount).isEqualTo("1.5")
        assertThat(recovered?.networkRecords).singleElement().satisfies({ record ->
            assertThat(record.type).isEqualTo("TRANSFER")
            assertThat(record.source.id).isEqualTo(source.vaultId)
            assertThat(record.destination.id).isEqualTo(destination.vaultId)
            assertThat(record.destinationAddress).isEqualTo(destinationAddress)
            assertThat(record.transactionHash).isEqualTo(recovered?.transactionHash)
            assertThat(record.vendorAssetId).isEqualTo(TOKEN_ASSET_ID)
            assertThat(record.netAmount).isEqualTo("1.5")
            assertThat(record.dropped).isFalse()
        })
        assertThat(listed.data.map { it.externalTransactionId }).contains(TRANSFER_EXTERNAL_ID)
        assertThat(listed.next).isNull()
    }

    @Test
    fun `EXTERNAL_WALLET 출금은 로컬 EVM 주소 wallet id로 실제 자산을 이동한다`() {
        val client = client()
        val source = client.createVault(VAULT_NAME, VAULT_IDEMPOTENCY_KEY)
        val sourceAddress = client.createDepositAddress(source.vaultId, TOKEN_ASSET_ID, TOKEN_WALLET_KEY).address
        client.createDepositAddress(source.vaultId, NATIVE_ASSET_ID, NATIVE_WALLET_KEY)
        val externalWalletAddress = chain.manifest.customerAddresses.last()
        val sourceBefore = chain.tokenBalance(sourceAddress)
        val destinationBefore = chain.tokenBalance(externalWalletAddress)

        val submission =
            client.submitTransaction(
                VendorTransactionRequest(
                    externalTransactionId = EXTERNAL_WALLET_TRANSFER_EXTERNAL_ID,
                    vendorAssetId = TOKEN_ASSET_ID,
                    sourceVaultId = source.vaultId,
                    destination = VendorTransactionDestination.Whitelisted(externalWalletAddress),
                    amount = "1.5",
                    note = null,
                    travelRuleMessage = null,
                    useGasless = false,
                ),
            ) as VendorTransactionSubmission.Accepted
        completeTransaction(submission.transactionId)
        val transaction = checkNotNull(client.transaction(submission.transactionId))

        assertThat(chain.tokenBalance(sourceAddress)).isEqualTo(sourceBefore - TOKEN_TRANSFER_RAW_AMOUNT)
        assertThat(chain.tokenBalance(externalWalletAddress)).isEqualTo(destinationBefore + TOKEN_TRANSFER_RAW_AMOUNT)
        assertThat(transaction.destination.type).isEqualTo("EXTERNAL_WALLET")
        assertThat(transaction.destination.id).isEqualTo(externalWalletAddress)
        assertThat(transaction.destinationAddress).isEqualTo(externalWalletAddress)
        assertThat(transaction.rawStatus).isEqualTo("COMPLETED")
    }

    @Test
    fun `외부 입금 제어는 실제 ERC20을 Vault로 보내고 Fireblocks 거래로 관찰한다`() {
        val client = client()
        val destination = client.createVault(VAULT_NAME, VAULT_IDEMPOTENCY_KEY)
        val destinationAddress = client.createDepositAddress(destination.vaultId, TOKEN_ASSET_ID, TOKEN_WALLET_KEY).address
        val balanceBefore = chain.tokenBalance(destinationAddress)
        val response =
            postJson(
                "http://127.0.0.1:$serverPort/__stub/deposits",
                """
                {
                  "externalTxId": "$DEPOSIT_EXTERNAL_ID",
                  "assetId": "$TOKEN_ASSET_ID",
                  "destinationVaultId": "${destination.vaultId}",
                  "amount": "$DEPOSIT_AMOUNT"
                }
                """.trimIndent(),
            )
        val transactionId = ObjectMapper().readTree(response).required("id").asString()

        completeTransaction(transactionId)

        val transaction = client.transaction(transactionId)
        assertThat(chain.tokenBalance(destinationAddress)).isEqualTo(balanceBefore + DEPOSIT_RAW_AMOUNT)
        assertThat(transaction?.externalTransactionId).isEqualTo(DEPOSIT_EXTERNAL_ID)
        assertThat(transaction?.rawStatus).isEqualTo("COMPLETED")
        assertThat(transaction?.source?.type).isEqualTo("UNKNOWN")
        assertThat(transaction?.source?.id).isNull()
        assertThat(transaction?.sourceAddress).isEqualTo(chain.manifest.deployerAddress)
        assertThat(transaction?.destination?.type).isEqualTo("VAULT_ACCOUNT")
        assertThat(transaction?.destination?.id).isEqualTo(destination.vaultId)
        assertThat(transaction?.destinationAddress).isEqualTo(destinationAddress)
        assertThat(transaction?.amount).isEqualTo(DEPOSIT_AMOUNT)
        assertThat(transaction?.networkRecords).singleElement().satisfies({ record ->
            assertThat(record.source.type).isEqualTo("UNKNOWN")
            assertThat(record.destination.id).isEqualTo(destination.vaultId)
            assertThat(record.destinationAddress).isEqualTo(destinationAddress)
            assertThat(record.transactionHash).isEqualTo(transaction?.transactionHash)
            assertThat(record.netAmount).isEqualTo(DEPOSIT_AMOUNT)
            assertThat(record.dropped).isFalse()
        })
    }

    @Test
    fun `batch sweep 부분 성공은 실제 SweepLeg와 성공분 network records를 제공한다`() {
        val client = client()
        val firstCustomer = client.createVault(VAULT_NAME, VAULT_IDEMPOTENCY_KEY)
        val secondCustomer = client.createVault(DESTINATION_VAULT_NAME, DESTINATION_VAULT_KEY)
        val operator = client.createVault(OPERATOR_VAULT_NAME, OPERATOR_VAULT_KEY)
        val firstAddress = client.createDepositAddress(firstCustomer.vaultId, TOKEN_ASSET_ID, TOKEN_WALLET_KEY).address
        val secondAddress =
            client.createDepositAddress(secondCustomer.vaultId, TOKEN_ASSET_ID, DESTINATION_TOKEN_WALLET_KEY).address
        client.createDepositAddress(operator.vaultId, NATIVE_ASSET_ID, OPERATOR_NATIVE_WALLET_KEY)
        val vaultIdsByAddress = mapOf(firstAddress to firstCustomer.vaultId, secondAddress to secondCustomer.vaultId)
        val owners = vaultIdsByAddress.keys.sorted()
        chain.approve(owners.first(), BATCH_SWEEP_RAW_AMOUNT)
        chain.approve(owners.last(), INSUFFICIENT_ALLOWANCE_RAW_AMOUNT)
        val omnibusBefore = chain.tokenBalance(chain.manifest.omnibusAddress)
        val evm = evmClient()
        val callData =
            evm.batchSweepCallData(
                LOCAL_NETWORK,
                BATCH_SWEEP_EXECUTION_ID,
                chain.manifest.tokenContractAddress,
                owners.map { SweepBatchCallItem(it, BATCH_SWEEP_AMOUNT) },
            )
        val submission =
            client.submitContractCall(
                VendorContractCallRequest(
                    externalTransactionId = BATCH_SWEEP_EXTERNAL_ID,
                    network = LOCAL_NETWORK,
                    sourceVaultId = operator.vaultId,
                    contractAddress = chain.manifest.sweepContractAddress,
                    callData = callData,
                    useGasless = false,
                ),
            ) as VendorTransactionSubmission.Accepted

        completeTransaction(submission.transactionId)

        val transaction = checkNotNull(client.transaction(submission.transactionId))
        val receipt =
            checkNotNull(
                evm.receipt(
                    LOCAL_NETWORK,
                    checkNotNull(transaction.transactionHash),
                    chain.manifest.sweepContractAddress,
                    chain.manifest.tokenDecimals,
                ),
            )
        val successfulOwner = receipt.legs.single { it.successful }.ownerAddress
        val failedOwner = receipt.legs.single { !it.successful }.ownerAddress
        assertThat(receipt.legs).hasSize(2)
        assertThat(receipt.legs.single { it.successful }.actualAmount).isEqualTo(BATCH_SWEEP_AMOUNT)
        assertThat(receipt.legs.single { !it.successful }.actualAmount).isEqualTo("0")
        assertThat(chain.tokenBalance(chain.manifest.omnibusAddress))
            .isEqualTo(omnibusBefore + BATCH_SWEEP_RAW_AMOUNT)
        assertThat(transaction.networkRecords).singleElement().satisfies({ record ->
            assertThat(record.type).isEqualTo("TOKEN_TRANSFER")
            assertThat(record.source.type).isEqualTo("VAULT_ACCOUNT")
            assertThat(record.source.id).isEqualTo(vaultIdsByAddress[successfulOwner])
            assertThat(record.destination.type).isEqualTo("ONE_TIME_ADDRESS")
            assertThat(record.destinationAddress).isEqualTo(chain.manifest.omnibusAddress)
            assertThat(record.transactionHash).isEqualTo(transaction.transactionHash)
            assertThat(record.vendorAssetId).isEqualTo(TOKEN_ASSET_ID)
            assertThat(record.netAmount).isEqualTo(BATCH_SWEEP_AMOUNT)
            assertThat(record.dropped).isFalse()
        })
        assertThat(transaction.networkRecords.map { it.source.id }).doesNotContain(vaultIdsByAddress[failedOwner])
    }

    @Test
    fun `CONTRACT_CALL은 calldata를 raw transaction으로 실행하고 같은 externalTxId를 중복 제출하지 않는다`() {
        val client = client()
        val source = client.createVault(VAULT_NAME, VAULT_IDEMPOTENCY_KEY)
        val sourceAddress = client.createDepositAddress(source.vaultId, NATIVE_ASSET_ID, NATIVE_WALLET_KEY).address
        val nonceBefore = chain.transactionCount(sourceAddress)
        val callData = approveCallData(chain.manifest.sweepContractAddress, CONTRACT_CALL_RAW_AMOUNT)
        val request =
            VendorContractCallRequest(
                externalTransactionId = CONTRACT_CALL_EXTERNAL_ID,
                network = LOCAL_NETWORK,
                sourceVaultId = source.vaultId,
                contractAddress = chain.manifest.tokenContractAddress,
                callData = callData,
                useGasless = false,
            )

        val first = client.submitContractCall(request) as VendorTransactionSubmission.Accepted
        val retried = client.submitContractCall(request) as VendorTransactionSubmission.Accepted
        completeTransaction(first.transactionId)
        val recovered = client.contractCallByExternalTransactionId(CONTRACT_CALL_EXTERNAL_ID)

        assertThat(retried).isEqualTo(first)
        assertThat(chain.transactionCount(sourceAddress)).isEqualTo(nonceBefore + BigInteger.ONE)
        assertThat(chain.tokenAllowance(sourceAddress)).isEqualTo(CONTRACT_CALL_RAW_AMOUNT)
        assertThat(recovered?.transactionId).isEqualTo(first.transactionId)
        assertThat(recovered?.sourceVaultId).isEqualTo(source.vaultId)
        assertThat(recovered?.contractAddress).isEqualTo(chain.manifest.tokenContractAddress)
        assertThat(recovered?.callData).isEqualTo(callData)
    }

    @Test
    fun `gasless CONTRACT_CALL은 source native 잔액 없이 fee payer로 approve를 실행한다`() {
        val client = client()
        val source = client.createVault(VAULT_NAME, VAULT_IDEMPOTENCY_KEY)
        val sourceAddress = client.createDepositAddress(source.vaultId, NATIVE_ASSET_ID, NATIVE_WALLET_KEY).address
        chain.setNativeBalance(sourceAddress, BigInteger.ZERO)
        val feePayerBalanceBefore = chain.nativeBalance(chain.manifest.gaslessFeePayerAddress)
        val callData = approveCallData(chain.manifest.sweepContractAddress, CONTRACT_CALL_RAW_AMOUNT)

        val submission =
            client.submitContractCall(
                VendorContractCallRequest(
                    externalTransactionId = GASLESS_CONTRACT_CALL_EXTERNAL_ID,
                    network = LOCAL_NETWORK,
                    sourceVaultId = source.vaultId,
                    contractAddress = chain.manifest.tokenContractAddress,
                    callData = callData,
                    useGasless = true,
                ),
            ) as VendorTransactionSubmission.Accepted
        completeTransaction(submission.transactionId)

        assertThat(chain.tokenAllowance(sourceAddress)).isEqualTo(CONTRACT_CALL_RAW_AMOUNT)
        assertThat(chain.nativeBalance(sourceAddress)).isZero()
        assertThat(chain.nativeBalance(chain.manifest.gaslessFeePayerAddress)).isLessThan(feePayerBalanceBefore)
        assertThat(chain.delegatedCode(sourceAddress))
            .isEqualTo("0xef0100${chain.manifest.gaslessDelegationContractAddress.removePrefix("0x")}")
    }

    @Test
    fun `gasless fee payer 잔액 부족은 Stub 거래와 위임을 남기지 않는다`() {
        val client = client()
        val source = client.createVault(VAULT_NAME, VAULT_IDEMPOTENCY_KEY)
        val sourceAddress = client.createDepositAddress(source.vaultId, NATIVE_ASSET_ID, NATIVE_WALLET_KEY).address
        chain.setNativeBalance(sourceAddress, BigInteger.ZERO)
        chain.setNativeBalance(chain.manifest.gaslessFeePayerAddress, BigInteger.ZERO)
        val callData = approveCallData(chain.manifest.sweepContractAddress, CONTRACT_CALL_RAW_AMOUNT)

        assertThatThrownBy {
            client.submitContractCall(
                VendorContractCallRequest(
                    externalTransactionId = GASLESS_FEE_PAYER_FAILURE_EXTERNAL_ID,
                    network = LOCAL_NETWORK,
                    sourceVaultId = source.vaultId,
                    contractAddress = chain.manifest.tokenContractAddress,
                    callData = callData,
                    useGasless = true,
                ),
            )
        }.isInstanceOf(VendorApiException::class.java)
            .satisfies({ exception -> assertThat((exception as VendorApiException).httpStatus).isEqualTo(500) })

        assertThat(client.contractCallByExternalTransactionId(GASLESS_FEE_PAYER_FAILURE_EXTERNAL_ID)).isNull()
        assertThat(chain.delegatedCode(sourceAddress)).isEqualTo("0x")
        assertThat(chain.tokenAllowance(sourceAddress)).isZero()
    }

    @Test
    fun `같은 externalTxId에 다른 자금 이동을 제출하면 충돌하고 두 번째 raw transaction은 만들지 않는다`() {
        val client = client()
        val source = client.createVault(VAULT_NAME, VAULT_IDEMPOTENCY_KEY)
        val destination = client.createVault(DESTINATION_VAULT_NAME, DESTINATION_VAULT_KEY)
        val sourceAddress = client.createDepositAddress(source.vaultId, TOKEN_ASSET_ID, TOKEN_WALLET_KEY).address
        client.createDepositAddress(destination.vaultId, TOKEN_ASSET_ID, DESTINATION_TOKEN_WALLET_KEY)
        val nonceBefore = chain.transactionCount(sourceAddress)
        val initial =
            VendorTransactionRequest(
                externalTransactionId = CONFLICT_EXTERNAL_ID,
                vendorAssetId = TOKEN_ASSET_ID,
                sourceVaultId = source.vaultId,
                destination = VendorTransactionDestination.Account(destination.vaultId),
                amount = "0.5",
                note = null,
                travelRuleMessage = null,
                useGasless = false,
            )

        client.submitTransaction(initial)

        assertThatThrownBy { client.submitTransaction(initial.copy(amount = "0.75")) }
            .isInstanceOf(RelayRejectedException::class.java)
            .hasCauseInstanceOf(VendorApiException::class.java)
            .satisfies({ exception ->
                assertThat((exception.cause as VendorApiException).httpStatus).isEqualTo(409)
            })
        assertThat(chain.transactionCount(sourceAddress)).isEqualTo(nonceBefore + BigInteger.ONE)
    }

    @Test
    fun `제출 저장 뒤 HTTP 400 응답 유실은 externalTxId 조회로 같은 거래를 회수한다`() {
        val client = client()
        val source = client.createVault(VAULT_NAME, VAULT_IDEMPOTENCY_KEY)
        val destination = client.createVault(DESTINATION_VAULT_NAME, DESTINATION_VAULT_KEY)
        val sourceAddress = client.createDepositAddress(source.vaultId, TOKEN_ASSET_ID, TOKEN_WALLET_KEY).address
        client.createDepositAddress(destination.vaultId, TOKEN_ASSET_ID, DESTINATION_TOKEN_WALLET_KEY)
        val nonceBefore = chain.transactionCount(sourceAddress)
        val request =
            VendorTransactionRequest(
                externalTransactionId = LOST_RESPONSE_EXTERNAL_ID,
                vendorAssetId = TOKEN_ASSET_ID,
                sourceVaultId = source.vaultId,
                destination = VendorTransactionDestination.Account(destination.vaultId),
                amount = "0.75",
                note = null,
                travelRuleMessage = null,
                useGasless = false,
            )
        postJson(
            "http://127.0.0.1:$serverPort/__stub/faults/transactions/next-response",
            """{"status":400,"afterCommit":true}""",
        )

        val lost = client.submitTransaction(request)
        val recovered = client.transactionByExternalTransactionId(LOST_RESPONSE_EXTERNAL_ID)
        val retried = client.submitTransaction(request)

        assertThat(lost).isInstanceOf(VendorTransactionSubmission.BadRequestNeedsLookup::class.java)
        assertThat(recovered?.externalTransactionId).isEqualTo(LOST_RESPONSE_EXTERNAL_ID)
        assertThat(retried).isEqualTo(VendorTransactionSubmission.Accepted(recovered?.transactionId!!))
        assertThat(chain.transactionCount(sourceAddress)).isEqualTo(nonceBefore + BigInteger.ONE)
    }

    @Test
    fun `native TRANSFER는 직접 주소로 실제 자산을 보내고 Fireblocks peer 모양을 보존한다`() {
        val client = client()
        val source = client.createVault(VAULT_NAME, VAULT_IDEMPOTENCY_KEY)
        val sourceAddress = client.createDepositAddress(source.vaultId, NATIVE_ASSET_ID, NATIVE_WALLET_KEY).address
        val balanceBefore = BigDecimal(client.balanceOf(source.vaultId, NATIVE_ASSET_ID).total)
        val nonceBefore = chain.transactionCount(sourceAddress)
        val request =
            VendorTransactionRequest(
                externalTransactionId = NATIVE_TRANSFER_EXTERNAL_ID,
                vendorAssetId = NATIVE_ASSET_ID,
                sourceVaultId = source.vaultId,
                destination = VendorTransactionDestination.Address(chain.manifest.omnibusAddress),
                amount = NATIVE_TRANSFER_AMOUNT,
                note = null,
                travelRuleMessage = null,
                useGasless = false,
            )

        val submission = client.submitTransaction(request) as VendorTransactionSubmission.Accepted
        completeTransaction(submission.transactionId)
        val transaction = client.transaction(submission.transactionId)
        val balanceAfter = BigDecimal(client.balanceOf(source.vaultId, NATIVE_ASSET_ID).total)

        assertThat(chain.transactionCount(sourceAddress)).isEqualTo(nonceBefore + BigInteger.ONE)
        assertThat(balanceBefore - balanceAfter).isGreaterThan(BigDecimal(NATIVE_TRANSFER_AMOUNT))
        assertThat(transaction?.rawStatus).isEqualTo("COMPLETED")
        assertThat(transaction?.destination?.type).isEqualTo("ONE_TIME_ADDRESS")
        assertThat(transaction?.destination?.id).isNull()
        assertThat(transaction?.destinationAddress).isEqualTo(chain.manifest.omnibusAddress)
        assertThat(transaction?.amount).isEqualTo(NATIVE_TRANSFER_AMOUNT)
    }

    @Test
    fun `거래 목록 next cursor는 정렬과 Vault 범위를 유지하며 다음 페이지를 반환한다`() {
        val client = client()
        val destination = client.createVault(VAULT_NAME, VAULT_IDEMPOTENCY_KEY)
        val source = client.createVault(DESTINATION_VAULT_NAME, DESTINATION_VAULT_KEY)
        client.createDepositAddress(destination.vaultId, TOKEN_ASSET_ID, TOKEN_WALLET_KEY)
        client.createDepositAddress(source.vaultId, TOKEN_ASSET_ID, DESTINATION_TOKEN_WALLET_KEY)
        val externalIds = (1..3).map { "local-page-transfer-$it" }
        externalIds.forEachIndexed { index, externalId ->
            client.submitTransaction(
                VendorTransactionRequest(
                    externalTransactionId = externalId,
                    vendorAssetId = TOKEN_ASSET_ID,
                    sourceVaultId = source.vaultId,
                    destination = VendorTransactionDestination.Account(destination.vaultId),
                    amount = "0.${index + 1}",
                    note = null,
                    travelRuleMessage = null,
                    useGasless = false,
                ),
            )
        }
        val first =
            client.transactions(
                VendorTransactionPageRequest(
                    sourceVaultId = source.vaultId,
                    afterEpochMillis = 0,
                    order = VendorTransactionOrder.ASC,
                    limit = 2,
                ),
            )
        val second =
            client.transactions(
                VendorTransactionPageRequest(
                    sourceVaultId = source.vaultId,
                    afterEpochMillis = 0,
                    order = VendorTransactionOrder.ASC,
                    limit = 2,
                    cursor = first.next,
                ),
            )

        assertThat(first.data.map { it.externalTransactionId }).containsExactlyElementsOf(externalIds.take(2))
        assertThat(first.next).isNotBlank()
        assertThat(second.data.map { it.externalTransactionId }).containsExactly(externalIds.last())
        assertThat(second.next).isNull()
    }

    @Test
    fun `reset은 Stub 상태와 Anvil snapshot만 같은 기준점으로 반복 복원한다`() {
        val client = client()
        val source = client.createVault(VAULT_NAME, VAULT_IDEMPOTENCY_KEY)
        val destination = client.createVault(DESTINATION_VAULT_NAME, DESTINATION_VAULT_KEY)
        val sourceWallet = client.createDepositAddress(source.vaultId, TOKEN_ASSET_ID, TOKEN_WALLET_KEY)
        client.createDepositAddress(destination.vaultId, TOKEN_ASSET_ID, DESTINATION_TOKEN_WALLET_KEY)
        client.activateWebhook(LOCAL_WEBHOOK_ID)
        val before = BigDecimal(client.balanceOf(source.vaultId, TOKEN_ASSET_ID).total)
        val first =
            client.submitTransaction(
                VendorTransactionRequest(
                    externalTransactionId = RESET_EXTERNAL_ID,
                    vendorAssetId = TOKEN_ASSET_ID,
                    sourceVaultId = source.vaultId,
                    destination = VendorTransactionDestination.Account(destination.vaultId),
                    amount = "1",
                    note = "reset contract",
                    travelRuleMessage = null,
                    useGasless = false,
                ),
            ) as VendorTransactionSubmission.Accepted
        assertThat(BigDecimal(client.balanceOf(source.vaultId, TOKEN_ASSET_ID).total)).isLessThan(before)
        postJson(
            "http://127.0.0.1:$serverPort/__stub/faults/transactions/next-response",
            """{"status":500,"afterCommit":false}""",
        )

        resetStubAndChain()

        assertThat(client.transactionByExternalTransactionId(RESET_EXTERNAL_ID)).isNull()
        assertThat(client.webhook(LOCAL_WEBHOOK_ID).status.name).isEqualTo("SUSPENDED")
        val restoredSource = client.createVault(VAULT_NAME, VAULT_IDEMPOTENCY_KEY)
        val restoredDestination = client.createVault(DESTINATION_VAULT_NAME, DESTINATION_VAULT_KEY)
        val restoredWallet = client.createDepositAddress(restoredSource.vaultId, TOKEN_ASSET_ID, TOKEN_WALLET_KEY)
        client.createDepositAddress(restoredDestination.vaultId, TOKEN_ASSET_ID, DESTINATION_TOKEN_WALLET_KEY)
        val restoredBalance = client.balanceOf(restoredSource.vaultId, TOKEN_ASSET_ID).total
        val recoveredSequence =
            client.submitTransaction(
                VendorTransactionRequest(
                    externalTransactionId = RESET_EXTERNAL_ID,
                    vendorAssetId = TOKEN_ASSET_ID,
                    sourceVaultId = restoredSource.vaultId,
                    destination = VendorTransactionDestination.Account(restoredDestination.vaultId),
                    amount = "1",
                    note = "reset contract",
                    travelRuleMessage = null,
                    useGasless = false,
                ),
            ) as VendorTransactionSubmission.Accepted

        assertThat(first.transactionId).isNotBlank()
        assertThat(recoveredSequence.transactionId).isEqualTo(first.transactionId)
        assertThat(restoredSource.vaultId).isEqualTo("1")
        assertThat(restoredWallet.address).isEqualTo(sourceWallet.address)

        resetStubAndChain()

        val repeatedSource = client.createVault(VAULT_NAME, VAULT_IDEMPOTENCY_KEY)
        val repeatedDestination = client.createVault(DESTINATION_VAULT_NAME, DESTINATION_VAULT_KEY)
        client.createDepositAddress(repeatedSource.vaultId, TOKEN_ASSET_ID, TOKEN_WALLET_KEY)
        client.createDepositAddress(repeatedDestination.vaultId, TOKEN_ASSET_ID, DESTINATION_TOKEN_WALLET_KEY)
        assertThat(client.balanceOf(repeatedSource.vaultId, TOKEN_ASSET_ID).total).isEqualTo(restoredBalance)
        assertThat(client.transactionByExternalTransactionId(RESET_EXTERNAL_ID)).isNull()
        val distinctAfterReset =
            client.submitTransaction(
                VendorTransactionRequest(
                    externalTransactionId = "$RESET_EXTERNAL_ID-distinct",
                    vendorAssetId = TOKEN_ASSET_ID,
                    sourceVaultId = repeatedSource.vaultId,
                    destination = VendorTransactionDestination.Account(repeatedDestination.vaultId),
                    amount = "1",
                    note = "reset collision contract",
                    travelRuleMessage = null,
                    useGasless = false,
                ),
            ) as VendorTransactionSubmission.Accepted
        assertThat(distinctAfterReset.transactionId).isNotEqualTo(first.transactionId)
    }

    private fun client(): FireblocksClient {
        val privateKeyPem = testPrivateKeyPem()
        val properties =
            FireblocksProperties(
                baseUrl = "http://127.0.0.1:$serverPort",
                apiKey = "bcm-local-stub",
                privateKeyPem = privateKeyPem,
                contractCallGasAssetIds = mapOf(LOCAL_NETWORK to NATIVE_ASSET_ID),
            )
        return FireblocksClient(
            restClientBuilder = RestClient.builder(),
            properties = properties,
            signer = FireblocksJwtSigner(properties.apiKey, privateKeyPem, Clock.systemUTC()),
            metrics = NoOpOperationalMetricsPort,
            restClientFactory = PooledFireblocksRestClientFactory(),
        )
    }

    private fun evmClient(): EvmErc20Client =
        EvmErc20Client(
            RestClient.builder(),
            EvmRpcProperties(mapOf(LOCAL_NETWORK to EvmRpcNetworkProperties(chain.rpcUrl))),
        )

    private fun testPrivateKeyPem(): String {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val body = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(keyPair.private.encoded)
        return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----"
    }

    private fun get(url: String): String =
        HttpClient
            .newHttpClient()
            .send(
                HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body()

    private fun postBytes(
        url: String,
        body: ByteArray,
    ): String =
        HttpClient
            .newHttpClient()
            .send(
                HttpRequest
                    .newBuilder(URI.create(url))
                    .header("Content-Type", MediaType.APPLICATION_OCTET_STREAM_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            ).body()

    private fun postNoBody(url: String): String =
        HttpClient
            .newHttpClient()
            .send(
                HttpRequest
                    .newBuilder(URI.create(url))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            ).also { response -> assertThat(response.statusCode()).isEqualTo(200) }
            .body()

    private fun postJson(
        url: String,
        body: String,
    ): String =
        HttpClient
            .newHttpClient()
            .send(
                HttpRequest
                    .newBuilder(URI.create(url))
                    .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            ).also { response -> assertThat(response.statusCode()).isEqualTo(200) }
            .body()

    private fun advanceTransaction(transactionId: String) {
        HttpClient
            .newHttpClient()
            .send(
                HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:$serverPort/__stub/transactions/$transactionId/advance"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            ).also { response -> check(response.statusCode() == 200) }
    }

    private fun completeTransaction(transactionId: String) {
        advanceTransaction(transactionId)
        advanceTransaction(transactionId)
    }

    private fun resetStubAndChain() {
        HttpClient
            .newHttpClient()
            .send(
                HttpRequest
                    .newBuilder(URI.create("http://127.0.0.1:$serverPort/__stub/reset"))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build(),
                HttpResponse.BodyHandlers.discarding(),
            ).also { response -> assertThat(response.statusCode()).isEqualTo(200) }
    }

    private fun tools.jackson.databind.JsonNode.required(field: String) =
        checkNotNull(get(field)) { "test response field is missing: $field" }

    private fun approveCallData(
        spenderAddress: String,
        amount: BigInteger,
    ): String =
        "0x095ea7b3" +
            spenderAddress.removePrefix("0x").padStart(64, '0') +
            amount.toString(16).padStart(64, '0')

    private fun decimalAmount(
        raw: BigInteger,
        decimals: Int,
    ): String = BigDecimal(raw, decimals).stripTrailingZeros().toPlainString()

    companion object {
        private const val LOCAL_BLOCKCHAIN_ID = "local-evm"
        private const val NATIVE_ASSET_ID = "ETH_LOCAL"
        private const val TOKEN_ASSET_ID = "TUSD_LOCAL"
        private const val LOCAL_WEBHOOK_ID = "local-webhook"
        private const val VAULT_NAME = "CUSTOMER:LOCAL-001"
        private const val VAULT_IDEMPOTENCY_KEY = "createVault:CUSTOMER:LOCAL-001"
        private const val DESTINATION_VAULT_NAME = "CUSTOMER:LOCAL-002"
        private const val DESTINATION_VAULT_KEY = "createVault:CUSTOMER:LOCAL-002"
        private const val OPERATOR_VAULT_NAME = "SYSTEM:SWEEP-OPERATOR"
        private const val OPERATOR_VAULT_KEY = "createVault:SYSTEM:SWEEP-OPERATOR"
        private const val NATIVE_WALLET_KEY = "createWallet:1:ETH_LOCAL"
        private const val TOKEN_WALLET_KEY = "createWallet:1:TUSD_LOCAL"
        private const val DESTINATION_NATIVE_WALLET_KEY = "createWallet:2:ETH_LOCAL"
        private const val DESTINATION_TOKEN_WALLET_KEY = "createWallet:2:TUSD_LOCAL"
        private const val OPERATOR_NATIVE_WALLET_KEY = "createWallet:3:ETH_LOCAL"
        private const val LOCAL_NETWORK = "LOCAL"
        private const val TRANSFER_EXTERNAL_ID = "local-transfer-1"
        private const val EXTERNAL_WALLET_TRANSFER_EXTERNAL_ID = "local-external-wallet-transfer-1"
        private const val DEPOSIT_EXTERNAL_ID = "local-deposit-1"
        private const val DEPOSIT_AMOUNT = "3.25"
        private const val BATCH_SWEEP_EXTERNAL_ID = "local-batch-sweep-1"
        private const val BATCH_SWEEP_EXECUTION_ID = "0198c7d5-7a30-7000-8000-000000000011"
        private const val BATCH_SWEEP_AMOUNT = "20"
        private const val CONTRACT_CALL_EXTERNAL_ID = "local-approve-1"
        private const val GASLESS_CONTRACT_CALL_EXTERNAL_ID = "local-gasless-approve-1"
        private const val GASLESS_FEE_PAYER_FAILURE_EXTERNAL_ID = "local-gasless-fee-payer-failure-1"
        private const val CONFLICT_EXTERNAL_ID = "local-transfer-conflict-1"
        private const val LOST_RESPONSE_EXTERNAL_ID = "local-transfer-lost-response-1"
        private const val NATIVE_TRANSFER_EXTERNAL_ID = "local-native-transfer-1"
        private const val RESET_EXTERNAL_ID = "local-reset-transfer-1"
        private const val NATIVE_TRANSFER_AMOUNT = "0.25"
        private val TOKEN_TRANSFER_RAW_AMOUNT = BigInteger("1500000")
        private val DEPOSIT_RAW_AMOUNT = BigInteger("3250000")
        private val BATCH_SWEEP_RAW_AMOUNT = BigInteger("20000000")
        private val INSUFFICIENT_ALLOWANCE_RAW_AMOUNT = BigInteger("10000000")
        private val CONTRACT_CALL_RAW_AMOUNT = BigInteger("25000000")
        private val runtimeDirectory: Path = Files.createTempDirectory("bcm-stateful-stub-")
        private val chain =
            LocalChainEnvironment.start(
                LocalChainConfiguration(
                    seed = "stateful-fireblocks-stub-contract-seed",
                    runtimeDirectory = runtimeDirectory,
                    contractArtifactDirectory = Path.of(requireNotNull(System.getProperty("bcm.contract-artifacts"))),
                ),
            )

        @JvmStatic
        @DynamicPropertySource
        fun localChainProperties(registry: DynamicPropertyRegistry) {
            registry.add("bcm.test-support.evm-rpc-url", chain::rpcUrl)
            registry.add("bcm.test-support.local-chain-manifest-file") {
                runtimeDirectory.resolve("manifest.json").toString()
            }
            registry.add("bcm.test-support.local-chain-key-file") {
                runtimeDirectory.resolve("evm-keys.json").toString()
            }
        }

        @JvmStatic
        @AfterAll
        fun stopLocalChain() {
            chain.close()
            runtimeDirectory.toFile().deleteRecursively()
        }
    }
}
