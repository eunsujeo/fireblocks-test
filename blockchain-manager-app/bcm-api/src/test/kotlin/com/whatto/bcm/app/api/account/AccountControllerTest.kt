package com.whatto.bcm.app.api.account

import com.ninjasquad.springmockk.MockkBean
import com.whatto.bcm.app.application.account.AccountService
import com.whatto.bcm.app.application.account.fixture.AccountFixture
import com.whatto.bcm.app.application.account.fixture.DepositAddressFixture
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.exception.AccountNotFoundException
import com.whatto.bcm.domain.exception.AssetNotSupportedException
import io.mockk.every
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

/**
 * 계정·주소 API 계약 — openapi v0.3.0: 201/200 envelope · maxLength 경계 400 · 404 ACCOUNT_NOT_FOUND ·
 * 미발급 주소 조회는 빈 배열이다.
 */
@WebMvcTest(AccountController::class)
class AccountControllerTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @MockkBean
    private lateinit var accountService: AccountService

    @Test
    fun `createAccount — 201, data 에 accountType·ref·accountId envelope`() {
        every { accountService.createAccount(AccountType.CUSTOMER, "000123") } returns AccountFixture.fixture()

        mockMvc
            .perform(
                post("/accounts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"accountType":"CUSTOMER","ref":"000123"}"""),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.data.ref").value("000123"))
            .andExpect(jsonPath("$.data.accountId").value("acct_test_01"))
            .andExpect(jsonPath("$.meta.requestId").isNotEmpty)
    }

    @Test
    fun `createAccount — ref 누락·빈 값은 400 VALIDATION_FAILED, 서비스 미호출`() {
        mockMvc
            .perform(
                post("/accounts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))

        mockMvc
            .perform(
                post("/accounts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"accountType":"CUSTOMER","ref":"  "}"""),
            ).andExpect(status().isBadRequest)

        verify(exactly = 0) { accountService.createAccount(any(), any()) }
    }

    @Test
    fun `createAccount — ref 65자는 400 (maxLength 64 경계)`() {
        val ref64 = "A".repeat(64)
        val ref65 = "A".repeat(65)
        every { accountService.createAccount(AccountType.CUSTOMER, ref64) } returns AccountFixture.fixture(ref = ref64)

        mockMvc
            .perform(
                post("/accounts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"accountType":"CUSTOMER","ref":"$ref64"}"""),
            ).andExpect(status().isCreated)

        mockMvc
            .perform(
                post("/accounts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"accountType":"CUSTOMER","ref":"$ref65"}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `파라미터 경계 — accountId 65자·네트워크 21자·심볼 17자는 400`() {
        mockMvc
            .perform(get("/accounts/${"a".repeat(65)}/balances"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))

        mockMvc
            .perform(get("/accounts/acct_test_01/balances").param("network", "A".repeat(21)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))

        mockMvc
            .perform(get("/accounts/acct_test_01/balances").param("symbol", "A".repeat(17)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
    }

    @Test
    fun `depositAddressesOf — 발급된 주소를 배열로 돌려준다`() {
        every { accountService.depositAddressesOf("acct_test_01", "USDC", null) } returns
            listOf(DepositAddressFixture.fixture(), DepositAddressFixture.fixture(network = "BASE", address = "0xBASE"))

        mockMvc
            .perform(get("/accounts/acct_test_01/addresses").param("symbol", "USDC"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].network").value("ETHEREUM"))
            .andExpect(jsonPath("$.data[0].symbol").value("USDC"))
            .andExpect(jsonPath("$.data[0].token").doesNotExist())
            .andExpect(jsonPath("$.data[1].address").value("0xBASE"))
    }

    @Test
    fun `depositAddressesOf — 미발급이면 빈 배열 (null 특수 케이스를 두지 않는다)`() {
        every { accountService.depositAddressesOf("acct_test_01", null, null) } returns emptyList()

        mockMvc
            .perform(get("/accounts/acct_test_01/addresses"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.length()").value(0))
            .andExpect(jsonPath("$.meta.requestId").isNotEmpty)
    }

    @Test
    fun `depositAddressesOf — 계정 없음은 404 ACCOUNT_NOT_FOUND (미발급 빈 배열과 구분)`() {
        every { accountService.depositAddressesOf("acct_none", null, null) } throws AccountNotFoundException("acct_none")

        mockMvc
            .perform(get("/accounts/acct_none/addresses"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error.code").value("ACCOUNT_NOT_FOUND"))
    }

    @Test
    fun `createDepositAddresses — 미지원 매핑이 섞이면 전체 400 ASSET_NOT_SUPPORTED`() {
        every {
            accountService.createDepositAddresses("acct_test_01", "USDC", listOf("ETHEREUM", "BASE"))
        } throws AssetNotSupportedException("BASE", "USDC")

        mockMvc
            .perform(
                post("/accounts/acct_test_01/addresses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"symbol":"USDC","networks":["ETHEREUM","BASE"]}"""),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error.code").value("ASSET_NOT_SUPPORTED"))
    }
}
