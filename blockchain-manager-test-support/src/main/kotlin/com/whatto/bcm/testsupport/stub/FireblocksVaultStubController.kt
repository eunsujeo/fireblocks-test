package com.whatto.bcm.testsupport.stub

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

@RestController
@ConditionalOnProperty(prefix = "bcm.test-support", name = ["vendor-mode"], havingValue = "STUB", matchIfMissing = true)
internal class FireblocksVaultStubController(
    private val state: FireblocksVaultState,
) {
    @PostMapping("/v1/vault/accounts")
    fun createVault(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody request: CreateVaultRequest,
    ): VaultAccountResponse = state.createVault(request.name, idempotencyKey)

    @PostMapping("/v1/vault/accounts/{vaultId}/{assetId}")
    fun createWallet(
        @PathVariable vaultId: String,
        @PathVariable assetId: String,
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
    ): CreateVaultAssetResponse = state.createWallet(vaultId, assetId, idempotencyKey)

    @GetMapping("/v1/vault/accounts/{vaultId}/{assetId}")
    fun balance(
        @PathVariable vaultId: String,
        @PathVariable assetId: String,
    ): VaultAssetResponse = state.balance(vaultId, assetId)
}

@Component
@ConditionalOnProperty(prefix = "bcm.test-support", name = ["vendor-mode"], havingValue = "STUB", matchIfMissing = true)
internal class FireblocksVaultState(
    private val chain: LocalStubChainState,
) {
    private val lock = ReentrantLock()
    private val vaults = linkedMapOf<String, VaultRecord>()
    private val vaultRequests = mutableMapOf<String, VaultRequestRecord>()
    private val walletRequests = mutableMapOf<String, WalletRequestRecord>()
    private val wallets = mutableMapOf<Pair<String, String>, CreateVaultAssetResponse>()

    fun createVault(
        name: String,
        idempotencyKey: String,
    ): VaultAccountResponse =
        lock.withLock {
            require(name.isNotBlank()) { "vault name must not be blank" }
            require(idempotencyKey.isNotBlank()) { "idempotency key must not be blank" }
            vaultRequests[idempotencyKey]?.let { previous ->
                if (previous.name != name) conflict("idempotency key was already used for another vault request")
                return@withLock previous.response
            }
            val id = (vaults.size + 1).toString()
            val response = VaultAccountResponse(id = id, name = name)
            vaults[id] = VaultRecord(id = id, addressIndex = vaults.size)
            vaultRequests[idempotencyKey] = VaultRequestRecord(name, response)
            response
        }

    fun createWallet(
        vaultId: String,
        assetId: String,
        idempotencyKey: String,
    ): CreateVaultAssetResponse =
        lock.withLock {
            val vault = vault(vaultId)
            chain.asset(assetId) ?: notFound("local asset does not exist")
            val requestKey = vaultId to assetId
            walletRequests[idempotencyKey]?.let { previous ->
                if (previous.requestKey != requestKey) conflict("idempotency key was already used for another wallet request")
                return@withLock previous.response
            }
            val response =
                wallets.getOrPut(requestKey) {
                    CreateVaultAssetResponse(
                        id = assetId,
                        address = chain.vaultAddress(vault.addressIndex),
                    )
                }
            walletRequests[idempotencyKey] = WalletRequestRecord(requestKey, response)
            response
        }

    fun balance(
        vaultId: String,
        assetId: String,
    ): VaultAssetResponse {
        val (wallet, asset) =
            lock.withLock {
                vault(vaultId)
                val asset = chain.asset(assetId) ?: notFound("local asset does not exist")
                val wallet = wallets[vaultId to assetId] ?: notFound("vault asset wallet does not exist")
                wallet to asset
            }
        val amount = chain.balance(wallet.address, asset)
        return VaultAssetResponse(
            id = assetId,
            total = amount,
            available = amount,
            pending = "0",
            frozen = "0",
            lockedAmount = "0",
        )
    }

    fun walletAddress(
        vaultId: String,
        assetId: String,
    ): String =
        lock.withLock {
            vault(vaultId)
            wallets[vaultId to assetId]?.address ?: notFound("vault asset wallet does not exist")
        }

    fun vaultIdByAddress(
        address: String,
        assetId: String,
    ): String? =
        lock.withLock {
            wallets.entries
                .firstOrNull { (key, wallet) -> key.second == assetId && wallet.address.equals(address, ignoreCase = true) }
                ?.key
                ?.first
        }

    fun reset() {
        lock.withLock {
            vaults.clear()
            vaultRequests.clear()
            walletRequests.clear()
            wallets.clear()
        }
    }

    private fun vault(vaultId: String): VaultRecord = vaults[vaultId] ?: notFound("vault does not exist")

    private fun notFound(message: String): Nothing = throw ResponseStatusException(HttpStatus.NOT_FOUND, message)

    private fun conflict(message: String): Nothing = throw ResponseStatusException(HttpStatus.CONFLICT, message)

    private data class VaultRecord(
        val id: String,
        val addressIndex: Int,
    )

    private data class VaultRequestRecord(
        val name: String,
        val response: VaultAccountResponse,
    )

    private data class WalletRequestRecord(
        val requestKey: Pair<String, String>,
        val response: CreateVaultAssetResponse,
    )
}

internal data class CreateVaultRequest(
    val name: String,
)

internal data class VaultAccountResponse(
    val id: String,
    val name: String,
)

internal data class CreateVaultAssetResponse(
    val id: String,
    val address: String,
    val tag: String? = null,
)

internal data class VaultAssetResponse(
    val id: String,
    val total: String,
    val available: String,
    val pending: String,
    val frozen: String,
    val lockedAmount: String,
)
