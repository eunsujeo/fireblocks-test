package com.whatto.bcm.app.application.account

import com.whatto.bcm.app.application.asset.VendorAssetMappingQueryService
import com.whatto.bcm.domain.account.Account
import com.whatto.bcm.domain.account.AccountCreationIntent
import com.whatto.bcm.domain.account.AccountRepository
import com.whatto.bcm.domain.account.AccountType
import com.whatto.bcm.domain.account.CreationStatus
import com.whatto.bcm.domain.account.DepositAddress
import com.whatto.bcm.domain.account.DepositAddressCreationIntent
import com.whatto.bcm.domain.account.DepositAddressRepository
import com.whatto.bcm.domain.account.VendorCallDecision
import com.whatto.bcm.domain.account.WalletProvisioningPolicy
import com.whatto.bcm.domain.account.WalletProvisioningRepository
import com.whatto.bcm.domain.asset.VendorAssetMapping
import com.whatto.bcm.domain.exception.AccountNotFoundException
import com.whatto.bcm.domain.exception.BcmException
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.CreationRetryLaterException
import com.whatto.bcm.domain.vendor.VendorDepositAddress
import com.whatto.bcm.domain.vendor.VendorVault
import com.whatto.bcm.domain.vendor.WalletVendorPort
import com.whatto.bcm.support.time.CoreDateTimes
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

/**
 * 계정·주소 오케스트레이션 — 멱등 규약(openapi info 절):
 * createAccount 는 (accountType, ref), createDepositAddress 는 (accountId, network, symbol). 로컬 생성 의도와
 * 40자 이하 벤더 멱등 키를 먼저 커밋하고 벤더 생성 뒤 공개 매핑과 원장을 원자 완료한다. 응답 유실·DB 실패 재시도는
 * 벤더 조회로 유일한 후보만 회수한다(02·03).
 *
 * ★ 계정 키에는 **유형이 반드시 들어간다** — 접두사가 없어 고객·시스템 ref 가 겹칠 수 있으므로,
 * 유형을 뺀 키를 쓰면 서로 다른 두 계정이 벤더 멱등 키를 공유해 **같은 vault 를 나눠 갖는다**.
 */
