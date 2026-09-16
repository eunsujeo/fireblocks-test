package com.whatto.bcm.app.api.wallet

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.whatto.bcm.app.application.account.AccountOperations
import com.whatto.bcm.app.application.account.AccountQueryService
import com.whatto.bcm.app.application.account.DepositAddressQueryService
import com.whatto.bcm.app.application.account.DfnsAccountService
import com.whatto.bcm.app.application.asset.FireblocksChainAssetResolver
import com.whatto.bcm.app.application.asset.RegisterVendorAssetMappingCommand
import com.whatto.bcm.app.application.asset.VendorAssetMappingQueryService
import com.whatto.bcm.app.application.asset.VendorAssetMappingService
import com.whatto.bcm.app.config.ClockConfig
import com.whatto.bcm.app.config.DfnsAccountConfig
import com.whatto.bcm.app.config.FireblocksAccountConfig
import com.whatto.bcm.app.config.FireblocksAssetConfig
import com.whatto.bcm.app.config.ProviderOriginConfiguration
import com.whatto.bcm.app.config.WalletProvisioningConfig
import com.whatto.bcm.domain.account.AccountModel
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.asset.TokenStandard
import com.whatto.bcm.domain.exception.AssetNotSupportedException
import com.whatto.bcm.domain.exception.InvalidAssetMappingException
import com.whatto.bcm.domain.exception.ProvisioningPendingException
import com.whatto.bcm.domain.monitoring.NoOpOperationalMetricsPort
import com.whatto.bcm.domain.monitoring.OperationalMetricsPort
import com.whatto.bcm.domain.vendor.ChainAssetResolver
import com.whatto.bcm.domain.vendor.NetworkWalletAssetPort
import com.whatto.bcm.domain.vendor.NetworkWalletProvisioningPort
import com.whatto.bcm.domain.vendor.VendorBalance
import com.whatto.bcm.infra.client.dfns.DfnsChainAssetResolver
import com.whatto.bcm.infra.client.dfns.DfnsClientConfig
import com.whatto.bcm.infra.client.dfns.DfnsNetworkWalletClient
import com.whatto.bcm.infra.persistence.account.AccountJdbcAdapter
import com.whatto.bcm.infra.persistence.account.DepositAddressJdbcAdapter
import com.whatto.bcm.infra.persistence.account.LogicalAccountJdbcAdapter
import com.whatto.bcm.infra.persistence.asset.VendorAssetCatalogCacheJdbcAdapter
import com.whatto.bcm.infra.persistence.asset.VendorAssetMappingJdbcAdapter
import com.whatto.bcm.infra.persistence.asset.VendorBlockchainCatalogJdbcAdapter
import com.whatto.bcm.infra.persistence.provider.ProviderOriginJdbcAdapter
import com.whatto.bcm.infra.persistence.wallet.NetworkWalletEvidenceJdbcAdapter
import com.whatto.bcm.infra.persistence.wallet.NetworkWalletProvisioningJdbcAdapter
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.client.RestClient
import tools.jackson.databind.ObjectMapper
import java.net.InetSocketAddress
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `BCM_PROVIDER=dfns` 조건부 조립 + 공개 계정·주소 유스케이스 + 실제 PostgreSQL 원장/증적 + 공식 명세 형태의 가짜 Dfns HTTP(로컬 HttpServer) 결합.
 * 조립 대상은 계정·주소·잔액 슬라이스와 Admin 자산 등록 유스케이스다 — API 전체 컨텍스트는 `ProviderConfiguration`의 Dfns 기동 차단을 유지하므로 여기서 열지 않는다.
 * Fireblocks 쪽 조립부(FireblocksAccountConfig·WalletProvisioningConfig·FireblocksAssetConfig)도 함께 등록해 조건부 제외가 실제로 동작하는지 본다.
 * 응답 JSON은 명세 `Wallet` schema 필드로 만든 표기이고 Baseline 실측이 아니다. 실벤더 호출 없음.
 */
