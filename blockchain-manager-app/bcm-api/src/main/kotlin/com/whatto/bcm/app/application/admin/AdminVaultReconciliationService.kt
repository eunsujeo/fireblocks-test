package com.whatto.bcm.app.application.admin

import com.whatto.bcm.domain.admin.VaultReconciliation
import com.whatto.bcm.domain.admin.VaultReconciliationFailureException
import com.whatto.bcm.domain.admin.VaultReconciliationItem
import com.whatto.bcm.domain.admin.VaultReconciliationRepository
import com.whatto.bcm.domain.admin.VaultReconciliationStatus
import com.whatto.bcm.domain.exception.InvalidRequestException
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.vendor.WalletVendorPort
import com.whatto.bcm.support.time.CoreDateTimes
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.task.TaskExecutor
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.time.Clock
import java.util.Base64
import java.util.UUID

@Service
class AdminVaultReconciliationService(
    private val repository: VaultReconciliationRepository,
    private val walletVendor: WalletVendorPort,
    @param:Qualifier("applicationTaskExecutor") private val taskExecutor: TaskExecutor,
    private val clock: Clock,
    private val properties: VaultReconciliationProperties,
) {
    fun start(query: String?): VaultReconciliation {
        val now = CoreDateTimes.now(clock)
        val run =
            repository.create(
                VaultReconciliation(
                    runId = UUID.randomUUID().toString(),
                    query = query?.trim()?.takeIf(String::isNotEmpty),
                    status = VaultReconciliationStatus.ACCEPTED,
                    vendorPageCount = 0,
                    vendorVaultCount = 0,
                    resultCount = 0,
                    failureCode = null,
                    requestedAt = now,
                    startedAt = null,
                    finishedAt = null,
                ),
            )
        try {
            enqueue(run.runId)
        } catch (exception: RuntimeException) {
            repository.failAccepted(run.runId, "EXECUTOR_UNAVAILABLE", CoreDateTimes.now(clock))
            throw exception
        }
        return run
    }

    fun enqueue(runId: String) {
        taskExecutor.execute { reconcile(runId) }
    }

    fun resume() {
        repository.findResumable().forEach { enqueue(it.runId) }
    }

    fun result(
        runId: String,
        cursor: String?,
        limit: Int,
    ): AdminVaultReconciliationResult {
        if (limit !in 1..100) {
            throw InvalidRequestException("limit")
        }
        val page =
            repository.findPage(runId, AdminVaultCursor.decode(cursor), limit)
                ?: throw ResourceNotFoundException("vaultReconciliation", runId)
        return AdminVaultReconciliationResult(page.run, page.items, page.nextSequence?.let(AdminVaultCursor::encode))
    }

    private fun reconcile(runId: String) {
        val claimId = UUID.randomUUID().toString()
        try {
            if (!claim(runId, claimId)) return
            val now = CoreDateTimes.now(clock)
            if (!repository.startWithAccountSnapshot(runId, claimId, now)) return
            if (repository.hasDuplicateVendorVaultMapping(runId)) {
                repository.fail(runId, claimId, "ACCOUNT_VAULT_MAPPING_CONFLICT", CoreDateTimes.now(clock))
                return
            }

            val current = repository.find(runId) ?: return
            if (current.vendorPaginationDone) {
                repository.complete(runId, claimId, CoreDateTimes.now(clock))
                return
            }
            var cursor = current.vendorCursor
            val seen = mutableSetOf<String>()
            cursor?.let(seen::add)
            do {
                if (!claim(runId, claimId)) return
                val page = walletVendor.vaults(cursor)
                if (!repository.recordVendorPage(runId, claimId, cursor, page.next, page.data, CoreDateTimes.now(clock))) return
                val next = page.next
                if (next != null && !seen.add(next)) {
                    repository.fail(runId, claimId, "VENDOR_CURSOR_REPEATED", CoreDateTimes.now(clock))
                    return
                }
                cursor = next
            } while (cursor != null)
            repository.complete(runId, claimId, CoreDateTimes.now(clock))
        } catch (exception: Exception) {
            val failureCode =
                when (exception) {
                    is VendorApiException -> "VENDOR_UNAVAILABLE"
                    is VaultReconciliationFailureException -> exception.failureCode
                    else -> "RECONCILIATION_FAILED"
                }
            runCatching { repository.fail(runId, claimId, failureCode, CoreDateTimes.now(clock)) }
                .onFailure { failure -> log.error("Vault reconciliation failure could not be recorded runId={}", runId, failure) }
            log.warn("Vault reconciliation failed runId={} code={}", runId, failureCode, exception)
        }
    }

    private fun claim(
        runId: String,
        claimId: String,
    ): Boolean {
        val claimedAt = CoreDateTimes.current(clock)
        return repository.renewClaim(
            runId,
            claimId,
            CoreDateTimes.format(claimedAt),
            CoreDateTimes.format(claimedAt.plusSeconds(properties.claimTtlSeconds)),
        ) ||
            repository.claim(
                runId,
                claimId,
                CoreDateTimes.format(claimedAt),
                CoreDateTimes.format(claimedAt.plusSeconds(properties.claimTtlSeconds)),
            )
    }

    companion object {
        private val log = LoggerFactory.getLogger(AdminVaultReconciliationService::class.java)
    }
}

@ConfigurationProperties("bcm.vault-reconciliation")
data class VaultReconciliationProperties(
    val claimTtlSeconds: Long = 120,
) {
    init {
        require(claimTtlSeconds > 0) { "claimTtlSeconds must be positive" }
    }
}

data class AdminVaultReconciliationResult(
    val run: VaultReconciliation,
    val items: List<VaultReconciliationItem>,
    val nextCursor: String?,
)

object AdminVaultCursor {
    fun encode(sequence: Long): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(sequence.toString().toByteArray(StandardCharsets.US_ASCII))

    fun decode(cursor: String?): Long? =
        cursor?.let {
            runCatching {
                String(Base64.getUrlDecoder().decode(it), StandardCharsets.US_ASCII).toLong().takeIf { value -> value >= 0 }
            }.getOrNull() ?: throw InvalidRequestException("cursor")
        }
}
