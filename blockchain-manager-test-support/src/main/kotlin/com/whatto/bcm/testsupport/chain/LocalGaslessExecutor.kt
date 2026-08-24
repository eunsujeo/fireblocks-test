package com.whatto.bcm.testsupport.chain

import org.web3j.crypto.AuthorizationTuple
import org.web3j.crypto.Credentials
import org.web3j.crypto.Hash
import org.web3j.crypto.RawTransaction
import org.web3j.crypto.Sign
import org.web3j.crypto.TransactionEncoder
import org.web3j.utils.Numeric
import java.math.BigInteger

internal class LocalGaslessExecutor(
    private val rpc: EvmJsonRpcClient,
    private val chainId: Long,
    private val feePayer: LocalEvmKey,
    private val delegationContractAddress: String,
    private val sourceKey: (String) -> LocalEvmKey,
) {
    constructor(node: AnvilNode, manifest: LocalChainManifest) :
        this(
            node.rpc,
            manifest.chainId,
            node.keyring.account(manifest.gaslessFeePayerAddress),
            manifest.gaslessDelegationContractAddress,
            node.keyring::account,
        )

    fun execute(
        sourceAddress: String,
        targetAddress: String,
        callData: String,
        value: BigInteger = BigInteger.ZERO,
        requestedIntentNonce: BigInteger? = null,
        deadline: BigInteger = MAX_DEADLINE,
    ): LocalTransactionReceipt {
        val source = sourceKey(sourceAddress)
        val delegated = rpc.code(source.address) == delegationCode()
        val intentNonce = requestedIntentNonce ?: intentNonce(source.address, delegated)
        val intentSignature = signIntent(source, targetAddress, callData, value, intentNonce, deadline)
        val executeData = encodeExecute(targetAddress, callData, value, intentNonce, deadline, intentSignature)
        val transaction =
            if (delegated) {
                RawTransaction.createTransaction(
                    chainId,
                    rpc.transactionCount(feePayer.address),
                    GAS_LIMIT,
                    source.address,
                    BigInteger.ZERO,
                    executeData,
                    MAX_PRIORITY_FEE_PER_GAS,
                    MAX_FEE_PER_GAS,
                )
            } else {
                val authorization =
                    AuthorizationTuple.from(
                        chainId,
                        delegationContractAddress,
                        rpc.transactionCount(source.address),
                        Credentials.create(source.privateKey),
                    )
                RawTransaction.createTransaction(
                    chainId,
                    rpc.transactionCount(feePayer.address),
                    MAX_PRIORITY_FEE_PER_GAS,
                    MAX_FEE_PER_GAS,
                    GAS_LIMIT,
                    source.address,
                    BigInteger.ZERO,
                    executeData,
                    emptyList(),
                    listOf(authorization),
                )
            }
        val signed =
            Numeric.toHexString(
                TransactionEncoder.signMessage(transaction, Credentials.create(feePayer.privateKey)),
            )
        val transactionHash = rpc.sendRawTransaction(signed)
        return rpc.waitForReceipt(transactionHash)
    }

    fun intentNonce(sourceAddress: String): BigInteger = intentNonce(sourceAddress, delegated = true)

    private fun intentNonce(
        sourceAddress: String,
        delegated: Boolean,
    ): BigInteger {
        if (!delegated) return BigInteger.ZERO
        val encoded = rpc.callContract(sourceAddress, EXECUTION_NONCE_SELECTOR)
        return BigInteger(encoded.removePrefix("0x").ifEmpty { "0" }, 16)
    }

    private fun delegationCode(): String = "0xef0100${delegationContractAddress.removePrefix("0x")}"

    private fun signIntent(
        source: LocalEvmKey,
        targetAddress: String,
        callData: String,
        value: BigInteger,
        intentNonce: BigInteger,
        deadline: BigInteger,
    ): ByteArray {
        val digest =
            Hash.sha3(
                INTENT_TAG +
                    word(BigInteger.valueOf(chainId)) +
                    addressWord(source.address) +
                    addressWord(targetAddress) +
                    word(value) +
                    Hash.sha3(Numeric.hexStringToByteArray(callData)) +
                    word(intentNonce) +
                    word(deadline),
            )
        val signature = Sign.signMessage(digest, Credentials.create(source.privateKey).ecKeyPair, false)
        return signature.r + signature.s + signature.v
    }

    private fun encodeExecute(
        targetAddress: String,
        callData: String,
        value: BigInteger,
        intentNonce: BigInteger,
        deadline: BigInteger,
        signature: ByteArray,
    ): String {
        val encodedData = dynamicBytes(Numeric.hexStringToByteArray(callData))
        val encodedSignature = dynamicBytes(signature)
        val head =
            addressWord(targetAddress) +
                word(value) +
                word(BigInteger.valueOf(HEAD_SIZE.toLong())) +
                word(intentNonce) +
                word(deadline) +
                word(BigInteger.valueOf((HEAD_SIZE + encodedData.size).toLong()))
        return EXECUTE_SELECTOR + Numeric.toHexStringNoPrefix(head + encodedData + encodedSignature)
    }

    private fun dynamicBytes(value: ByteArray): ByteArray {
        val paddedSize = ((value.size + WORD_SIZE - 1) / WORD_SIZE) * WORD_SIZE
        return word(BigInteger.valueOf(value.size.toLong())) + value.copyOf(paddedSize)
    }

    private fun addressWord(address: String): ByteArray = Numeric.toBytesPadded(BigInteger(address.removePrefix("0x"), 16), WORD_SIZE)

    private fun word(value: BigInteger): ByteArray = Numeric.toBytesPadded(value, WORD_SIZE)

    companion object {
        private const val WORD_SIZE = 32
        private const val HEAD_SIZE = 6 * WORD_SIZE
        private val INTENT_TAG = Hash.sha3("BCM_LOCAL_GASLESS_INTENT_V1".toByteArray())
        private val EXECUTE_SELECTOR = Hash.sha3String("execute(address,uint256,bytes,uint256,uint256,bytes)").take(10)
        private val EXECUTION_NONCE_SELECTOR = Hash.sha3String("executionNonce()").take(10)
        private val GAS_LIMIT = BigInteger.valueOf(500_000)
        private val MAX_PRIORITY_FEE_PER_GAS = BigInteger.valueOf(1_000_000_000)
        private val MAX_FEE_PER_GAS = BigInteger.valueOf(2_000_000_000)
        private val MAX_DEADLINE = BigInteger.valueOf(Long.MAX_VALUE)
    }
}

internal fun DeterministicEvmKeyring.account(address: String): LocalEvmKey =
    checkNotNull(accounts.find { it.address.equals(address, ignoreCase = true) }) {
        "local EVM key does not exist for address"
    }