@DataJdbcTest
@ContextConfiguration(classes = [NetworkWalletDatasetTestConfiguration::class])
@Import(
    DfnsAccountAssemblyIntegrationTest.SliceSupportConfiguration::class,
    ProviderOriginConfiguration::class,
    ClockConfig::class,
    DfnsClientConfig::class,
    DfnsAccountConfig::class,
    FireblocksAccountConfig::class,
    WalletProvisioningConfig::class,
    FireblocksAssetConfig::class,
    AccountQueryService::class,
    DepositAddressQueryService::class,
    VendorAssetMappingQueryService::class,
    VendorAssetMappingService::class,
    ProviderOriginJdbcAdapter::class,
    NetworkWalletProvisioningJdbcAdapter::class,
    NetworkWalletEvidenceJdbcAdapter::class,
    LogicalAccountJdbcAdapter::class,
    AccountJdbcAdapter::class,
    DepositAddressJdbcAdapter::class,
    VendorAssetMappingJdbcAdapter::class,
    VendorBlockchainCatalogJdbcAdapter::class,
    VendorAssetCatalogCacheJdbcAdapter::class,
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DfnsAccountAssemblyIntegrationTest {
    @Autowired lateinit var context: ApplicationContext

    @Autowired lateinit var accounts: AccountOperations

    @Autowired lateinit var jdbc: JdbcTemplate

    @Autowired lateinit var assetMappings: VendorAssetMappingService

    @Configuration(proxyBeanMethods = false)
    class SliceSupportConfiguration {
        @Bean
        fun restClientBuilder(): RestClient.Builder = RestClient.builder()

        @Bean
        fun operationalMetricsPort(): OperationalMetricsPort = NoOpOperationalMetricsPort

        /** 슬라이스 컨텍스트에는 Jackson 자동 구성이 없다 — 실행 앱과 같은 조립을 재현하려면 여기서 제공한다. */
        @Bean
        fun objectMapper(): ObjectMapper = ObjectMapper()
    }

    @BeforeEach
    fun prepareAssets() {
        // Dfns 데이터셋의 네트워크 행은 DBA 데이터셋 seed다(03) — vndr_blkc_id는 채택 명세의 Dfns network 값이다. 네트워크 코드는 시험용이다.
        jdbc.update(
            """
            INSERT INTO bcm_blkc_m (vndr_blkc_id, ntwk_cd, chain_id, dspl_nm, test_yn, deprc_yn, sync_dttm, chain_mdl_dvcd,
                                    frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
            VALUES ('EthereumSepolia', 'ETHEREUM_TEST', 11155111, 'Ethereum Sepolia (test)', 'Y', 'N', '20260915000000', 'EVM', 'SYSTEM', '9999', 'SYSTEM', '9999'),
                   ('SolanaDevnet', 'SOLANA_TEST', NULL, 'Solana Devnet (test)', 'Y', 'N', '20260915000000', 'SOLANA', 'SYSTEM', '9999', 'SYSTEM', '9999')
            ON CONFLICT (vndr_blkc_id) DO NOTHING
            """.trimIndent(),
        )
        // 사전 등록 매핑 — USDC/KRWK는 Dfns 자산 키 형식(등록 관문과 같은 규칙), SOLANA_TEST는 모델 미확정 네트워크의 시험 행이다.
        listOf(
            Triple("ETHEREUM_TEST", "USDC", "EthereumSepolia:Erc20:${FakeDfns.USDC_CONTRACT.lowercase()}"),
            Triple("ETHEREUM_TEST", "KRWK", "EthereumSepolia:Erc20:${FakeDfns.KRWK_CONTRACT.lowercase()}"),
            Triple("SOLANA_TEST", "USDC", "SolanaDevnet:Spl:${FakeDfns.SOLANA_USDC_MINT}"),
        ).forEach { (network, symbol, vendorAssetId) ->
            jdbc.update(
                """
                INSERT INTO bcm_vndr_ast_m (ntwk_cd, tkn_smbl, vndr_ast_id, cntr_addr, actv_yn, reg_dttm,
                                            frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
                VALUES (?, ?, ?, ?, 'Y', '20260915000000', 'SYSTEM', '9999', 'SYSTEM', '9999')
                ON CONFLICT (ntwk_cd, tkn_smbl) DO NOTHING
                """.trimIndent(),
                network,
                symbol,
                vendorAssetId,
                when {
                    network == "SOLANA_TEST" -> FakeDfns.SOLANA_USDC_MINT
                    symbol == "KRWK" -> FakeDfns.KRWK_CONTRACT
                    else -> FakeDfns.USDC_CONTRACT
                },
            )
        }
        dfns.reset()
    }

    @AfterEach
    fun cleanup() {
        jdbc.update("DELETE FROM bcm_addr_m WHERE acnt_id IN (SELECT acnt_id FROM bcm_acnt_m WHERE ref LIKE 'assembly-%')")
        jdbc.execute("ALTER TABLE bcm_ntwk_wlt_evdc_l DISABLE TRIGGER trg_bcm_ntwk_wlt_evdc_append_only")
        jdbc.update("DELETE FROM bcm_ntwk_wlt_evdc_l")
        jdbc.execute("ALTER TABLE bcm_ntwk_wlt_evdc_l ENABLE TRIGGER trg_bcm_ntwk_wlt_evdc_append_only")
        jdbc.update("DELETE FROM bcm_ntwk_wlt_m")
        jdbc.update("DELETE FROM bcm_ntwk_wlt_obs_item_l")
        jdbc.update("DELETE FROM bcm_ntwk_wlt_obs_l")
        jdbc.update("DELETE FROM bcm_ntwk_wlt_crtn_l")
        jdbc.update("DELETE FROM bcm_acnt_m WHERE ref LIKE 'assembly-%'")
        jdbc.execute("ALTER TABLE bcm_vndr_ast_chng_l DISABLE TRIGGER trg_bcm_vndr_ast_chng_no_delete")
        jdbc.update("DELETE FROM bcm_vndr_ast_chng_l WHERE tkn_smbl LIKE 'ASM%'")
        jdbc.execute("ALTER TABLE bcm_vndr_ast_chng_l ENABLE TRIGGER trg_bcm_vndr_ast_chng_no_delete")
        jdbc.update("DELETE FROM bcm_vndr_ast_m WHERE tkn_smbl LIKE 'ASM%'")
    }

    @Test
    fun `dfns 선택은 Dfns 계정 유스케이스와 HTTP 어댑터만 조립하고 Fireblocks 계정 서비스·생성 정책은 만들지 않는다`() {
        assertThat(accounts).isInstanceOf(DfnsAccountService::class.java)
        assertThat(context.getBean(NetworkWalletProvisioningPort::class.java)).isInstanceOf(DfnsNetworkWalletClient::class.java)
        assertThat(context.getBean(NetworkWalletAssetPort::class.java)).isInstanceOf(DfnsNetworkWalletClient::class.java)
        assertThat(context.getBean(ChainAssetResolver::class.java)).isInstanceOf(DfnsChainAssetResolver::class.java)
        assertThat(context.getBeanNamesForType(FireblocksChainAssetResolver::class.java)).isEmpty()
        assertThat(context.getBeansOfType(AccountOperations::class.java)).hasSize(1)
        assertThat(context.beanDefinitionNames).noneMatch { it.contains("fireblocks", ignoreCase = true) || it == "accountService" }
        assertThat(context.beanDefinitionNames).noneMatch { it.contains("walletProvisioningPolicy") }
        assertThat(context.getBeanNamesForType(com.whatto.bcm.app.application.account.AccountService::class.java)).isEmpty()
    }

    @Test
    fun `계정 생성은 외부 호출 없이 논리 계정으로 멱등 완료된다`() {
        val ref = "assembly-${UUID.randomUUID()}"
        val created = accounts.createAccount(AccountType.CUSTOMER, ref)
        val again = accounts.createAccount(AccountType.CUSTOMER, ref)

        assertThat(created.model).isEqualTo(AccountModel.LOGICAL)
        assertThat(created.vendorVaultId).isNull()
        assertThat(again).isEqualTo(created)
        assertThat(dfns.requests).isEmpty()
    }

    @Test
    fun `첫 주소 발급이 서명된 지갑 생성 뒤 지갑 주소를 저장하고 같은 네트워크의 다른 토큰과 재요청은 외부 호출 없이 같은 주소를 받는다`() {
        val account = accounts.createAccount(AccountType.CUSTOMER, "assembly-${UUID.randomUUID()}")
        dfns.addressReady = true

        val first = accounts.createDepositAddresses(account.accountId, "USDC", listOf("ETHEREUM_TEST"))
        val krwk = accounts.createDepositAddress(account.accountId, "ETHEREUM_TEST", "KRWK")
        val again = accounts.createDepositAddresses(account.accountId, "USDC", listOf("ETHEREUM_TEST"))

        assertThat(first.single().failure).isNull()
        assertThat(first.single().depositAddress?.address).isEqualTo(FakeDfns.ADDRESS)
        assertThat(krwk.address).isEqualTo(FakeDfns.ADDRESS)
        assertThat(again).isEqualTo(first)
        assertThat(dfns.requests.map { it.first + " " + it.second })
            .containsExactly("POST /auth/action/init", "POST /auth/action", "POST /wallets")
        assertThat(dfns.userActionHeaders).containsExactly("ua-token-1")
        assertThat(accounts.depositAddressesOf(account.accountId, null, null).map { it.symbol }).containsExactlyInAnyOrder("USDC", "KRWK")
        assertThat(evidenceOperations()).containsExactly("CREATE")
    }

    @Test
    fun `지갑 주소가 아직 없으면 보류 항목으로 답하고 다음 요청은 새 생성 없이 조회로 완료한다`() {
        val account = accounts.createAccount(AccountType.CUSTOMER, "assembly-${UUID.randomUUID()}")
        dfns.addressReady = false

        val pending = accounts.createDepositAddresses(account.accountId, "USDC", listOf("ETHEREUM_TEST")).single()

        assertThat(pending.depositAddress).isNull()
        assertThat(pending.failure).isInstanceOfSatisfying(ProvisioningPendingException::class.java) {
            assertThat(it.retryAfterSeconds).isEqualTo(3)
            assertThat(it.reason).isEqualTo("ADDRESS_NOT_READY")
        }
        dfns.addressReady = true

        val issued = accounts.createDepositAddress(account.accountId, "ETHEREUM_TEST", "USDC")

        assertThat(issued.address).isEqualTo(FakeDfns.ADDRESS)
        assertThat(dfns.requests.map { it.first + " " + it.second })
            .containsExactly("POST /auth/action/init", "POST /auth/action", "POST /wallets", "GET /wallets/${FakeDfns.WALLET_ID}")
        assertThat(evidenceOperations()).containsExactly("CREATE", "READ")
    }

    @Test
    fun `수신 주소 모델이 확인되지 않은 네트워크가 섞이면 아무것도 발급하지 않고 미발급 계정의 잔액은 벤더 호출 없이 빈 배열이다`() {
        val account = accounts.createAccount(AccountType.CUSTOMER, "assembly-${UUID.randomUUID()}")

        assertThatThrownBy { accounts.createDepositAddresses(account.accountId, "USDC", listOf("ETHEREUM_TEST", "SOLANA_TEST")) }
            .isInstanceOf(AssetNotSupportedException::class.java)
        assertThat(accounts.balancesOf(account.accountId, null, null)).isEmpty()
        assertThat(dfns.requests).isEmpty()
        assertThat(accounts.depositAddressesOf(account.accountId, null, null)).isEmpty()
    }

    @Test
    fun `잔액 조회는 발급 네트워크의 지갑 자산을 한 번 읽어 매핑 키가 같은 항목을 돌려주고 목록에 없는 발급 자산은 0이다`() {
        val account = accounts.createAccount(AccountType.CUSTOMER, "assembly-${UUID.randomUUID()}")
        accounts.createDepositAddresses(account.accountId, "USDC", listOf("ETHEREUM_TEST"))
        accounts.createDepositAddress(account.accountId, "ETHEREUM_TEST", "KRWK")
        dfns.reset()

        val balances = accounts.balancesOf(account.accountId, null, null)

        assertThat(balances.map { it.symbol to it.balance }).containsExactlyInAnyOrder(
            "USDC" to VendorBalance("1.5", "1.5", null, null, null),
            "KRWK" to VendorBalance("0", "0", null, null, null),
        )
        assertThat(dfns.requests.map { it.first + " " + it.second }).containsExactly("GET /wallets/${FakeDfns.WALLET_ID}/assets")
        assertThat(dfns.userActionHeaders).isEmpty()
    }

    @Test
    fun `Dfns 데이터셋의 자산 등록은 벤더 호출 없이 network·contractAddress로 Dfns 자산 키를 만들어 저장하고 Fireblocks asset id·비EVM 모델은 거절한다`() {
        val registered =
            assetMappings.register(
                RegisterVendorAssetMappingCommand(
                    "ETHEREUM_TEST",
                    "ASMDAI",
                    null,
                    FakeDfns.DAI_CONTRACT,
                    "123456",
                    "0001",
                    "req-asm-1",
                    decimals = 18,
                ),
            )

        assertThat(registered.vendorAssetId).isEqualTo("EthereumSepolia:Erc20:${FakeDfns.DAI_CONTRACT.lowercase()}")
        assertThat(registered.contractAddress).isEqualTo(FakeDfns.DAI_CONTRACT)
        // 정밀도는 등록값으로 보존한다 — Dfns에는 카탈로그가 없어 해소로 얻을 값이 없다(03 V27).
        assertThat(registered.decimals).isEqualTo(18)
        assertThat(assetMappings.mappings("ETHEREUM_TEST", "ASMDAI").single().decimals).isEqualTo(18)
        assertThat(assetMappings.mappings("ETHEREUM_TEST", "ASMDAI").single().vendorAssetId).isEqualTo(registered.vendorAssetId)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bcm_vndr_ast_chng_l WHERE tkn_smbl = 'ASMDAI'", Int::class.java)).isEqualTo(1)

        assertThatThrownBy {
            assetMappings.register(
                RegisterVendorAssetMappingCommand(
                    "ETHEREUM_TEST",
                    "ASMFB",
                    "USDC_ETH_TEST5",
                    FakeDfns.DAI_CONTRACT,
                    "123456",
                    "0001",
                    decimals = 18,
                ),
            )
        }.isInstanceOfSatisfying(
            InvalidAssetMappingException::class.java,
        ) { assertThat(it.reason).isEqualTo("fireblocksAssetIdNotApplicable") }
        // Solana 모델 행 — mint는 운영자가 명시한 토큰 표준과 함께 Spl 키로, 표준이 없으면 거절이다(V25 chain_mdl_dvcd).
        val sol =
            assetMappings.register(
                RegisterVendorAssetMappingCommand(
                    "SOLANA_TEST",
                    "ASMSOL",
                    null,
                    FakeDfns.SOLANA_KRWK_MINT,
                    "123456",
                    "0001",
                    tokenStandard = TokenStandard.SPL_2022,
                    decimals = 6,
                ),
            )
        assertThat(sol.vendorAssetId).isEqualTo("SolanaDevnet:Spl2022:${FakeDfns.SOLANA_KRWK_MINT}")
        assertThatThrownBy {
            assetMappings.register(
                RegisterVendorAssetMappingCommand(
                    "SOLANA_TEST",
                    "ASMNOSTD",
                    null,
                    FakeDfns.SOLANA_KRWK_MINT,
                    "123456",
                    "0001",
                    decimals = 6,
                ),
            )
        }.isInstanceOfSatisfying(InvalidAssetMappingException::class.java) { assertThat(it.reason).isEqualTo("tokenStandardRequired") }
        // 정밀도가 없으면 Dfns 원천은 등록을 거절한다 — 등록 뒤에 금액 환산이 막히지 않게 관문에서 먼저 막는다.
        assertThatThrownBy {
            assetMappings.register(
                RegisterVendorAssetMappingCommand("ETHEREUM_TEST", "ASMNODEC", null, FakeDfns.DAI_CONTRACT, "123456", "0001"),
            )
        }.isInstanceOfSatisfying(InvalidAssetMappingException::class.java) { assertThat(it.reason).isEqualTo("decimalsRequired") }
        assertThat(assetMappings.mappings(null, null).map { it.symbol }).doesNotContain("ASMFB", "ASMNOSTD", "ASMNODEC")
        assertThat(dfns.requests).isEmpty()
    }

    private fun evidenceOperations(): List<String> =
        jdbc
            .queryForList(
                "SELECT oprtn_dvcd FROM bcm_ntwk_wlt_evdc_l ORDER BY obs_dttm, oprtn_dvcd",
                String::class.java,
            ).map { checkNotNull(it) }

    /** 공식 OpenAPI 1.1018.3의 경로·필드 모양만 흉내 낸 로컬 서버 — 서명은 검증하지 않고 헤더 존재만 기록한다. */
    class FakeDfns {
        val requests = CopyOnWriteArrayList<Pair<String, String>>()
        val userActionHeaders = CopyOnWriteArrayList<String>()

        @Volatile var addressReady = true

        /** 생성 요청의 externalId — 실제 Dfns처럼 생성/조회 응답에 그대로 되돌린다. */
        @Volatile private var externalId: String = ""
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val baseUrl: String

        init {
            server.createContext("/") { exchange -> handle(exchange) }
            server.start()
            baseUrl = "http://127.0.0.1:${server.address.port}"
        }

        fun reset() {
            requests.clear()
            userActionHeaders.clear()
            addressReady = true
            externalId = ""
        }

        fun stop() = server.stop(0)

        private fun handle(exchange: HttpExchange) {
            val path = exchange.requestURI.path
            requests += exchange.requestMethod to path
            exchange.requestHeaders.getFirst("X-DFNS-USERACTION")?.let(userActionHeaders::add)
            val requestBody = exchange.requestBody.readAllBytes().toString(Charsets.UTF_8)
            if (exchange.requestMethod == "POST" && path == "/wallets") {
                externalId =
                    checkNotNull(Regex(""""externalId":"([^"]+)"""").find(requestBody)?.groupValues?.get(1)) { "externalId missing" }
            }
            val (status, body) =
                when {
                    exchange.requestMethod == "POST" && path == "/auth/action/init" ->
                        200 to
                            """{"challenge":"Y2gtNzloaHQtbXJlb2stOGFwOHFtMmVpZWZ0amxhZw","challengeIdentifier":"eyJ0e.fQNA",
                               "allowCredentials":{"key":[{"type":"public-key","id":"$CREDENTIAL_ID"}]}}"""
                    exchange.requestMethod == "POST" && path == "/auth/action" -> 200 to """{"userAction":"ua-token-1"}"""
                    exchange.requestMethod == "POST" && path == "/wallets" -> 200 to wallet()
                    exchange.requestMethod == "GET" && path == "/wallets/$WALLET_ID" -> 200 to wallet()
                    exchange.requestMethod == "GET" && path == "/wallets/$WALLET_ID/assets" -> 200 to walletAssets()
                    else -> 404 to """{"error":{"message":"not found"}}"""
                }
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }

        private fun wallet(): String =
            """{"id":"$WALLET_ID","network":"EthereumSepolia"${if (addressReady) ""","address":"$ADDRESS"""" else ""},
               "signingKey":{"id":"key-test0-test0-test0test0test0","scheme":"ECDSA","curve":"secp256k1","publicKey":"00"},
               "status":"Active","dateCreated":"2026-09-15T00:00:00.000Z","custodial":true,"externalId":"$externalId","tags":[]}"""

        /** 명세 Get Wallet Assets 형태 — Native·등록 USDC(대소문자 다른 컨트랙트)·모델 밖 kind. KRWK는 목록에 없다. */
        private fun walletAssets(): String =
            """{"walletId":"$WALLET_ID","network":"EthereumSepolia","assets":[
               {"kind":"Native","symbol":"ETH","decimals":18,"verified":true,"balance":"250000000000000000"},
               {"kind":"Erc20","contract":"${USDC_CONTRACT.uppercase().replace(
                "0X",
                "0x",
            )}","symbol":"USDC","decimals":6,"verified":true,"balance":"1500000"},
               {"kind":"Iou","currency":"USD","issuer":"r1","decimals":15,"balance":"1"}]}"""

        companion object {
            const val WALLET_ID = "wa-assem-bly00-000000000000000"
            const val ADDRESS = "0x00e3495cf6af59008f22ffaf32d4c92ac33dac47"
            const val USDC_CONTRACT = "0x1c7D4B196Cb0C7B01d743Fbc6116a902379C7238"
            const val KRWK_CONTRACT = "0x0000000000000000000000000000000000000002"
            const val DAI_CONTRACT = "0xFF34B3d4Aee8ddCd6F9AFFFB6Fe49bD371b8a357"

            /** base58 32바이트 형식의 시험용 mint 값 — 실제 발행 자산을 가리키지 않는다. */
            const val SOLANA_USDC_MINT = "4zMMC9srt5Ri5X14GAgXhaHii3GnPAEERYPJgZJDncDU"
            const val SOLANA_KRWK_MINT = "Es9vMFrzaCERmJfrF4H2FYD4KCoNkY11McCe8BenwNYB"
        }
    }

    companion object {
        private const val CREDENTIAL_ID = "cr-test0-test0-test0test0test0"
        private val dfns = FakeDfns()

        @JvmStatic
        @AfterAll
        fun stopServer() = dfns.stop()

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            DfnsDatasetTestSupport.register(registry)
            val keyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
            val pemBody = Base64.getEncoder().encodeToString(keyPair.private.encoded)
            registry.add("bcm.provider") { "dfns" }
            registry.add("bcm.chain-mode") { "TESTNET" }
            registry.add("bcm.origin.id") { "test-dfns-origin" }
            registry.add("bcm.origin.platform-instance-id") { "test-dfns-platform" }
            registry.add("bcm.origin.vendor-organization-id") { "test-dfns-organization" }
            registry.add("bcm.dfns.base-url") { dfns.baseUrl }
            registry.add("bcm.dfns.auth-token") { "test-service-account-token" }
            registry.add("bcm.dfns.credential-id") { CREDENTIAL_ID }
            registry.add("bcm.dfns.credential-private-key-pem") { "-----BEGIN PRIVATE KEY-----\n$pemBody\n-----END PRIVATE KEY-----" }
            registry.add("bcm.dfns.networks.ETHEREUM_TEST") { "EthereumSepolia" }
            registry.add("bcm.dfns.networks.SOLANA_TEST") { "SolanaDevnet" }
            registry.add("bcm.dfns.account-address-networks") { "ETHEREUM_TEST" }
            registry.add("bcm.dfns.provisioning-retry-after-seconds") { "3" }
        }
    }
}
