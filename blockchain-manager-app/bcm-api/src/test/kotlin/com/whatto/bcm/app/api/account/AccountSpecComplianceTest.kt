package com.whatto.bcm.app.api.account

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers.openApi
import com.ninjasquad.springmockk.MockkBean
import com.whatto.bcm.app.application.account.AccountService
import com.whatto.bcm.app.application.account.AddressOutcome
import com.whatto.bcm.app.application.account.AssetBalance
import com.whatto.bcm.app.application.account.fixture.AccountFixture
import com.whatto.bcm.app.application.account.fixture.DepositAddressFixture
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.exception.AccountNotFoundException
import com.whatto.bcm.domain.exception.CreationRetryLaterException
import com.whatto.bcm.domain.vendor.VendorBalance
import io.mockk.every
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.io.File

/**
 * 스펙 스키마 자동 대조 (T2.3 이월분 회수) — 실제 응답을 openapi.yaml(정본)과 검증 라이브러리로 대조한다.
 * jsonPath 수동 검증(AccountControllerTest)이 못 잡는 스키마 drift(필드 누락·타입 변화)를 잡는 계약 그물.
 */
@WebMvcTest(AccountController::class)
class AccountSpecComplianceTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var accountService: AccountService

    @Test
    fun `createAccount 201 응답이 스펙 AccountResponse 와 일치한다`() {
        every { accountService.createAccount(AccountType.CUSTOMER, "000123") } returns AccountFixture.fixture()

        mockMvc
            .perform(
                post("/accounts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"accountType":"CUSTOMER","ref":"000123"}"""),
            ).andExpect(status().isCreated)
            .andExpect(openApi().isValid(SPEC))
    }

    @Test
    fun `depositAddressesOf 200 응답이 스펙 DepositAddressListResponse 와 일치한다 (빈 배열 포함)`() {
        every { accountService.depositAddressesOf("acct_test_01", null, null) } returns
            listOf(DepositAddressFixture.fixture())
        mockMvc
            .perform(get("/accounts/acct_test_01/addresses"))
            .andExpect(status().isOk)
            .andExpect(openApi().isValid(SPEC))

        every { accountService.depositAddressesOf("acct_test_01", null, null) } returns emptyList()
        mockMvc
            .perform(get("/accounts/acct_test_01/addresses"))
            .andExpect(status().isOk)
            .andExpect(openApi().isValid(SPEC))
    }

    @Test
    fun `balancesOf 200 응답이 스펙 AssetBalanceListResponse 와 일치한다 (미발급 빈 배열 포함)`() {
        every { accountService.balancesOf("acct_test_01", null, null) } returns
            listOf(
                AssetBalance(
                    network = "ETHEREUM",
                    symbol = "USDC",
                    balance =
                        VendorBalance(
                            total = "11.8",
                            available = "10.5",
                            pending = "1.0",
                            frozen = "0.2",
                            lockedAmount = "0.1",
                        ),
                ),
            )

        mockMvc
            .perform(get("/accounts/acct_test_01/balances"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].symbol").value("USDC"))
            .andExpect(jsonPath("$.data[0].token").doesNotExist())
            .andExpect(openApi().isValid(SPEC))

        every { accountService.balancesOf("acct_test_01", "BASE", "USDC") } returns emptyList()
        mockMvc
            .perform(
                get("/accounts/acct_test_01/balances")
                    .param("network", "BASE")
                    .param("symbol", "USDC"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(0))
            .andExpect(openApi().isValid(SPEC))
    }

    @Test
    fun `404 에러 응답이 스펙 ErrorResponse 와 일치한다`() {
        every { accountService.balancesOf("acct_none", null, null) } throws AccountNotFoundException("acct_none")

        mockMvc
            .perform(get("/accounts/acct_none/balances"))
            .andExpect(status().isNotFound)
            .andExpect(openApi().isValid(SPEC))
    }

    @Test
    fun `GET 계정 경로변수 검증 400 응답이 주소와 잔액 스펙 ErrorResponse 에 명시된다`() {
        val tooLongAccountId = "a".repeat(65)

        mockMvc
            .perform(get("/accounts/$tooLongAccountId/addresses"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
            .andExpect(openApi().isValid(SPEC))

        mockMvc
            .perform(get("/accounts/$tooLongAccountId/balances"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
            .andExpect(openApi().isValid(SPEC))
    }

    @Test
    fun `createDepositAddresses 200 응답이 스펙 AddressesResponse 와 일치한다 (부분 실패 포함)`() {
        every { accountService.createDepositAddresses("acct_test_01", "USDC", listOf("ETHEREUM", "TRON")) } returns
            listOf(
                AddressOutcome(
                    network = "ETHEREUM",
                    symbol = "USDC",
                    depositAddress = DepositAddressFixture.fixture(),
                    failure = null,
                ),
                AddressOutcome(
                    network = "TRON",
                    symbol = "USDC",
                    depositAddress = null,
                    failure = CreationRetryLaterException("acct_test_01:TRON:USDC", 82_800),
                ),
            )

        mockMvc
            .perform(
                post("/accounts/acct_test_01/addresses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"symbol":"USDC","networks":["ETHEREUM","TRON"]}"""),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.data[0].symbol").value("USDC"))
            .andExpect(jsonPath("$.data[0].token").doesNotExist())
            .andExpect(jsonPath("$.data[1].error.code").value("CREATION_RETRY_LATER"))
            .andExpect(jsonPath("$.data[1].error.retryAfterSeconds").value(82_800))
            .andExpect(openApi().isValid(SPEC))
    }

    @Test
    fun `여러 네트워크 발급 빈 배열은 400 — 스펙 minItems 1`() {
        mockMvc
            .perform(
                post("/accounts/acct_test_01/addresses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"symbol":"USDC","networks":[]}"""),
            ).andExpect(status().isBadRequest)
    }

    companion object {
        /** 테스트 작업 디렉터리 = 모듈 디렉터리 — 저장소 루트의 정본 스펙을 가리킨다 */
        private val SPEC: String = File("../../docs/api/openapi.yaml").absolutePath
    }
}
