package com.whatto.bcm.app.application.admin

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminContractRepository
import com.whatto.bcm.domain.admin.AdminContractVersion
import com.whatto.bcm.domain.admin.AdminRole
import com.whatto.bcm.domain.admin.EmergencyExternalControlCandidate
import com.whatto.bcm.domain.admin.EmergencyExternalControlEvaluator
import com.whatto.bcm.domain.admin.EmergencyExternalControlEvidence
import com.whatto.bcm.domain.admin.EmergencyExternalControlRepository
import com.whatto.bcm.domain.admin.EmergencyExternalControlVerificationPort
import com.whatto.bcm.domain.event.EventIdGenerator
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

@Service
class EmergencyExternalControlObservationService(
    private val contracts: AdminContractRepository,
    private val evidence: EmergencyExternalControlRepository,
    private val verification: EmergencyExternalControlVerificationPort,
    private val transactions: TransactionRunner,
    private val ids: EventIdGenerator,
    private val clock: Clock,
) {
    fun observe(command: ObserveEmergencyExternalControlsCommand): EmergencyExternalControlEvidence {
        validate(command)
        evidence.findByIdempotency(command.actor.employeeNo, command.idempotencyKey)?.let { existing ->
            if (existing.sameObservationAs(command)) return existing
            throw ConflictException("emergencyExternalControlIdempotency", command.idempotencyKey)
        }
        val version =
            contracts.findVersion(command.versionId)
                ?: throw ResourceNotFoundException("contractVersion", command.versionId)
        check(version.use == "SWEEP") { "emergency external control observation requires a SWEEP contract" }
        val now = now()
        val candidate = collect(version, now).copy(expectedOperatorSetHash = EMPTY_OPERATOR_SET_HASH)
        val evaluation = EmergencyExternalControlEvaluator.evaluate(candidate, now)
        val snapshotHash =
            sha256(
                candidate.snapshot(
                    network = version.network,
                    contractVersionId = version.versionId,
                    status = evaluation.status.name,
                    issues = evaluation.issues,
                ),
            )
        val newEvidence =
            EmergencyExternalControlEvidence(
                evidenceId = ids.nextId(),
                network = version.network,
                contractVersionId = version.versionId,
                snapshotHash = snapshotHash,
                candidate = candidate,
                evaluation = evaluation,
                reason = command.reason,
                workTicket = command.workTicket,
                idempotencyKey = command.idempotencyKey,
                registeredBy = command.actor,
            )
        return transactions.run {
            evidence.findByIdempotency(command.actor.employeeNo, command.idempotencyKey)?.let { existing ->
                if (existing.sameObservationAs(command)) return@run existing
                throw ConflictException("emergencyExternalControlIdempotency", command.idempotencyKey)
            }
            try {
                evidence.insert(newEvidence)
            } catch (conflict: ConflictException) {
                evidence.findByIdempotency(command.actor.employeeNo, command.idempotencyKey)?.let { existing ->
                    if (existing.sameObservationAs(command)) return@run existing
                }
                throw conflict
            }
        }
    }

    private fun collect(
        version: AdminContractVersion,
        now: Instant,
    ): EmergencyExternalControlCandidate =
        try {
            verification.collect(version, now)
        } catch (error: RuntimeException) {
            log.warn(
                "외부 통제 관찰 실패 versionId={} network={} exceptionType={}",
                version.versionId,
                version.network,
                error.javaClass.name,
            )
            unavailableCandidate(version, now, "EXTERNAL_CONTROL_SOURCE_ERROR")
        }

    private fun validate(command: ObserveEmergencyExternalControlsCommand) {
        check(AdminRole.BCM_OPERATOR in command.actor.roles) { "BCM_OPERATOR role is required" }
        require(command.reason.isNotBlank()) { "external control observation reason is required" }
        require(command.workTicket.isNotBlank()) { "external control observation work ticket is required" }
        require(command.idempotencyKey.isNotBlank()) { "external control observation idempotency key is required" }
    }

    private fun EmergencyExternalControlEvidence.sameObservationAs(command: ObserveEmergencyExternalControlsCommand): Boolean =
        contractVersionId == command.versionId &&
            reason == command.reason &&
            workTicket == command.workTicket

    private fun EmergencyExternalControlCandidate.snapshot(
        network: String,
        contractVersionId: String,
        status: String,
        issues: List<String>,
    ): String =
        listOf(
            network,
            contractVersionId,
            tapSourceId,
            tap?.blocked,
            tap?.observedAt,
            pinnedBlockNumber,
            expectedOperatorSetHash,
            firstEndpointId,
            first?.blockNumber,
            first?.paused,
            first?.operatorSetHash,
            first?.observedAt,
            secondEndpointId,
            second?.blockNumber,
            second?.paused,
            second?.operatorSetHash,
            second?.observedAt,
            sourceErrors.joinToString(","),
            observedAt,
            validUntil,
            status,
            issues.joinToString(","),
        ).joinToString("|")

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.SECONDS)

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private val log = LoggerFactory.getLogger(EmergencyExternalControlObservationService::class.java)
        const val EMPTY_OPERATOR_SET_HASH = "4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945"

        fun unavailableCandidate(
            version: AdminContractVersion,
            now: Instant,
            issue: String,
        ) = EmergencyExternalControlCandidate(
            tapSourceId = "UNCONFIGURED_TAP",
            tap = null,
            pinnedBlockNumber = version.deploymentBlockNumber,
            expectedOperatorSetHash = EMPTY_OPERATOR_SET_HASH,
            firstEndpointId = "UNCONFIGURED_RPC_1",
            first = null,
            secondEndpointId = "UNCONFIGURED_RPC_2",
            second = null,
            sourceErrors = listOf(issue),
            observedAt = now,
            validUntil = now.plus(Duration.ofMinutes(5)),
        )
    }
}

data class ObserveEmergencyExternalControlsCommand(
    val versionId: String,
    val reason: String,
    val workTicket: String,
    val idempotencyKey: String,
    val actor: AdminActor,
)

@Configuration
class EmergencyExternalControlVerificationFallbackConfig {
    @Bean
    @ConditionalOnMissingBean(EmergencyExternalControlVerificationPort::class)
    fun unavailableEmergencyExternalControlVerification(): EmergencyExternalControlVerificationPort =
        EmergencyExternalControlVerificationPort { version, now ->
            EmergencyExternalControlObservationService.unavailableCandidate(
                version,
                now,
                "EXTERNAL_CONTROL_SOURCE_UNCONFIGURED",
            )
        }
}
