package com.whatto.bcm.infra.persistence.account

import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountCreationIntent
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.account.CreationStatus
import com.whatto.bcm.domain.account.DepositAddress
import com.whatto.bcm.domain.account.DepositAddressCreationIntent
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.CreationRetryLaterException
import com.whatto.bcm.infra.persistence.support.PersistenceTestSupport
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jdbc.test.autoconfigure.DataJdbcTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@DataJdbcTest
@Import(AccountJdbcAdapter::class, DepositAddressJdbcAdapter::class, WalletProvisioningJdbcAdapter::class)
class AccountPersistenceTest : PersistenceTestSupport() {
    @Autowired
    lateinit var accounts: AccountJdbcAdapter

    @Autowired
    lateinit var addresses: DepositAddressJdbcAdapter

    @Autowired
    lateinit var provisioning: WalletProvisioningJdbcAdapter

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    private fun account(
        accountId: String = "acct_01",
        accountType: AccountType = AccountType.CUSTOMER,
        ref: String = "000001",
    ) = Account(
        accountId = accountId,
        accountType = accountType,
        ref = ref,
        vendorVaultId = "vault-7",
        registeredAt = "20260805120000",
    )

    @Test
    fun `계정 매핑 왕복 — (유형, ref) 와 accountId 로 같은 도메인 객체를 되찾는다`() {
        val saved = accounts.insert(account())
        assertThat(accounts.findByTypeAndRef(AccountType.CUSTOMER, "000001")).isEqualTo(saved)
        assertThat(accounts.findByAccountId("acct_01")).isEqualTo(saved)
    }

    @Test
    fun `계정 생성 멱등의 최종 방어 — 같은 (유형, ref) 재삽입은 도메인 ConflictException 으로 변환된다`() {
        accounts.insert(account(accountId = "acct_02", ref = "000DUP"))
        assertThatThrownBy { accounts.insert(account(accountId = "acct_03", ref = "000DUP")) }
            .isInstanceOf(ConflictException::class.java)
            .hasRootCauseInstanceOf(java.sql.SQLException::class.java) // cause 체인 보존 (error-handling.md)
    }

