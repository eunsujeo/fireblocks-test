package com.whatto.bcm.testsupport.stub

import com.whatto.bcm.testsupport.chain.EvmJsonRpcClient
import com.whatto.bcm.testsupport.chain.LocalEvmKey
import com.whatto.bcm.testsupport.chain.LocalGaslessExecutor
import com.whatto.bcm.testsupport.config.TestSupportProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.web3j.crypto.Credentials
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.TransactionEncoder
import org.web3j.utils.Numeric
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal data class LocalStubAsset(
    val id: String,
    val displayName: String,
    val displaySymbol: String,
    val decimals: Int,
    val assetClass: String,
    val contractAddress: String?,
)

internal data class LocalStubTransactionResult(
    val transactionHash: String,
    val successful: Boolean,
    val blockNumber: BigInteger,
)

internal data class LocalStubTokenTransfer(
    val assetId: String,
    val sourceAddress: String,
    val destinationAddress: String,
    val amount: String,
)

@Component
@ConditionalOnProperty(prefix = "bcm.test-support", name = ["vendor-mode"], havingValue = "STUB", matchIfMissing = true)
internal class LocalStubChainState(
    private val properties: TestSupportProperties,
) {
    private val rpc = EvmJsonRpcClient(properties.evmRpcUrl)
    private val manifest by lazy(::loadManifest)
    private val privateKeysByAddress by lazy(::loadPrivateKeys)
    private val resetLock = ReentrantLock()
    private var baselineSnapshotId = properties.resetEnabled.takeIf { it }?.let { rpc.snapshot() }
    private var nextTransactionNonce: BigInteger? = null

    fun assets(): List<LocalStubAsset> = manifest.assets

    fun asset(assetId: String): LocalStubAsset? = assets().find { it.id == assetId }

    fun vaultAddress(index: Int): String =
        (manifest.customerAddresses + manifest.operatorAddress).getOrNull(index)
            ?: throw IllegalStateException("local chain has no deterministic address for vault index=$index")

    fun externalSourceAddress(): String = manifest.deployerAddress

    fun balance(
        address: String,
        asset: LocalStubAsset,
    ): String {
        val raw =
            if (asset.contractAddress == null) {
                rpc.nativeBalance(address)
            } else {
                rpc.tokenBalance(asset.contractAddress, address)
            }
        return decimalAmount(raw, asset.decimals)
    }

    fun setNativeBalance(
        address: String,
        balanceWei: BigInteger,
    ) = rpc.setNativeBalance(address, balanceWei)

    fun configureNextTransactionNonce(nonce: BigInteger) {
        require(nonce.signum() >= 0) { "transaction nonce must not be negative" }
        resetLock.withLock {
            check(nextTransactionNonce == null) { "next transaction nonce is already configured" }
            nextTransactionNonce = nonce
        }
    }

    fun submitRawTransaction(
        sourceAddress: String,
        destinationAddress: String,
        rawAmount: BigInteger,
        callData: String,
        gasLimit: BigInteger,
    ): LocalStubTransactionResult {
        val normalizedSource = normalizedAddress(sourceAddress)
        val privateKey =
            privateKeysByAddress[normalizedSource]
                ?: throw IllegalStateException("local EVM key does not exist for source address")
        val transaction =
            RawTransaction.createTransaction(
                resetLock.withLock {
                    nextTransactionNonce.also { nextTransactionNonce = null } ?: rpc.transactionCount(normalizedSource)
                },
                GAS_PRICE_WEI,
                gasLimit,
                normalizedAddress(destinationAddress),
                rawAmount,
                normalizedHex(callData),
            )
        val signed =
            Numeric.toHexString(
                TransactionEncoder.signMessage(
                    transaction,
                    properties.evmChainId,
                    Credentials.create(privateKey),
                ),
            )
        val transactionHash = rpc.sendRawTransaction(signed)
        val receipt = rpc.waitForReceipt(transactionHash)
        return LocalStubTransactionResult(transactionHash, receipt.successful, receipt.blockNumber)
    }

    fun submitGaslessTransaction(
        sourceAddress: String,
        destinationAddress: String,
        rawAmount: BigInteger,
        callData: String,
    ): LocalStubTransactionResult {
        val executor =
            LocalGaslessExecutor(
                rpc = rpc,
                chainId = properties.evmChainId,
                feePayer = localKey(manifest.gaslessFeePayerAddress),
                delegationContractAddress = manifest.gaslessDelegationContractAddress,
                sourceKey = ::localKey,
            )
        val receipt = executor.execute(sourceAddress, destinationAddress, callData, rawAmount)
        return LocalStubTransactionResult(receipt.transactionHash, receipt.successful, receipt.blockNumber)
    }

    fun submitExternalTransfer(
        destinationAddress: String,
        asset: LocalStubAsset,
        rawAmount: BigInteger,
    ): LocalStubTransactionResult {
        val tokenAddress = asset.contractAddress
        return if (tokenAddress == null) {
            submitRawTransaction(
                sourceAddress = manifest.deployerAddress,
                destinationAddress = destinationAddress,
                rawAmount = rawAmount,
                callData = "0x",
                gasLimit = NATIVE_TRANSFER_GAS_LIMIT,
            )
        } else {
            submitRawTransaction(
                sourceAddress = manifest.deployerAddress,
                destinationAddress = tokenAddress,
                rawAmount = BigInteger.ZERO,
                callData = TRANSFER_SELECTOR + addressWord(destinationAddress) + uintWord(rawAmount),
                gasLimit = TOKEN_TRANSFER_GAS_LIMIT,
            )
        }
    }

    fun tokenTransfers(transactionHash: String): List<LocalStubTokenTransfer> {
        val assetsByContract =
            assets()
                .filter { it.contractAddress != null }
                .associateBy { normalizedAddress(requireNotNull(it.contractAddress)) }
        return rpc
            .waitForReceipt(transactionHash)
            .logs
            .asSequence()
            .filter { log -> log.topics.firstOrNull() == TRANSFER_TOPIC && log.topics.size == 3 }
            .mapNotNull { log -> assetsByContract[log.address]?.let { asset -> log to asset } }
            .map { (log, asset) ->
                val rawAmount = BigInteger(log.data.removePrefix("0x"), 16)
                LocalStubTokenTransfer(
                    assetId = asset.id,
                    sourceAddress = topicAddress(log.topics[1]),
                    destinationAddress = topicAddress(log.topics[2]),
                    amount = decimalAmount(rawAmount, asset.decimals),
                )
            }.toList()
    }

    fun blockNumber(): BigInteger = rpc.blockNumber()

    fun mineBlock() = rpc.mineBlock()

    fun reset() {
        resetLock.withLock {
            val snapshotId = checkNotNull(baselineSnapshotId) { "local environment reset is not enabled" }
            check(rpc.revert(snapshotId)) { "local chain baseline snapshot is no longer available" }
            baselineSnapshotId = rpc.snapshot()
            nextTransactionNonce = null
        }
    }

    private fun loadManifest(): LocalStubManifest {
        val manifestPath = Path.of(properties.localChainManifestFile)
        val document =
            try {
                ObjectMapper().readTree(Files.readString(manifestPath))
            } catch (exception: Exception) {
                throw IllegalStateException("local chain manifest is not readable: $manifestPath", exception)
            }
        val chainId = document.required("chainId").asLong()
        check(chainId == properties.evmChainId && rpc.chainId() == chainId) {
            "local chain manifest and RPC chain id mismatch"
        }
        val deployerAddress = document.required("deployerAddress").asString()
        val operatorAddress = document.required("operatorAddress").asString()
        val gaslessFeePayerAddress = document.required("gaslessFeePayerAddress").asString()
        val gaslessDelegationContractAddress = document.required("gaslessDelegationContractAddress").asString()
        val customerAddresses =
            document
                .required("customerAddresses")
                .iterator()
                .asSequence()
                .map { it.asString() }
                .toList()
        check(customerAddresses.isNotEmpty()) { "local chain manifest has no customer addresses" }
        val rpcAccounts = rpc.accounts().toSet()
        check((listOf(deployerAddress, operatorAddress, gaslessFeePayerAddress) + customerAddresses).all { it in rpcAccounts }) {
            "local chain manifest addresses do not belong to RPC"
        }

        val tokenSymbol = document.required("tokenSymbol").asString()
        val tokenDecimals = document.required("tokenDecimals").asInt()
        val tokenContractAddress = document.required("tokenContractAddress").asString()
        return LocalStubManifest(
            deployerAddress = deployerAddress,
            operatorAddress = operatorAddress,
            gaslessFeePayerAddress = gaslessFeePayerAddress,
            gaslessDelegationContractAddress = gaslessDelegationContractAddress,
            customerAddresses = customerAddresses,
            assets =
                listOf(
                    LocalStubAsset(
                        id = NATIVE_ASSET_ID,
                        displayName = "Local Ether",
                        displaySymbol = "ETH",
                        decimals = NATIVE_DECIMALS,
                        assetClass = "NATIVE",
                        contractAddress = null,
                    ),
                    LocalStubAsset(
                        id = TOKEN_ASSET_ID,
                        displayName = "Local $tokenSymbol",
                        displaySymbol = tokenSymbol,
                        decimals = tokenDecimals,
                        assetClass = "ERC20",
                        contractAddress = tokenContractAddress,
                    ),
                ),
        )
    }

    private fun loadPrivateKeys(): Map<String, String> {
        val keyPath = Path.of(properties.localChainKeyFile)
        val document =
            try {
                ObjectMapper().readTree(Files.readString(keyPath))
            } catch (exception: Exception) {
                throw IllegalStateException("local EVM key file is not readable: $keyPath", exception)
            }
        check(document.required("schemaVersion").asInt() == 1) { "unsupported local EVM key schema" }
        val keys =
            document
                .required("accounts")
                .iterator()
                .asSequence()
                .associate { account ->
                    normalizedAddress(account.required("address").asString()) to
                        account.required("privateKey").asString()
                }
        check(
            (
                listOf(manifest.deployerAddress, manifest.operatorAddress, manifest.gaslessFeePayerAddress) +
                    manifest.customerAddresses
            ).all {
                normalizedAddress(it) in keys
            },
        ) {
            "local EVM key file does not contain every managed address"
        }
        return keys
    }

    private fun decimalAmount(
        raw: BigInteger,
        decimals: Int,
    ): String = BigDecimal(raw, decimals).stripTrailingZeros().toPlainString()

    private fun normalizedAddress(value: String): String {
        val raw = value.removePrefix("0x")
        require(raw.length == 40 && raw.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "invalid EVM address" }
        return "0x${raw.lowercase()}"
    }

    private fun normalizedHex(value: String): String {
        require(value.startsWith("0x") && value.length % 2 == 0) { "invalid EVM hex" }
        require(value.drop(2).all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "invalid EVM hex" }
        return value.lowercase()
    }

    private fun localKey(address: String): LocalEvmKey {
        val normalized = normalizedAddress(address)
        return LocalEvmKey(
            address = normalized,
            privateKey = checkNotNull(privateKeysByAddress[normalized]) { "local EVM key does not exist for address" },
        )
    }

    private fun addressWord(address: String): String = normalizedAddress(address).removePrefix("0x").padStart(64, '0')

    private fun uintWord(value: BigInteger): String = value.toString(16).padStart(64, '0')

    private fun topicAddress(value: String): String {
        val raw = value.removePrefix("0x")
        require(raw.length == 64 && raw.take(24).all { it == '0' }) { "invalid EVM address topic" }
        return normalizedAddress(raw.takeLast(40))
    }

    private fun JsonNode.required(field: String): JsonNode = checkNotNull(get(field)) { "local chain manifest field is missing: $field" }

    private data class LocalStubManifest(
        val deployerAddress: String,
        val operatorAddress: String,
        val gaslessFeePayerAddress: String,
        val gaslessDelegationContractAddress: String,
        val customerAddresses: List<String>,
        val assets: List<LocalStubAsset>,
    )

    companion object {
        const val LOCAL_BLOCKCHAIN_ID = "local-evm"
        const val NATIVE_ASSET_ID = "ETH_LOCAL"
        const val TOKEN_ASSET_ID = "TUSD_LOCAL"
        private const val NATIVE_DECIMALS = 18
        private const val TRANSFER_SELECTOR = "0xa9059cbb"
        private const val TRANSFER_TOPIC = "0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef"
        private val GAS_PRICE_WEI = BigInteger("1000000000")
        private val NATIVE_TRANSFER_GAS_LIMIT = BigInteger.valueOf(21_000)
        private val TOKEN_TRANSFER_GAS_LIMIT = BigInteger.valueOf(100_000)
    }
}
