package com.whatto.bcm.testsupport.stub

import com.whatto.bcm.testsupport.chain.EvmJsonRpcClient
import com.whatto.bcm.testsupport.chain.LocalEvmKey
import com.whatto.bcm.testsupport.chain.LocalGaslessExecutor
import com.whatto.bcm.testsupport.config.TestSupportProperties
import com.whatto.bcm.testsupport.config.requireInternalLocalEndpoint
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
    val blockchainId: String,
    val chainId: Long,
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
    private val contexts by lazy(::loadContexts)
    private val resetLock = ReentrantLock()

    fun blockchains(): List<LocalStubBlockchain> =
        if (properties.localChainClusterManifestFile.isBlank()) {
            listOf(LocalStubBlockchain(LOCAL_BLOCKCHAIN_ID, "Local EVM", properties.evmChainId))
        } else {
            contexts.map { context ->
                LocalStubBlockchain(
                    id = context.manifest.blockchainId,
                    displayName = context.manifest.displayName,
                    chainId = context.manifest.chainId,
                )
            }
        }

    fun assets(): List<LocalStubAsset> = contexts.flatMap { it.manifest.assets }

    fun assets(blockchainId: String): List<LocalStubAsset> = assets().filter { it.blockchainId == blockchainId }

    fun asset(assetId: String): LocalStubAsset? = assets().find { it.id == assetId }

    fun vaultAddress(
        index: Int,
        asset: LocalStubAsset,
    ): String =
        context(asset).manifest.let { manifest -> (manifest.customerAddresses + manifest.operatorAddress).getOrNull(index) }
            ?: throw IllegalStateException("local chain has no deterministic address for vault index=$index")

    fun externalSourceAddress(asset: LocalStubAsset): String = context(asset).manifest.deployerAddress

    fun balance(
        address: String,
        asset: LocalStubAsset,
    ): String {
        val context = context(asset)
        val raw =
            if (asset.contractAddress == null) {
                context.rpc.nativeBalance(address)
            } else {
                context.rpc.tokenBalance(asset.contractAddress, address)
            }
        return decimalAmount(raw, asset.decimals)
    }

    fun setNativeBalance(
        address: String,
        balanceWei: BigInteger,
    ) = contexts.first().rpc.setNativeBalance(address, balanceWei)

    fun configureNextTransactionNonce(nonce: BigInteger) {
        require(nonce.signum() >= 0) { "transaction nonce must not be negative" }
        resetLock.withLock {
            val context = contexts.first()
            check(context.nextTransactionNonce == null) { "next transaction nonce is already configured" }
            context.nextTransactionNonce = nonce
        }
    }

    fun submitRawTransaction(
        asset: LocalStubAsset,
        sourceAddress: String,
        destinationAddress: String,
        rawAmount: BigInteger,
        callData: String,
        gasLimit: BigInteger,
    ): LocalStubTransactionResult {
        val context = context(asset)
        val normalizedSource = normalizedAddress(sourceAddress)
        val privateKey =
            context.privateKeysByAddress[normalizedSource]
                ?: throw IllegalStateException("local EVM key does not exist for source address")
        val transaction =
            RawTransaction.createTransaction(
                resetLock.withLock {
                    context.nextTransactionNonce.also { context.nextTransactionNonce = null }
                        ?: context.rpc.transactionCount(normalizedSource)
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
                    asset.chainId,
                    Credentials.create(privateKey),
                ),
            )
        val transactionHash = context.rpc.sendRawTransaction(signed)
        val receipt = context.rpc.waitForReceipt(transactionHash)
        return LocalStubTransactionResult(transactionHash, receipt.successful, receipt.blockNumber)
    }

    fun submitGaslessTransaction(
        asset: LocalStubAsset,
        sourceAddress: String,
        destinationAddress: String,
        rawAmount: BigInteger,
        callData: String,
    ): LocalStubTransactionResult {
        val context = context(asset)
        val executor =
            LocalGaslessExecutor(
                rpc = context.rpc,
                chainId = asset.chainId,
                feePayer = localKey(context, context.manifest.gaslessFeePayerAddress),
                delegationContractAddress = context.manifest.gaslessDelegationContractAddress,
                sourceKey = { address -> localKey(context, address) },
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
                asset = asset,
                sourceAddress = context(asset).manifest.deployerAddress,
                destinationAddress = destinationAddress,
                rawAmount = rawAmount,
                callData = "0x",
                gasLimit = NATIVE_TRANSFER_GAS_LIMIT,
            )
        } else {
            submitRawTransaction(
                asset = asset,
                sourceAddress = context(asset).manifest.deployerAddress,
                destinationAddress = tokenAddress,
                rawAmount = BigInteger.ZERO,
                callData = TRANSFER_SELECTOR + addressWord(destinationAddress) + uintWord(rawAmount),
                gasLimit = TOKEN_TRANSFER_GAS_LIMIT,
            )
        }
    }

    fun tokenTransfers(
        transactionHash: String,
        assetId: String,
    ): List<LocalStubTokenTransfer> {
        val transactionAsset = checkNotNull(asset(assetId)) { "local asset does not exist" }
        val context = context(transactionAsset)
        val assetsByContract =
            context.manifest.assets
                .filter { it.contractAddress != null }
                .associateBy { normalizedAddress(requireNotNull(it.contractAddress)) }
        return context.rpc
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

    fun blockNumber(assetId: String): BigInteger = context(checkNotNull(asset(assetId))).rpc.blockNumber()

    fun mineBlock(assetId: String) = context(checkNotNull(asset(assetId))).rpc.mineBlock()

    fun reset() {
        resetLock.withLock {
            contexts.forEach { context ->
                val snapshotId = checkNotNull(context.baselineSnapshotId) { "local environment reset is not enabled" }
                check(context.rpc.revert(snapshotId)) { "local chain baseline snapshot is no longer available" }
                context.baselineSnapshotId = context.rpc.snapshot()
                context.nextTransactionNonce = null
            }
        }
    }

    private fun loadContexts(): List<LocalStubChainContext> {
        if (properties.localChainClusterManifestFile.isBlank()) {
            return listOf(loadContext(properties.evmRpcUrl, properties.localChainManifestFile, properties.localChainKeyFile, true))
        }
        val clusterPath = Path.of(properties.localChainClusterManifestFile)
        val cluster =
            try {
                ObjectMapper().readTree(Files.readString(clusterPath))
            } catch (exception: Exception) {
                throw IllegalStateException("local chain cluster manifest is not readable: $clusterPath", exception)
            }
        check(cluster.required("schemaVersion").asInt() == 1) { "unsupported local chain cluster schema" }
        return cluster
            .required("chains")
            .iterator()
            .asSequence()
            .map { chain ->
                loadContext(
                    chain.required("rpcUrl").asString(),
                    chain.required("manifestFile").asString(),
                    chain.required("keyFile").asString(),
                    false,
                )
            }.toList()
            .also { contexts ->
                check(contexts.map { it.manifest.blockchainId }.distinct().size == contexts.size) { "duplicate local blockchain id" }
                check(
                    contexts
                        .flatMap { it.manifest.assets }
                        .map { it.id }
                        .distinct()
                        .size == contexts.flatMap { it.manifest.assets }.size,
                ) {
                    "duplicate local asset id"
                }
            }
    }

    private fun loadContext(
        rpcUrl: String,
        manifestFile: String,
        keyFile: String,
        includeNativeAsset: Boolean,
    ): LocalStubChainContext {
        requireInternalLocalEndpoint("cluster EVM RPC URL", rpcUrl)
        val rpc = EvmJsonRpcClient(rpcUrl)
        val manifestPath = Path.of(manifestFile)
        val document =
            try {
                ObjectMapper().readTree(Files.readString(manifestPath))
            } catch (exception: Exception) {
                throw IllegalStateException("local chain manifest is not readable: $manifestPath", exception)
            }
        val chainId = document.required("chainId").asLong()
        check(rpc.chainId() == chainId) {
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

        val blockchainId = document.get("blockchainId")?.asString() ?: LOCAL_BLOCKCHAIN_ID
        val displayName = document.get("displayName")?.asString() ?: "Local EVM"
        val assetDocuments =
            document
                .get("assets")
                ?.iterator()
                ?.asSequence()
                ?.toList()
        val manifestAssets =
            assetDocuments?.map { asset ->
                val contractAddress = asset.required("contractAddress").asString()
                check(rpc.codeHash(contractAddress) == asset.required("codeHash").asString()) {
                    "local token code hash does not match manifest: ${asset.required("id").asString()}"
                }
                LocalStubAsset(
                    id = asset.required("id").asString(),
                    blockchainId = blockchainId,
                    chainId = chainId,
                    displayName = asset.required("displayName").asString(),
                    displaySymbol = asset.required("symbol").asString(),
                    decimals = asset.required("decimals").asInt(),
                    assetClass = "ERC20",
                    contractAddress = contractAddress,
                )
            } ?: run {
                val contractAddress = document.required("tokenContractAddress").asString()
                check(rpc.codeHash(contractAddress) == document.required("tokenCodeHash").asString()) {
                    "local token code hash does not match manifest: $TOKEN_ASSET_ID"
                }
                listOf(
                    LocalStubAsset(
                        id = TOKEN_ASSET_ID,
                        blockchainId = blockchainId,
                        chainId = chainId,
                        displayName = "Local ${document.required("tokenSymbol").asString()}",
                        displaySymbol = document.required("tokenSymbol").asString(),
                        decimals = document.required("tokenDecimals").asInt(),
                        assetClass = "ERC20",
                        contractAddress = contractAddress,
                    ),
                )
            }
        val assets =
            if (includeNativeAsset) {
                listOf(
                    LocalStubAsset(
                        id = NATIVE_ASSET_ID,
                        blockchainId = blockchainId,
                        chainId = chainId,
                        displayName = "Local Ether",
                        displaySymbol = "ETH",
                        decimals = NATIVE_DECIMALS,
                        assetClass = "NATIVE",
                        contractAddress = null,
                    ),
                ) + manifestAssets
            } else {
                manifestAssets
            }
        val manifest =
            LocalStubManifest(
                blockchainId = blockchainId,
                displayName = displayName,
                chainId = chainId,
                deployerAddress = deployerAddress,
                operatorAddress = operatorAddress,
                gaslessFeePayerAddress = gaslessFeePayerAddress,
                gaslessDelegationContractAddress = gaslessDelegationContractAddress,
                customerAddresses = customerAddresses,
                assets = assets,
            )
        return LocalStubChainContext(
            rpc = rpc,
            manifest = manifest,
            privateKeysByAddress = loadPrivateKeys(manifest, keyFile),
            baselineSnapshotId = properties.resetEnabled.takeIf { it }?.let { rpc.snapshot() },
        )
    }

    private fun loadPrivateKeys(
        manifest: LocalStubManifest,
        keyFile: String,
    ): Map<String, String> {
        val keyPath = Path.of(keyFile)
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

    private fun localKey(
        context: LocalStubChainContext,
        address: String,
    ): LocalEvmKey {
        val normalized = normalizedAddress(address)
        return LocalEvmKey(
            address = normalized,
            privateKey = checkNotNull(context.privateKeysByAddress[normalized]) { "local EVM key does not exist for address" },
        )
    }

    private fun context(asset: LocalStubAsset): LocalStubChainContext = contexts.single { it.manifest.blockchainId == asset.blockchainId }

    private fun addressWord(address: String): String = normalizedAddress(address).removePrefix("0x").padStart(64, '0')

    private fun uintWord(value: BigInteger): String = value.toString(16).padStart(64, '0')

    private fun topicAddress(value: String): String {
        val raw = value.removePrefix("0x")
        require(raw.length == 64 && raw.take(24).all { it == '0' }) { "invalid EVM address topic" }
        return normalizedAddress(raw.takeLast(40))
    }

    private fun JsonNode.required(field: String): JsonNode = checkNotNull(get(field)) { "local chain manifest field is missing: $field" }

    private data class LocalStubManifest(
        val blockchainId: String,
        val displayName: String,
        val chainId: Long,
        val deployerAddress: String,
        val operatorAddress: String,
        val gaslessFeePayerAddress: String,
        val gaslessDelegationContractAddress: String,
        val customerAddresses: List<String>,
        val assets: List<LocalStubAsset>,
    )

    private data class LocalStubChainContext(
        val rpc: EvmJsonRpcClient,
        val manifest: LocalStubManifest,
        val privateKeysByAddress: Map<String, String>,
        var baselineSnapshotId: String?,
        var nextTransactionNonce: BigInteger? = null,
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

internal data class LocalStubBlockchain(
    val id: String,
    val displayName: String,
    val chainId: Long,
)
