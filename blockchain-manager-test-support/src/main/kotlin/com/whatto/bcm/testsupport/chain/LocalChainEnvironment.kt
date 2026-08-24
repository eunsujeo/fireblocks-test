package com.whatto.bcm.testsupport.chain

import java.io.Closeable
import java.math.BigInteger
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID

data class LocalChainConfiguration(
    val seed: String,
    val runtimeDirectory: Path,
    val contractArtifactDirectory: Path,
    val anvilBinary: String = "anvil",
    val rpcPort: Int = 0,
)

data class LocalSweepItem(
    val ownerAddress: String,
    val amount: BigInteger,
)

class LocalChainEnvironment private constructor(
    private val node: AnvilNode,
    val manifest: LocalChainManifest,
    private var baselineSnapshotId: String,
) : Closeable {
    val rpcUrl: String = node.rpcUrl
    private val rpc = node.rpc

    fun approve(
        ownerAddress: String,
        amount: BigInteger,
    ): String {
        require(amount.signum() >= 0) { "approval amount must not be negative" }
        val data = APPROVE_SELECTOR + addressWord(manifest.sweepContractAddress) + uintWord(amount)
        return confirmedTransaction(ownerAddress, manifest.tokenContractAddress, data)
    }

    fun batchSweep(
        executionId: String,
        items: List<LocalSweepItem>,
    ): String {
        require(items.isNotEmpty()) { "batch sweep must contain at least one item" }
        val normalizedItems = items.map { it.copy(ownerAddress = normalizedAddress(it.ownerAddress)) }
        require(normalizedItems.zipWithNext().all { (left, right) -> left.ownerAddress < right.ownerAddress }) {
            "batch sweep owners must be unique and sorted"
        }
        val encodedItems =
            normalizedItems.joinToString("") { item ->
                require(item.amount.signum() > 0) { "batch sweep amount must be positive" }
                addressWord(item.ownerAddress) + uintWord(item.amount)
            }
        val data =
            BATCH_SWEEP_SELECTOR +
                bytes16Word(executionId) +
                addressWord(manifest.tokenContractAddress) +
                uintWord(BigInteger.valueOf(96)) +
                uintWord(BigInteger.valueOf(items.size.toLong())) +
                encodedItems
        return confirmedTransaction(manifest.operatorAddress, manifest.sweepContractAddress, data)
    }

    fun waitForReceipt(transactionHash: String) {
        check(rpc.waitForReceipt(transactionHash).successful) { "EVM transaction reverted" }
    }

    fun transactionCount(address: String): BigInteger = rpc.transactionCount(address)

    fun nativeBalance(address: String): BigInteger = rpc.nativeBalance(address)

    fun setNativeBalance(
        address: String,
        balanceWei: BigInteger,
    ) = rpc.setNativeBalance(address, balanceWei)

    fun delegatedCode(address: String): String = rpc.code(address)

    fun gaslessApprove(
        ownerAddress: String,
        amount: BigInteger,
    ): String {
        val receipt = gaslessApproveAttempt(ownerAddress, amount)
        check(receipt.successful) { "local gasless approval reverted" }
        return receipt.transactionHash
    }

    internal fun gaslessApproveAttempt(
        ownerAddress: String,
        amount: BigInteger,
        intentNonce: BigInteger? = null,
        deadline: BigInteger = GASLESS_MAX_DEADLINE,
    ): LocalTransactionReceipt {
        require(amount.signum() >= 0) { "approval amount must not be negative" }
        val data = APPROVE_SELECTOR + addressWord(manifest.sweepContractAddress) + uintWord(amount)
        return LocalGaslessExecutor(node, manifest).execute(
            sourceAddress = ownerAddress,
            targetAddress = manifest.tokenContractAddress,
            callData = data,
            requestedIntentNonce = intentNonce,
            deadline = deadline,
        )
    }

    internal fun gaslessContractCallAttempt(
        ownerAddress: String,
        targetAddress: String,
        data: String,
        intentNonce: BigInteger? = null,
        deadline: BigInteger = GASLESS_MAX_DEADLINE,
    ): LocalTransactionReceipt =
        LocalGaslessExecutor(node, manifest).execute(
            sourceAddress = ownerAddress,
            targetAddress = targetAddress,
            callData = data,
            requestedIntentNonce = intentNonce,
            deadline = deadline,
        )

    internal fun gaslessIntentNonce(ownerAddress: String): BigInteger = LocalGaslessExecutor(node, manifest).intentNonce(ownerAddress)

    fun blockNumber(): BigInteger = rpc.blockNumber()

    fun mineBlock() = rpc.mineBlock()

    fun tokenBalance(address: String): BigInteger = uintCall(manifest.tokenContractAddress, BALANCE_OF_SELECTOR + addressWord(address))

    fun tokenAllowance(ownerAddress: String): BigInteger =
        uintCall(
            manifest.tokenContractAddress,
            ALLOWANCE_SELECTOR + addressWord(ownerAddress) + addressWord(manifest.sweepContractAddress),
        )

    fun reset() {
        check(rpc.revert(baselineSnapshotId)) { "local chain baseline snapshot is no longer available" }
        verifyManifest()
        baselineSnapshotId = rpc.snapshot()
    }

    override fun close() = node.close()

    private fun confirmedTransaction(
        from: String,
        to: String,
        data: String,
    ): String {
        val hash = rpc.sendTransaction(from, to, data)
        check(rpc.waitForReceipt(hash).successful) { "EVM RPC transaction reverted" }
        return hash
    }

    private fun uintCall(
        contractAddress: String,
        data: String,
    ): BigInteger {
        val result = rpc.callContract(contractAddress, data)
        require(result.startsWith("0x")) { "EVM contract result is not hex" }
        return BigInteger(result.removePrefix("0x").ifEmpty { "0" }, 16)
    }

    private fun verifyManifest() {
        check(rpc.chainId() == manifest.chainId) { "local chain id changed after reset" }
        check(rpc.codeHash(manifest.tokenContractAddress) == manifest.tokenCodeHash) { "token code hash changed after reset" }
        check(rpc.codeHash(manifest.sweepContractAddress) == manifest.sweepCodeHash) { "sweep code hash changed after reset" }
        check(rpc.codeHash(manifest.gaslessDelegationContractAddress) == manifest.gaslessDelegationCodeHash) {
            "gasless delegation code hash changed after reset"
        }
    }

    companion object {
        fun start(configuration: LocalChainConfiguration): LocalChainEnvironment {
            val node = AnvilNode.start(configuration)
            return try {
                bootstrap(node, configuration)
            } catch (exception: Exception) {
                node.close()
                throw exception
            }
        }

        private fun bootstrap(
            node: AnvilNode,
            configuration: LocalChainConfiguration,
        ): LocalChainEnvironment {
            val rpc = node.rpc
            val accounts = rpc.accounts()
            check(accounts.size >= REQUIRED_ACCOUNTS) { "Anvil did not expose enough deterministic accounts" }
            val deployer = accounts[0]
            val operator = accounts[1]
            val omnibus = accounts[2]
            val customers = accounts.subList(3, 5)
            val gaslessFeePayer = accounts[5]
            val tokenArtifact = ContractArtifact.load(configuration.contractArtifactDirectory, "TestToken", "TestToken")
            val tokenAddress = checkNotNull(rpc.deploy(deployer, tokenArtifact.creationBytecode).contractAddress)
            val sweepArtifact = ContractArtifact.load(configuration.contractArtifactDirectory, "BcmSweep", "BcmSweep")
            val sweepConstructor =
                addressWord(operator) +
                    addressWord(omnibus) +
                    addressWord(tokenAddress) +
                    uintWord(BigInteger.valueOf(MAXIMUM_ITEMS.toLong())) +
                    uintWord(MAXIMUM_ITEM_AMOUNT) +
                    uintWord(MAXIMUM_TOTAL_AMOUNT)
            val sweepAddress = checkNotNull(rpc.deploy(deployer, sweepArtifact.creationBytecode + sweepConstructor).contractAddress)
            val gaslessArtifact =
                ContractArtifact.load(configuration.contractArtifactDirectory, "LocalGaslessDelegation", "LocalGaslessDelegation")
            val gaslessDeployment =
                rpc.deploy(
                    deployer,
                    gaslessArtifact.creationBytecode + addressWord(gaslessFeePayer),
                )
            val gaslessDelegationAddress =
                checkNotNull(gaslessDeployment.contractAddress)
            (listOf(deployer) + customers).forEach { fundedAddress ->
                val mintData = MINT_SELECTOR + addressWord(fundedAddress) + uintWord(CUSTOMER_TOKEN_BALANCE)
                val receipt = rpc.waitForReceipt(rpc.sendTransaction(deployer, tokenAddress, mintData))
                check(receipt.successful) { "test token mint failed" }
            }
            val manifest =
                LocalChainManifest(
                    foundryVersion = FoundryToolchain.REQUIRED_VERSION,
                    chainId = rpc.chainId(),
                    deployerAddress = deployer,
                    operatorAddress = operator,
                    omnibusAddress = omnibus,
                    gaslessFeePayerAddress = gaslessFeePayer,
                    customerAddresses = customers,
                    tokenContractAddress = tokenAddress,
                    tokenCodeHash = rpc.codeHash(tokenAddress),
                    tokenSymbol = "TUSD",
                    tokenDecimals = TOKEN_DECIMALS,
                    sweepContractAddress = sweepAddress,
                    sweepCodeHash = rpc.codeHash(sweepAddress),
                    gaslessDelegationContractAddress = gaslessDelegationAddress,
                    gaslessDelegationCodeHash = rpc.codeHash(gaslessDelegationAddress),
                    maximumItems = MAXIMUM_ITEMS,
                    maximumItemAmount = MAXIMUM_ITEM_AMOUNT.toString(),
                    maximumTotalAmount = MAXIMUM_TOTAL_AMOUNT.toString(),
                )
            Files.writeString(configuration.runtimeDirectory.resolve("manifest.json"), manifest.toJson())
            node.keyring.write(configuration.runtimeDirectory.resolve("evm-keys.json"))
            return LocalChainEnvironment(node, manifest, rpc.snapshot()).also { it.verifyManifest() }
        }

        private fun bytes16Word(value: String): String {
            val uuid = UUID.fromString(value)
            require(uuid.toString() == value && uuid.version() == 7 && uuid.variant() == 2) {
                "batch executionId must be a canonical UUID v7"
            }
            return value.replace("-", "").padEnd(64, '0')
        }

        private fun addressWord(address: String): String = normalizedAddress(address).removePrefix("0x").padStart(64, '0')

        private fun normalizedAddress(address: String): String {
            val raw = address.removePrefix("0x")
            require(raw.length == 40 && raw.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) { "invalid EVM address" }
            return "0x${raw.lowercase()}"
        }

        private fun uintWord(value: BigInteger): String {
            require(value.signum() >= 0 && value.bitLength() <= 256) { "value exceeds uint256" }
            return value.toString(16).padStart(64, '0')
        }

        private const val APPROVE_SELECTOR = "0x095ea7b3"
        private const val ALLOWANCE_SELECTOR = "0xdd62ed3e"
        private const val BALANCE_OF_SELECTOR = "0x70a08231"
        private const val MINT_SELECTOR = "0x40c10f19"
        private const val BATCH_SWEEP_SELECTOR = "0x4209ef32"
        private const val REQUIRED_ACCOUNTS = 6
        private const val TOKEN_DECIMALS = 6
        private const val MAXIMUM_ITEMS = 10
        private val CUSTOMER_TOKEN_BALANCE = BigInteger("100000000")
        private val MAXIMUM_ITEM_AMOUNT = BigInteger("50000000")
        private val MAXIMUM_TOTAL_AMOUNT = BigInteger("100000000")
        private val GASLESS_MAX_DEADLINE = BigInteger.valueOf(Long.MAX_VALUE)
    }
}