@Service
class AccountService(
    private val accountRepository: AccountRepository,
    private val depositAddressRepository: DepositAddressRepository,
    private val provisioningRepository: WalletProvisioningRepository,
    private val assetMappingQueryService: VendorAssetMappingQueryService,
    private val walletVendorPort: WalletVendorPort,
    private val provisioningPolicy: WalletProvisioningPolicy,
    private val clock: Clock,
) {
    fun createAccount(
        accountType: AccountType,
        ref: String,
    ): Account {
        accountRepository.findByTypeAndRef(accountType, ref)?.let { return it }

        val now = CoreDateTimes.now(clock)
        val accountKey = "$accountType:$ref"
        val intent =
            provisioningRepository.reserveAccount(
                AccountCreationIntent(
                    accountId = "acct_${UUID.randomUUID()}",
                    accountType = accountType,
                    ref = ref,
                    vendorVaultName = accountKey,
                    idempotencyKey = newIdempotencyKey("bcm-vlt-"),
                    idempotencyKeyRegisteredAt = now,
                    lastVendorCallPreparedAt = null,
                    status = CreationStatus.PENDING,
                    attemptCount = 0,
                    vendorVaultId = null,
                    registeredAt = now,
                    lastChangedAt = now,
                ),
            )
        if (intent.status == CreationStatus.COMPLETED) {
            return accountRepository.findByTypeAndRef(accountType, ref)
                ?: throw ConflictException("accountCreation", accountKey)
        }
        val submitting = provisioningRepository.beginAccountAttempt(intent.accountId, now)
        if (submitting.status == CreationStatus.COMPLETED) {
            return accountRepository.findByTypeAndRef(accountType, ref)
                ?: throw ConflictException("accountCreation", accountKey)
        }
        val recovered =
            if (submitting.attemptCount > 1) {
                recoverVault(submitting)
            } else {
                null
            }
        val (vault, completionGeneration) =
            recovered?.let { it to submitting }
                ?: prepareAccountVendorCall(submitting).let { prepared ->
                    walletVendorPort.createVault(
                        name = prepared.vendorVaultName,
                        idempotencyKey = prepared.idempotencyKey,
                    ) to prepared
                }
        if (vault.name != submitting.vendorVaultName) {
            throw ConflictException("vendorVaultName", submitting.accountId)
        }
        return provisioningRepository.completeAccount(completionGeneration, vault.vaultId, CoreDateTimes.now(clock))
    }

    private fun recoverVault(intent: AccountCreationIntent): VendorVault? {
        val candidates = linkedMapOf<String, VendorVault>()
        var cursor: String? = null
        val seenCursors = mutableSetOf<String>()
        do {
            val page = walletVendorPort.vaultsByName(intent.vendorVaultName, cursor)
            page.data
                .filter { it.name == intent.vendorVaultName }
                .forEach { candidate -> candidates[candidate.vaultId] = candidate }
            provisioningPolicy.uniqueVault(intent.accountId, candidates.values)
            cursor = page.next
            provisioningPolicy.requireFreshCursor("vendorVaultRecoveryCursor", intent.accountId, seenCursors, cursor)
            cursor?.let(seenCursors::add)
        } while (cursor != null)
        return provisioningPolicy.uniqueVault(intent.accountId, candidates.values)
    }

    private fun newIdempotencyKey(prefix: String): String = prefix + UUID.randomUUID().toString().replace("-", "")

    private fun prepareAccountVendorCall(intent: AccountCreationIntent): AccountCreationIntent {
        val now = CoreDateTimes.current(clock)
        val (key, registeredAt) =
            vendorCallKey(
                resourceKey = intent.accountId,
                prefix = "bcm-vlt-",
                currentKey = intent.idempotencyKey,
                keyRegisteredAt = intent.idempotencyKeyRegisteredAt,
                lastVendorCallPreparedAt = intent.lastVendorCallPreparedAt,
                now = now,
            )
        return provisioningRepository.prepareAccountVendorCall(intent, key, registeredAt, CoreDateTimes.format(now))
            ?: throw ConflictException("accountCreationAttempt", intent.accountId)
    }

    fun createDepositAddress(
        accountId: String,
        network: String,
        symbol: String,
    ): DepositAddress {
        val account = accountRepository.findByAccountId(accountId) ?: throw AccountNotFoundException(accountId)
        val mapping = assetMappingQueryService.requiredMapping(network, symbol)
        return issueDepositAddress(account, mapping)
    }

    private fun issueDepositAddress(
        account: Account,
        mapping: VendorAssetMapping,
    ): DepositAddress {
        val accountId = account.accountId
        val network = mapping.network
        val symbol = mapping.symbol
        depositAddressRepository.find(accountId, network, symbol)?.let { return it }

        val now = CoreDateTimes.now(clock)
        val intent =
            provisioningRepository.reserveAddress(
                DepositAddressCreationIntent(
                    accountId = accountId,
                    network = network,
                    symbol = symbol,
                    vendorAssetId = mapping.vendorAssetId,
                    idempotencyKey = newIdempotencyKey("bcm-adr-"),
                    idempotencyKeyRegisteredAt = now,
                    lastVendorCallPreparedAt = null,
                    status = CreationStatus.PENDING,
                    attemptCount = 0,
                    address = null,
                    registeredAt = now,
                    lastChangedAt = now,
                ),
            )
        if (intent.status == CreationStatus.COMPLETED) {
            return depositAddressRepository.find(accountId, network, symbol)
                ?: throw ConflictException("depositAddressCreation", "$accountId:$network:$symbol")
        }
        val submitting = provisioningRepository.beginAddressAttempt(accountId, network, symbol, now)
        if (submitting.status == CreationStatus.COMPLETED) {
            return depositAddressRepository.find(accountId, network, symbol)
                ?: throw ConflictException("depositAddressCreation", "$accountId:$network:$symbol")
        }
        val recovered =
            if (submitting.attemptCount > 1) {
                recoverDepositAddress(account.requireVendorVaultId(), submitting)
            } else {
                null
            }
        // 벤더 tag 는 03이 비보관(PLAN #21). assetId는 최초 생성 의도 snapshot을 재사용한다.
        val (vendorAddress, completionGeneration) =
            recovered?.let { it to submitting }
                ?: prepareAddressVendorCall(submitting).let { prepared ->
                    walletVendorPort.createDepositAddress(
                        vaultId = account.requireVendorVaultId(),
                        assetSymbol = prepared.vendorAssetId,
                        idempotencyKey = prepared.idempotencyKey,
                    ) to prepared
                }
        return provisioningRepository.completeAddress(
            completionGeneration,
            vendorAddress.address,
            CoreDateTimes.now(clock),
        )
    }

    private fun recoverDepositAddress(
        vendorVaultId: String,
        intent: DepositAddressCreationIntent,
    ): VendorDepositAddress? {
        val candidates = linkedMapOf<Pair<String, String?>, VendorDepositAddress>()
        var cursor: String? = null
        val seenCursors = mutableSetOf<String>()
        do {
            val page = walletVendorPort.depositAddresses(vendorVaultId, intent.vendorAssetId, cursor)
            page.data.forEach { candidate -> candidates[candidate.address to candidate.tag] = candidate }
            val resourceKey = "${intent.accountId}:${intent.network}:${intent.symbol}"
            provisioningPolicy.uniqueDepositAddress(resourceKey, candidates.values)
            cursor = page.next
            provisioningPolicy.requireFreshCursor("vendorDepositAddressRecoveryCursor", resourceKey, seenCursors, cursor)
            cursor?.let(seenCursors::add)
        } while (cursor != null)
        return provisioningPolicy.uniqueDepositAddress(
            "${intent.accountId}:${intent.network}:${intent.symbol}",
            candidates.values,
        )
    }

    private fun prepareAddressVendorCall(intent: DepositAddressCreationIntent): DepositAddressCreationIntent {
        val now = CoreDateTimes.current(clock)
        val (key, registeredAt) =
            vendorCallKey(
                resourceKey = "${intent.accountId}:${intent.network}:${intent.symbol}",
                prefix = "bcm-adr-",
                currentKey = intent.idempotencyKey,
                keyRegisteredAt = intent.idempotencyKeyRegisteredAt,
                lastVendorCallPreparedAt = intent.lastVendorCallPreparedAt,
                now = now,
            )
        return provisioningRepository.prepareAddressVendorCall(intent, key, registeredAt, CoreDateTimes.format(now))
            ?: throw ConflictException("depositAddressCreationAttempt", "${intent.accountId}:${intent.network}:${intent.symbol}")
    }

    private fun vendorCallKey(
        resourceKey: String,
        prefix: String,
        currentKey: String,
        keyRegisteredAt: String,
        lastVendorCallPreparedAt: String?,
        now: java.time.LocalDateTime,
    ): Pair<String, String> =
        when (
            val decision =
                provisioningPolicy.vendorCallDecision(
                    currentKey,
                    keyRegisteredAt,
                    lastVendorCallPreparedAt,
                    now,
                )
        ) {
            is VendorCallDecision.Reuse -> decision.idempotencyKey to decision.idempotencyKeyRegisteredAt
            VendorCallDecision.Rotate -> newIdempotencyKey(prefix) to CoreDateTimes.format(now)
            is VendorCallDecision.RetryLater -> throw CreationRetryLaterException(resourceKey, decision.retryAfterSeconds)
        }

    /**
     * 한 자산 심볼을 여러 네트워크로 발급 — 고객이 같은 자산을 여러 체인에서 받을 때 쓴다
     * (openapi createDepositAddresses). 네트워크마다 단건 발급과 결과가 같고 멱등하다.
     *
     * **계정이 없거나 매핑 하나라도 없으면 요청 전체가 발급 전에 실패한다.** 매핑 검증 뒤의 벤더 오류만
     * 네트워크별 실패로 남으며, 이때 성공한 네트워크는 저장한다.
     * 잡는 것은 도메인 예외(BcmException)뿐이고 예상 못 한 예외는 그대로 올려 보낸다 — 항목 오류로
     * 뭉개면 원인이 사라진다. 같은 네트워크가 두 번 오면 발급을 한 번만 하고 같은 결과를 담는다.
     *
     * 받을 네트워크를 정하는 것은 DAW-CORE 다 — 매니저는 네트워크를 스스로 채우지 않는다.
     * 순차 처리이고 네트워크마다 벤더를 한 번 부르므로 호출 수는 줄지 않는다(상한 20 = 지연의 상한).
     */
    fun createDepositAddresses(
        accountId: String,
        symbol: String,
        networks: List<String>,
    ): List<AddressOutcome> {
        val account = accountRepository.findByAccountId(accountId) ?: throw AccountNotFoundException(accountId)
        // 전체를 먼저 확인해 미지원 자산과 벤더 부분 실패를 구분한다.
        val mappings =
            networks.distinct().associateWith { network ->
                assetMappingQueryService.requiredMapping(network, symbol)
            }
        val issued = mutableMapOf<String, AddressOutcome>()
        return networks.map { network ->
            issued.getOrPut(network) {
                try {
                    AddressOutcome(
                        network = network,
                        symbol = symbol,
                        depositAddress = issueDepositAddress(account, checkNotNull(mappings[network])),
                        failure = null,
                    )
                } catch (exception: BcmException) {
                    AddressOutcome(network = network, symbol = symbol, depositAddress = null, failure = exception)
                }
            }
        }
    }

    /**
     * 자산별 vault 잔액 — 대사 재료이지 고객별 귀속 잔액이 아니다 (openapi balancesOf).
     *
     * 조회 대상은 **그 계정에 주소가 발급된 자산**이다. 매니저가 아는 자산 집합이 주소 매핑뿐이라,
     * 주소 없이 vault 에 들어온 자산은 목록에 나오지 않는다. 자산마다 벤더를 한 번 부른다.
     */
    fun balancesOf(
        accountId: String,
        network: String?,
        symbol: String?,
    ): List<AssetBalance> {
        val account = accountRepository.findByAccountId(accountId) ?: throw AccountNotFoundException(accountId)
        return depositAddressRepository.findAll(accountId, symbol, network).map {
            AssetBalance(
                network = it.network,
                symbol = it.symbol,
                balance =
                    walletVendorPort.balanceOf(
                        vaultId = account.requireVendorVaultId(),
                        assetSymbol = assetMappingQueryService.requiredMapping(it.network, it.symbol).vendorAssetId,
                    ),
            )
        }
    }

    /**
     * 발급된 주소 조회 — 계정 없음(404)과 미발급(빈 배열)을 구분한다. 조회는 주소를 만들지 않는다 (openapi).
     * symbol·network 는 선택 필터다 — 둘 다 없으면 그 계정의 전체.
     */
    fun depositAddressesOf(
        accountId: String,
        symbol: String?,
        network: String?,
    ): List<DepositAddress> {
        accountRepository.findByAccountId(accountId) ?: throw AccountNotFoundException(accountId)
        return depositAddressRepository.findAll(accountId, symbol, network)
    }
}