    @Test
    fun `vault 생성 의도는 벤더 호출 전에 고정되고 완료 시 계정 매핑과 한 트랜잭션으로 종결된다`() {
        val candidate =
            AccountCreationIntent(
                accountId = "acct_intent",
                accountType = AccountType.CUSTOMER,
                ref = "intent-ref",
                vendorVaultName = "CUSTOMER:intent-ref",
                idempotencyKey = "bcm-vlt-00000000000000000000000000000001",
                idempotencyKeyRegisteredAt = "20260901000000",
                lastVendorCallPreparedAt = null,
                status = CreationStatus.PENDING,
                attemptCount = 0,
                vendorVaultId = null,
                registeredAt = "20260901000000",
                lastChangedAt = "20260901000000",
            )

        val reserved = provisioning.reserveAccount(candidate)
        val raced = provisioning.reserveAccount(candidate.copy(accountId = "acct_loser", idempotencyKey = "bcm-vlt-loser"))
        val submitting = provisioning.beginAccountAttempt(reserved.accountId, "20260901000001")
        val firstPrepared =
            provisioning.prepareAccountVendorCall(
                submitting,
                submitting.idempotencyKey,
                submitting.idempotencyKeyRegisteredAt,
                "20260901000001",
            )
        val superseding = provisioning.beginAccountAttempt(reserved.accountId, "20260902000000")
        val stalePreparation =
            provisioning.prepareAccountVendorCall(
                submitting,
                submitting.idempotencyKey,
                submitting.idempotencyKeyRegisteredAt,
                "20260902000000",
            )
        val prepared =
            provisioning.prepareAccountVendorCall(
                superseding,
                "bcm-vlt-00000000000000000000000000000002",
                "20260902000000",
                "20260902000000",
            )
        assertThatThrownBy {
            provisioning.completeAccount(checkNotNull(firstPrepared), "vault-stale", "20260902000001")
        }.isInstanceOf(CreationRetryLaterException::class.java)
        assertThat(accounts.findByTypeAndRef(AccountType.CUSTOMER, "intent-ref")).isNull()
        val completed = provisioning.completeAccount(checkNotNull(prepared), "vault-77", "20260902000001")

        assertThat(raced).isEqualTo(reserved)
        assertThat(submitting.status).isEqualTo(CreationStatus.SUBMITTING)
        assertThat(submitting.attemptCount).isEqualTo(1)
        assertThat(stalePreparation).isNull()
        assertThat(prepared.lastVendorCallPreparedAt).isEqualTo("20260902000000")
        assertThat(completed.accountId).isEqualTo("acct_intent")
        assertThat(completed.vendorVaultId).isEqualTo("vault-77")
        assertThat(accounts.findByTypeAndRef(AccountType.CUSTOMER, "intent-ref")).isEqualTo(completed)
        assertThat(provisioning.findAccount(AccountType.CUSTOMER, "intent-ref")?.status)
            .isEqualTo(CreationStatus.COMPLETED)
        assertThat(provisioning.beginAccountAttempt(reserved.accountId, "20260901000003").status)
            .isEqualTo(CreationStatus.COMPLETED)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `계정 새 키 세대 준비가 잠근 동안 이전 세대 완료는 기다린 뒤 매핑 없이 거절된다`() {
        val firstPrepared =
            reserveAndPrepareAccount(
                accountCreationIntent(
                    accountId = "acct_generation_race",
                    ref = "generation-race-ref",
                    idempotencyKey = "bcm-vlt-00000000000000000000000000000011",
                ),
            )
        val rotated = CountDownLatch(1)
        val releaseRotation = CountDownLatch(1)
        val completionAttempted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val transaction = requiresNewTransaction()

        try {
            val rotation =
                executor.submit<AccountCreationIntent> {
                    checkNotNull(
                        transaction.execute {
                            val superseding =
                                provisioning.beginAccountAttempt(firstPrepared.accountId, "20260902000000")
                            val prepared =
                                checkNotNull(
                                    provisioning.prepareAccountVendorCall(
                                        superseding,
                                        "bcm-vlt-00000000000000000000000000000012",
                                        "20260902000000",
                                        "20260902000000",
                                    ),
                                )
                            rotated.countDown()
                            check(releaseRotation.await(5, TimeUnit.SECONDS))
                            prepared
                        },
                    )
                }
            assertThat(rotated.await(5, TimeUnit.SECONDS)).isTrue()

            val staleCompletion =
                executor.submit<Throwable?> {
                    completionAttempted.countDown()
                    runCatching {
                        provisioning.completeAccount(firstPrepared, "vault-stale-race", "20260902000001")
                    }.exceptionOrNull()
                }
            assertThat(completionAttempted.await(5, TimeUnit.SECONDS)).isTrue()
            assertThatThrownBy { staleCompletion.get(300, TimeUnit.MILLISECONDS) }
                .isInstanceOf(TimeoutException::class.java)

            releaseRotation.countDown()
            val currentGeneration = rotation.get(5, TimeUnit.SECONDS)
            assertThat(staleCompletion.get(5, TimeUnit.SECONDS))
                .isInstanceOf(CreationRetryLaterException::class.java)
            assertThat(accounts.findByTypeAndRef(AccountType.CUSTOMER, firstPrepared.ref)).isNull()

            val completed = provisioning.completeAccount(currentGeneration, "vault-current-race", "20260902000002")
            assertThat(completed.vendorVaultId).isEqualTo("vault-current-race")
        } finally {
            releaseRotation.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            jdbc.update("DELETE FROM bcm_acnt_m WHERE acnt_id = ?", firstPrepared.accountId)
            jdbc.update("DELETE FROM bcm_acnt_crtn_l WHERE acnt_id = ?", firstPrepared.accountId)
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `계정 공개 매핑 insert 뒤 원장 완료가 실패하면 두 변경을 함께 rollback한다`() {
        val prepared =
            reserveAndPrepareAccount(
                accountCreationIntent(
                    accountId = "acct_completion_rollback",
                    ref = "completion-rollback-ref",
                    idempotencyKey = "bcm-vlt-00000000000000000000000000000021",
                ),
            )
        val trigger = "trg_test_fail_acnt_completion"
        val function = "bcm_test_fail_acnt_completion"

        try {
            installCompletionFailureTrigger(
                table = "bcm_acnt_crtn_l",
                trigger = trigger,
                function = function,
                mappingExistsPredicate = "SELECT 1 FROM bcm_acnt_m WHERE acnt_id = NEW.acnt_id",
            )
            assertThatThrownBy {
                provisioning.completeAccount(prepared, "vault-rollback", "20260901000002")
            }.isInstanceOf(DataAccessException::class.java)
                .hasStackTraceContaining("forced completion failure after mapping insert")

            assertThat(accounts.findByTypeAndRef(AccountType.CUSTOMER, prepared.ref)).isNull()
            val unchanged = provisioning.findAccount(prepared.accountType, prepared.ref)
            assertThat(unchanged?.status).isEqualTo(CreationStatus.SUBMITTING)
            assertThat(unchanged?.vendorVaultId).isNull()
        } finally {
            removeCompletionFailureTrigger("bcm_acnt_crtn_l", trigger, function)
            jdbc.update("DELETE FROM bcm_acnt_m WHERE acnt_id = ?", prepared.accountId)
            jdbc.update("DELETE FROM bcm_acnt_crtn_l WHERE acnt_id = ?", prepared.accountId)
        }
    }

    @Test
    fun `유형이 다르면 같은 ref 도 별개 계정이다 — 접두사가 없어 코어 ID 공간이 겹칠 수 있다`() {
        val customer = accounts.insert(account(accountId = "acct_cu", accountType = AccountType.CUSTOMER, ref = "000123"))
        val system = accounts.insert(account(accountId = "acct_sy", accountType = AccountType.SYSTEM, ref = "000123"))

        assertThat(accounts.findByTypeAndRef(AccountType.CUSTOMER, "000123")).isEqualTo(customer)
        assertThat(accounts.findByTypeAndRef(AccountType.SYSTEM, "000123")).isEqualTo(system)
        assertThat(customer.accountId).isNotEqualTo(system.accountId)
    }

    private fun address(
        accountId: String = "acct_01",
        network: String = "ETHEREUM",
        symbol: String = "USDC",
        address: String = "0xA1b2C3",
    ) = DepositAddress(
        accountId = accountId,
        network = network,
        symbol = symbol,
        address = address,
        registeredAt = "20260805120000",
    )

    @Test
    fun `주소 매핑 왕복 — (계정, 네트워크, 심볼)과 역방향(주소) 조회가 같은 객체를 되찾는다`() {
        val saved = addresses.insert(address())
        assertThat(addresses.find("acct_01", "ETHEREUM", "USDC")).isEqualTo(saved)
        assertThat(addresses.findByAddress("0xA1b2C3", "ETHEREUM", "USDC")).isEqualTo(saved)
    }

    @Test
    fun `같은 심볼이라도 네트워크가 다르면 별개 주소다 — 여러 네트워크 발급의 물리 근거`() {
        val ethereum = addresses.insert(address(network = "ETHEREUM", address = "0xETH"))
        val polygon = addresses.insert(address(network = "POLYGON", address = "0xPOLY"))

        assertThat(addresses.find("acct_01", "ETHEREUM", "USDC")).isEqualTo(ethereum)
        assertThat(addresses.find("acct_01", "POLYGON", "USDC")).isEqualTo(polygon)
    }

    @Test
    fun `주소 목록 조회 — 필터 없으면 전체, symbol 으로 거르면 그 자산만`() {
        addresses.insert(address(network = "ETHEREUM", address = "0xETH"))
        addresses.insert(address(network = "BASE", address = "0xBASE"))
        addresses.insert(address(network = "BASE", symbol = "USDT", address = "0xUSDT"))

        assertThat(addresses.findAll("acct_01", null, null)).hasSize(3)
        assertThat(addresses.findAll("acct_01", "USDC", null)).hasSize(2)
        assertThat(addresses.findAll("acct_01", "USDC", "BASE")).hasSize(1)
        assertThat(addresses.findAll("acct_01", "NONE", null)).isEmpty()
    }

    @Test
    fun `주소 발급 멱등의 물리 근거 — 같은 (계정, 네트워크, 심볼) 재발급은 도메인 ConflictException 으로 변환된다`() {
        addresses.insert(address(address = "0xFIRST"))
        assertThatThrownBy { addresses.insert(address(address = "0xSECOND")) }
            .isInstanceOf(ConflictException::class.java)
            .hasRootCauseInstanceOf(java.sql.SQLException::class.java)
    }

    @Test
    fun `주소 생성 의도는 assetId를 고정하고 완료 시 주소 매핑과 한 트랜잭션으로 종결된다`() {
        accounts.insert(account(accountId = "acct_address_intent", ref = "address-intent-ref"))
        val candidate =
            DepositAddressCreationIntent(
                accountId = "acct_address_intent",
                network = "ETHEREUM",
                symbol = "USDC",
                vendorAssetId = "USDC_ERC20",
                idempotencyKey = "bcm-adr-00000000000000000000000000000001",
                idempotencyKeyRegisteredAt = "20260901000000",
                lastVendorCallPreparedAt = null,
                status = CreationStatus.PENDING,
                attemptCount = 0,
                address = null,
                registeredAt = "20260901000000",
                lastChangedAt = "20260901000000",
            )

        val reserved = provisioning.reserveAddress(candidate)
        val raced = provisioning.reserveAddress(candidate.copy(vendorAssetId = "CHANGED", idempotencyKey = "bcm-adr-loser"))
        val submitting =
            provisioning.beginAddressAttempt(
                candidate.accountId,
                candidate.network,
                candidate.symbol,
                "20260901000001",
            )
        val firstPrepared =
            provisioning.prepareAddressVendorCall(
                submitting,
                submitting.idempotencyKey,
                submitting.idempotencyKeyRegisteredAt,
                "20260901000001",
            )
        val superseding =
            provisioning.beginAddressAttempt(
                candidate.accountId,
                candidate.network,
                candidate.symbol,
                "20260902000000",
            )
        val prepared =
            provisioning.prepareAddressVendorCall(
                superseding,
                "bcm-adr-00000000000000000000000000000002",
                "20260902000000",
                "20260902000000",
            )
        assertThatThrownBy {
            provisioning.completeAddress(checkNotNull(firstPrepared), "0xSTALE", "20260902000001")
        }.isInstanceOf(CreationRetryLaterException::class.java)
        assertThat(addresses.find(candidate.accountId, candidate.network, candidate.symbol)).isNull()
        val completed =
            provisioning.completeAddress(
                checkNotNull(prepared),
                "0xRECOVERED",
                "20260902000001",
            )

        assertThat(raced).isEqualTo(reserved)
        assertThat(raced.vendorAssetId).isEqualTo("USDC_ERC20")
        assertThat(submitting.attemptCount).isEqualTo(1)
        assertThat(prepared.idempotencyKey).isEqualTo("bcm-adr-00000000000000000000000000000002")
        assertThat(prepared.idempotencyKeyRegisteredAt).isEqualTo("20260902000000")
        assertThat(completed.address).isEqualTo("0xRECOVERED")
        assertThat(addresses.find(candidate.accountId, candidate.network, candidate.symbol)).isEqualTo(completed)
        assertThat(provisioning.findAddress(candidate.accountId, candidate.network, candidate.symbol)?.status)
            .isEqualTo(CreationStatus.COMPLETED)
        assertThat(
            provisioning
                .beginAddressAttempt(
                    candidate.accountId,
                    candidate.network,
                    candidate.symbol,
                    "20260901000003",
                ).status,
        ).isEqualTo(CreationStatus.COMPLETED)
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `주소 새 키 세대 준비가 잠근 동안 이전 세대 완료는 기다린 뒤 매핑 없이 거절된다`() {
        val accountId = "acct_address_generation_race"
        accounts.insert(account(accountId = accountId, ref = "address-generation-race-ref"))
        val firstPrepared =
            reserveAndPrepareAddress(
                addressCreationIntent(
                    accountId = accountId,
                    idempotencyKey = "bcm-adr-00000000000000000000000000000011",
                ),
            )
        val rotated = CountDownLatch(1)
        val releaseRotation = CountDownLatch(1)
        val completionAttempted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val transaction = requiresNewTransaction()

        try {
            val rotation =
                executor.submit<DepositAddressCreationIntent> {
                    checkNotNull(
                        transaction.execute {
                            val superseding =
                                provisioning.beginAddressAttempt(
                                    firstPrepared.accountId,
                                    firstPrepared.network,
                                    firstPrepared.symbol,
                                    "20260902000000",
                                )
                            val prepared =
                                checkNotNull(
                                    provisioning.prepareAddressVendorCall(
                                        superseding,
                                        "bcm-adr-00000000000000000000000000000012",
                                        "20260902000000",
                                        "20260902000000",
                                    ),
                                )
                            rotated.countDown()
                            check(releaseRotation.await(5, TimeUnit.SECONDS))
                            prepared
                        },
                    )
                }
            assertThat(rotated.await(5, TimeUnit.SECONDS)).isTrue()

            val staleCompletion =
                executor.submit<Throwable?> {
                    completionAttempted.countDown()
                    runCatching {
                        provisioning.completeAddress(firstPrepared, "0xSTALE_RACE", "20260902000001")
                    }.exceptionOrNull()
                }
            assertThat(completionAttempted.await(5, TimeUnit.SECONDS)).isTrue()
            assertThatThrownBy { staleCompletion.get(300, TimeUnit.MILLISECONDS) }
                .isInstanceOf(TimeoutException::class.java)

            releaseRotation.countDown()
            val currentGeneration = rotation.get(5, TimeUnit.SECONDS)
            assertThat(staleCompletion.get(5, TimeUnit.SECONDS))
                .isInstanceOf(CreationRetryLaterException::class.java)
            assertThat(addresses.find(firstPrepared.accountId, firstPrepared.network, firstPrepared.symbol)).isNull()

            val completed = provisioning.completeAddress(currentGeneration, "0xCURRENT_RACE", "20260902000002")
            assertThat(completed.address).isEqualTo("0xCURRENT_RACE")
        } finally {
            releaseRotation.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            jdbc.update("DELETE FROM bcm_addr_m WHERE acnt_id = ?", accountId)
            jdbc.update("DELETE FROM bcm_addr_crtn_l WHERE acnt_id = ?", accountId)
            jdbc.update("DELETE FROM bcm_acnt_m WHERE acnt_id = ?", accountId)
        }
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    fun `주소 공개 매핑 insert 뒤 원장 완료가 실패하면 두 변경을 함께 rollback한다`() {
        val accountId = "acct_address_completion_rollback"
        accounts.insert(account(accountId = accountId, ref = "address-completion-rollback-ref"))
        val prepared =
            reserveAndPrepareAddress(
                addressCreationIntent(
                    accountId = accountId,
                    idempotencyKey = "bcm-adr-00000000000000000000000000000021",
                ),
            )
        val trigger = "trg_test_fail_addr_completion"
        val function = "bcm_test_fail_addr_completion"

        try {
            installCompletionFailureTrigger(
                table = "bcm_addr_crtn_l",
                trigger = trigger,
                function = function,
                mappingExistsPredicate =
                    "SELECT 1 FROM bcm_addr_m " +
                        "WHERE acnt_id = NEW.acnt_id AND ntwk_cd = NEW.ntwk_cd AND tkn_smbl = NEW.tkn_smbl",
            )
            assertThatThrownBy {
                provisioning.completeAddress(prepared, "0xROLLBACK", "20260901000002")
            }.isInstanceOf(DataAccessException::class.java)
                .hasStackTraceContaining("forced completion failure after mapping insert")

            assertThat(addresses.find(prepared.accountId, prepared.network, prepared.symbol)).isNull()
            val unchanged = provisioning.findAddress(prepared.accountId, prepared.network, prepared.symbol)
            assertThat(unchanged?.status).isEqualTo(CreationStatus.SUBMITTING)
            assertThat(unchanged?.address).isNull()
        } finally {
            removeCompletionFailureTrigger("bcm_addr_crtn_l", trigger, function)
            jdbc.update("DELETE FROM bcm_addr_m WHERE acnt_id = ?", accountId)
            jdbc.update("DELETE FROM bcm_addr_crtn_l WHERE acnt_id = ?", accountId)
            jdbc.update("DELETE FROM bcm_acnt_m WHERE acnt_id = ?", accountId)
        }
    }

    @Test
    fun `없는 매핑은 null — 귀속 불명 입금의 분기 근거`() {
        assertThat(addresses.findByAddress("0xUNKNOWN", "ETHEREUM", "USDC")).isNull()
        assertThat(accounts.findByTypeAndRef(AccountType.CUSTOMER, "000NONE")).isNull()
    }

    private fun accountCreationIntent(
        accountId: String,
        ref: String,
        idempotencyKey: String,
    ) = AccountCreationIntent(
        accountId = accountId,
        accountType = AccountType.CUSTOMER,
        ref = ref,
        vendorVaultName = "CUSTOMER:$ref",
        idempotencyKey = idempotencyKey,
        idempotencyKeyRegisteredAt = "20260901000000",
        lastVendorCallPreparedAt = null,
        status = CreationStatus.PENDING,
        attemptCount = 0,
        vendorVaultId = null,
        registeredAt = "20260901000000",
        lastChangedAt = "20260901000000",
    )

    private fun addressCreationIntent(
        accountId: String,
        idempotencyKey: String,
    ) = DepositAddressCreationIntent(
        accountId = accountId,
        network = "ETHEREUM",
        symbol = "USDC",
        vendorAssetId = "USDC_ERC20",
        idempotencyKey = idempotencyKey,
        idempotencyKeyRegisteredAt = "20260901000000",
        lastVendorCallPreparedAt = null,
        status = CreationStatus.PENDING,
        attemptCount = 0,
        address = null,
        registeredAt = "20260901000000",
        lastChangedAt = "20260901000000",
    )

    private fun reserveAndPrepareAccount(intent: AccountCreationIntent): AccountCreationIntent {
        val reserved = provisioning.reserveAccount(intent)
        val submitting = provisioning.beginAccountAttempt(reserved.accountId, "20260901000001")
        return checkNotNull(
            provisioning.prepareAccountVendorCall(
                submitting,
                submitting.idempotencyKey,
                submitting.idempotencyKeyRegisteredAt,
                "20260901000001",
            ),
        )
    }

    private fun reserveAndPrepareAddress(intent: DepositAddressCreationIntent): DepositAddressCreationIntent {
        val reserved = provisioning.reserveAddress(intent)
        val submitting =
            provisioning.beginAddressAttempt(
                reserved.accountId,
                reserved.network,
                reserved.symbol,
                "20260901000001",
            )
        return checkNotNull(
            provisioning.prepareAddressVendorCall(
                submitting,
                submitting.idempotencyKey,
                submitting.idempotencyKeyRegisteredAt,
                "20260901000001",
            ),
        )
    }

    private fun requiresNewTransaction(): TransactionTemplate =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        }

    private fun installCompletionFailureTrigger(
        table: String,
        trigger: String,
        function: String,
        mappingExistsPredicate: String,
    ) {
        removeCompletionFailureTrigger(table, trigger, function)
        jdbc.execute(
            """
            CREATE FUNCTION $function() RETURNS trigger
            LANGUAGE plpgsql
            AS ${'$'}function${'$'}
            BEGIN
              IF NOT EXISTS ($mappingExistsPredicate) THEN
                RAISE EXCEPTION 'mapping insert did not precede completion';
              END IF;
              RAISE EXCEPTION 'forced completion failure after mapping insert';
            END;
            ${'$'}function${'$'}
            """.trimIndent(),
        )
        jdbc.execute(
            """
            CREATE TRIGGER $trigger
            BEFORE UPDATE OF crtn_stcd ON $table
            FOR EACH ROW
            WHEN (NEW.crtn_stcd = 'COMPLETED')
            EXECUTE FUNCTION $function()
            """.trimIndent(),
        )
    }

    private fun removeCompletionFailureTrigger(
        table: String,
        trigger: String,
        function: String,
    ) {
        jdbc.execute("DROP TRIGGER IF EXISTS $trigger ON $table")
        jdbc.execute("DROP FUNCTION IF EXISTS $function()")
    }
}
