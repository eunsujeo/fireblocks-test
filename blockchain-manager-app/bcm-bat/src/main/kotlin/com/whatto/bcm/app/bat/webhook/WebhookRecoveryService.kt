package com.whatto.bcm.app.bat.webhook

import com.whatto.bcm.domain.TransactionRunner
import com.whatto.bcm.domain.admin.AdminActor
import com.whatto.bcm.domain.admin.AdminRole
import com.whatto.bcm.domain.admin.WebhookRecoveryCallType
import com.whatto.bcm.domain.admin.WebhookRecoveryEvent
import com.whatto.bcm.domain.admin.WebhookRecoveryEventStatus
import com.whatto.bcm.domain.admin.WebhookRecoveryRepository
import com.whatto.bcm.domain.admin.WebhookRecoveryRequest
import com.whatto.bcm.domain.admin.WebhookRecoveryScope
import com.whatto.bcm.domain.admin.WebhookRecoveryState
import com.whatto.bcm.domain.admin.WebhookRecoveryView
import com.whatto.bcm.domain.exception.ConflictException
import com.whatto.bcm.domain.exception.ResourceNotFoundException
import com.whatto.bcm.domain.exception.VendorApiException
import com.whatto.bcm.domain.vendor.VendorWebhookRecoveryPort
import com.whatto.bcm.domain.vendor.VendorWebhookStatus
import com.whatto.bcm.domain.vendor.VendorWebhookSubscription
import com.whatto.bcm.support.id.UuidV7Generator
import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

data class RequestWebhookRecoveryCommand(
    val idempotencyKey: String,
    val reason: String,
    val workTicket: String,
    val actor: AdminActor,
    val approver: AdminActor,
)

data class ExecuteWebhookRecoveryCommand(
    val requestId: String,
    val actor: AdminActor,
)

data class WebhookRecoveryStatus(
    val requestId: String,
    val webhookId: String,
    val state: WebhookRecoveryState,
    val scope: WebhookRecoveryScope,
    val requestedAt: Instant,
    val latestEvent: WebhookRecoveryEventStatus?,
    val calledAt: Instant?,
    val resultAt: Instant?,
    val previousStatus: String?,
    val currentStatus: String?,
    val missingRequiredEvents: List<String>,
    val scopeFrom: Instant?,
    val scopeTo: Instant?,
    val scheduledNotificationCount: Int?,
    val errorCode: String?,
)

fun interface WebhookRecoveryIdGenerator {
    fun nextId(): String
}

@Component
class UuidV7WebhookRecoveryIdGenerator(
    clock: Clock,
) : WebhookRecoveryIdGenerator {
    private val delegate = UuidV7Generator(clock)

    override fun nextId(): String = delegate.nextId()
}

@Service
class WebhookRecoveryService(
    private val vendor: VendorWebhookRecoveryPort,
    private val recoveries: WebhookRecoveryRepository,
    private val transactions: TransactionRunner,
    private val ids: WebhookRecoveryIdGenerator,
    private val properties: WebhookRecoveryProperties,
    private val clock: Clock,
) {
    fun request(command: RequestWebhookRecoveryCommand): WebhookRecoveryStatus {
        requireOperator(command.actor)
        requireApprover(command.approver)
        require(command.actor.employeeNo != command.approver.employeeNo) { "webhook recovery approver must differ from requester" }
        validate(command)
        val webhookId = configuredWebhookId()
        val existing = recoveries.findByIdempotency(command.actor.employeeNo, command.idempotencyKey)
        if (existing != null) {
            if (!existing.sameRequest(command, webhookId)) {
                throw ConflictException("webhookRecoveryIdempotency", command.idempotencyKey)
            }
            return existing.status()
        }
        val requiredEventsPayload = eventPayload(REQUIRED_EVENTS)
        val request =
            WebhookRecoveryRequest(
                requestId = ids.nextId(),
                webhookId = webhookId,
                scope = WebhookRecoveryScope.FAILED_LAST_24H,
                requiredEvents = REQUIRED_EVENTS,
                requiredEventsPayload = requiredEventsPayload,
                requiredEventsHash = sha256(requiredEventsPayload),
                idempotencyKey = command.idempotencyKey,
                reason = command.reason,
                workTicket = command.workTicket,
                requestedAt = now(),
                requestedBy = command.actor,
                approvedAt = now(),
                approvedBy = command.approver,
            )
        return try {
            transactions.run { recoveries.insertRequest(request) }
            WebhookRecoveryView(request, emptyList()).status()
        } catch (conflict: ConflictException) {
            val raced = recoveries.findByIdempotency(command.actor.employeeNo, command.idempotencyKey) ?: throw conflict
            if (!raced.sameRequest(command, webhookId)) throw conflict
            raced.status()
        }
    }

    fun execute(command: ExecuteWebhookRecoveryCommand): WebhookRecoveryStatus {
        requireOperator(command.actor)
        repeat(MAX_STEPS) {
            val view = recoveries.find(command.requestId) ?: throw ResourceNotFoundException("webhookRecovery", command.requestId)
            when (view.state(now(), intentTimeout())) {
                WebhookRecoveryState.COMPLETED,
                WebhookRecoveryState.FAILED,
                WebhookRecoveryState.AMBIGUOUS,
                -> return view.status()
                else -> Unit
            }
            val latest = view.events.maxByOrNull(WebhookRecoveryEvent::sequence)
            when (latest?.status) {
                null -> queryStatus(view, command.actor)
                WebhookRecoveryEventStatus.STATUS_OBSERVED -> afterStatus(view, latest, command.actor)
                WebhookRecoveryEventStatus.ACTIVATED -> afterActivation(view, latest, command.actor)
                WebhookRecoveryEventStatus.STATUS_INTENT,
                WebhookRecoveryEventStatus.ACTIVATE_INTENT,
                WebhookRecoveryEventStatus.RESEND_INTENT,
                -> return view.status()
                WebhookRecoveryEventStatus.FAILED,
                WebhookRecoveryEventStatus.RESEND_ACCEPTED,
                -> return view.status()
            }
        }
        error("webhook recovery exceeded maximum steps: requestId=${command.requestId}")
    }

    fun status(requestId: String): WebhookRecoveryStatus =
        (recoveries.find(requestId) ?: throw ResourceNotFoundException("webhookRecovery", requestId)).status()

    private fun queryStatus(
        view: WebhookRecoveryView,
        actor: AdminActor,
    ) {
        val intent = appendIntent(view, WebhookRecoveryEventStatus.STATUS_INTENT, WebhookRecoveryCallType.STATUS_QUERY, actor)
        val subscription =
            callOrRecordDefinitiveFailure(view.request.requestId, intent, actor) { vendor.webhook(view.request.webhookId) } ?: return
        recordSubscriptionResult(view.request, intent, subscription, WebhookRecoveryEventStatus.STATUS_OBSERVED, actor)
    }

    private fun afterStatus(
        view: WebhookRecoveryView,
        latest: WebhookRecoveryEvent,
        actor: AdminActor,
    ) {
        val missing = view.request.requiredEvents - latest.observedEvents
        if (missing.isNotEmpty()) {
            appendFailure(view.request.requestId, latest, actor, "REQUIRED_EVENTS_MISSING")
        } else if (latest.webhookStatus == VendorWebhookStatus.ENABLED.name) {
            resend(view, actor)
        } else {
            activate(view, actor)
        }
    }

    private fun activate(
        view: WebhookRecoveryView,
        actor: AdminActor,
    ) {
        val intent = appendIntent(view, WebhookRecoveryEventStatus.ACTIVATE_INTENT, WebhookRecoveryCallType.ACTIVATE, actor)
        val subscription =
            callOrRecordDefinitiveFailure(view.request.requestId, intent, actor) {
                vendor.activateWebhook(view.request.webhookId)
            } ?: return
        if (!validSubscription(view.request, subscription)) {
            appendFailure(view.request.requestId, intent, actor, activationError(view.request, subscription))
            return
        }
        recordSubscriptionResult(view.request, intent, subscription, WebhookRecoveryEventStatus.ACTIVATED, actor)
    }

    private fun afterActivation(
        view: WebhookRecoveryView,
        latest: WebhookRecoveryEvent,
        actor: AdminActor,
    ) {
        if (latest.webhookStatus != VendorWebhookStatus.ENABLED.name) {
            appendFailure(view.request.requestId, latest, actor, "ACTIVATION_NOT_ENABLED")
            return
        }
        resend(view, actor)
    }

    private fun resend(
        view: WebhookRecoveryView,
        actor: AdminActor,
    ) {
        val calledAt = now()
        val intent =
            appendIntent(
                view,
                WebhookRecoveryEventStatus.RESEND_INTENT,
                WebhookRecoveryCallType.RESEND_FAILED,
                actor,
                calledAt.minus(RESEND_LOOKBACK),
                calledAt,
            )
        val receipt =
            callOrRecordDefinitiveFailure(view.request.requestId, intent, actor) {
                vendor.resendFailedWebhookNotifications(view.request.webhookId)
            } ?: return
        val responsePayload = "{\"total\":${receipt.scheduledNotificationCount}}"
        appendResult(
            view.request.requestId,
            intent,
            actor,
            WebhookRecoveryEventStatus.RESEND_ACCEPTED,
            scheduledNotificationCount = receipt.scheduledNotificationCount,
            responsePayload = responsePayload,
        )
    }

    private fun recordSubscriptionResult(
        request: WebhookRecoveryRequest,
        intent: WebhookRecoveryEvent,
        subscription: VendorWebhookSubscription,
        status: WebhookRecoveryEventStatus,
        actor: AdminActor,
    ) {
        if (subscription.webhookId != request.webhookId) {
            appendFailure(request.requestId, intent, actor, "WEBHOOK_ID_MISMATCH")
            return
        }
        val observedPayload = eventPayload(subscription.events)
        appendResult(
            request.requestId,
            intent,
            actor,
            status,
            webhookStatus = subscription.status.name,
            observedEvents = subscription.events,
            observedEventsPayload = observedPayload,
            responsePayload = subscriptionPayload(subscription),
        )
    }

    private fun validSubscription(
        request: WebhookRecoveryRequest,
        subscription: VendorWebhookSubscription,
    ): Boolean =
        subscription.webhookId == request.webhookId &&
            subscription.status == VendorWebhookStatus.ENABLED &&
            subscription.events.containsAll(request.requiredEvents)

    private fun activationError(
        request: WebhookRecoveryRequest,
        subscription: VendorWebhookSubscription,
    ): String =
        when {
            subscription.webhookId != request.webhookId -> "WEBHOOK_ID_MISMATCH"
            subscription.status != VendorWebhookStatus.ENABLED -> "ACTIVATION_NOT_ENABLED"
            !subscription.events.containsAll(request.requiredEvents) -> "REQUIRED_EVENTS_MISSING"
            else -> "ACTIVATION_INVALID"
        }

    private fun appendIntent(
        view: WebhookRecoveryView,
        status: WebhookRecoveryEventStatus,
        callType: WebhookRecoveryCallType,
        actor: AdminActor,
        scopeFrom: Instant? = null,
        scopeTo: Instant? = null,
    ): WebhookRecoveryEvent =
        transactions.run {
            val locked =
                recoveries.findForUpdate(view.request.requestId)
                    ?: throw ResourceNotFoundException("webhookRecovery", view.request.requestId)
            check(locked.events == view.events) { "webhook recovery changed concurrently" }
            recoveries.appendEvent(
                event(
                    locked,
                    status,
                    callType,
                    actor,
                    calledAt = scopeTo ?: now(),
                    scopeFrom = scopeFrom,
                    scopeTo = scopeTo,
                ),
            )
        }

    private fun appendResult(
        requestId: String,
        intent: WebhookRecoveryEvent,
        actor: AdminActor,
        status: WebhookRecoveryEventStatus,
        webhookStatus: String? = null,
        observedEvents: Set<String> = emptySet(),
        observedEventsPayload: String? = null,
        scheduledNotificationCount: Int? = null,
        responsePayload: String,
    ) {
        transactions.run {
            val locked = recoveries.findForUpdate(requestId) ?: throw ResourceNotFoundException("webhookRecovery", requestId)
            check(locked.events.lastOrNull()?.samePersistedEvent(intent) == true) {
                "webhook recovery result does not follow its intent"
            }
            recoveries.appendEvent(
                event(
                    locked,
                    status,
                    intent.callType,
                    actor,
                    calledAt = intent.calledAt,
                    resultAt = now(),
                    webhookStatus = webhookStatus,
                    observedEvents = observedEvents,
                    observedEventsPayload = observedEventsPayload,
                    scopeFrom = intent.scopeFrom,
                    scopeTo = intent.scopeTo,
                    scheduledNotificationCount = scheduledNotificationCount,
                    responsePayload = responsePayload,
                ),
            )
        }
    }

    private fun appendFailure(
        requestId: String,
        previous: WebhookRecoveryEvent,
        actor: AdminActor,
        errorCode: String,
    ) {
        transactions.run {
            val locked = recoveries.findForUpdate(requestId) ?: throw ResourceNotFoundException("webhookRecovery", requestId)
            check(locked.events.lastOrNull()?.samePersistedEvent(previous) == true) {
                "webhook recovery failure does not follow current event"
            }
            recoveries.appendEvent(
                event(
                    locked,
                    WebhookRecoveryEventStatus.FAILED,
                    previous.callType,
                    actor,
                    calledAt = previous.calledAt,
                    resultAt = now(),
                    scopeFrom = previous.scopeFrom,
                    scopeTo = previous.scopeTo,
                    errorCode = errorCode,
                ),
            )
        }
    }

    private fun <T> callOrRecordDefinitiveFailure(
        requestId: String,
        intent: WebhookRecoveryEvent,
        actor: AdminActor,
        block: () -> T,
    ): T? =
        try {
            block()
        } catch (failure: VendorApiException) {
            val status = failure.httpStatus
            if (status != null && status in 400..499) {
                appendFailure(requestId, intent, actor, "VENDOR_$status")
                null
            } else {
                throw failure
            }
        }

    private fun event(
        view: WebhookRecoveryView,
        status: WebhookRecoveryEventStatus,
        callType: WebhookRecoveryCallType,
        actor: AdminActor,
        calledAt: Instant,
        resultAt: Instant? = null,
        webhookStatus: String? = null,
        observedEvents: Set<String> = emptySet(),
        observedEventsPayload: String? = null,
        scopeFrom: Instant? = null,
        scopeTo: Instant? = null,
        scheduledNotificationCount: Int? = null,
        responsePayload: String? = null,
        errorCode: String? = null,
    ) = WebhookRecoveryEvent(
        requestId = view.request.requestId,
        sequence = (view.events.maxOfOrNull(WebhookRecoveryEvent::sequence) ?: 0) + 1,
        status = status,
        callType = callType,
        calledAt = calledAt,
        resultAt = resultAt,
        webhookStatus = webhookStatus,
        observedEvents = observedEvents,
        observedEventsPayload = observedEventsPayload,
        observedEventsHash = observedEventsPayload?.let(::sha256),
        scopeFrom = scopeFrom,
        scopeTo = scopeTo,
        scheduledNotificationCount = scheduledNotificationCount,
        responsePayload = responsePayload,
        responseHash = responsePayload?.let(::sha256),
        errorCode = errorCode,
        occurredAt = resultAt ?: calledAt,
        actor = actor,
    )

    private fun WebhookRecoveryView.status(): WebhookRecoveryStatus {
        val latest = events.maxByOrNull(WebhookRecoveryEvent::sequence)
        val latestSubscription = events.lastOrNull { it.status in SUBSCRIPTION_RESULTS }
        val firstSubscription = events.firstOrNull { it.status in SUBSCRIPTION_RESULTS }
        return WebhookRecoveryStatus(
            requestId = request.requestId,
            webhookId = request.webhookId,
            state = state(now(), intentTimeout()),
            scope = request.scope,
            requestedAt = request.requestedAt,
            latestEvent = latest?.status,
            calledAt = latest?.calledAt,
            resultAt = latest?.resultAt,
            previousStatus = firstSubscription?.webhookStatus,
            currentStatus = latestSubscription?.webhookStatus,
            missingRequiredEvents = (request.requiredEvents - (latestSubscription?.observedEvents ?: emptySet())).sorted(),
            scopeFrom = events.lastOrNull { it.scopeFrom != null }?.scopeFrom,
            scopeTo = events.lastOrNull { it.scopeTo != null }?.scopeTo,
            scheduledNotificationCount = events.lastOrNull { it.scheduledNotificationCount != null }?.scheduledNotificationCount,
            errorCode = latest?.errorCode,
        )
    }

    private fun WebhookRecoveryEvent.samePersistedEvent(other: WebhookRecoveryEvent): Boolean =
        requestId == other.requestId &&
            sequence == other.sequence &&
            status == other.status &&
            callType == other.callType &&
            calledAt == other.calledAt &&
            actor.employeeNo == other.actor.employeeNo &&
            actor.branchCode == other.actor.branchCode

    private fun WebhookRecoveryView.sameRequest(
        command: RequestWebhookRecoveryCommand,
        webhookId: String,
    ): Boolean =
        request.webhookId == webhookId &&
            request.scope == WebhookRecoveryScope.FAILED_LAST_24H &&
            request.requiredEvents == REQUIRED_EVENTS &&
            request.reason == command.reason &&
            request.workTicket == command.workTicket &&
            request.approvedBy.employeeNo == command.approver.employeeNo

    private fun configuredWebhookId(): String =
        properties.webhookId.takeIf { it.matches(WEBHOOK_ID_PATTERN) }
            ?: throw IllegalStateException("bcm.webhook-recovery.webhook-id is required and must be a safe identifier")

    private fun validate(command: RequestWebhookRecoveryCommand) {
        require(command.idempotencyKey.isNotBlank() && command.idempotencyKey.length <= 128) { "invalid idempotencyKey" }
        require(command.reason.isNotBlank() && command.reason.length <= 1000) { "invalid reason" }
        require(command.workTicket.isNotBlank() && command.workTicket.length <= 128) { "invalid workTicket" }
        require(command.actor.employeeNo.length <= 6 && command.actor.branchCode.length <= 4) { "invalid actor" }
        require(command.approver.employeeNo.length <= 6 && command.approver.branchCode.length <= 4) { "invalid approver" }
    }

    private fun requireOperator(actor: AdminActor) {
        check(AdminRole.BCM_OPERATOR in actor.roles) { "BCM_OPERATOR role is required" }
    }

    private fun requireApprover(actor: AdminActor) {
        check(AdminRole.BCM_APPROVER in actor.roles) { "BCM_APPROVER role is required" }
    }

    private fun intentTimeout(): Duration = Duration.ofSeconds(properties.intentTimeoutSeconds)

    private fun now(): Instant = clock.instant().truncatedTo(ChronoUnit.SECONDS)

    companion object {
        val REQUIRED_EVENTS =
            setOf(
                "transaction.approval_status.updated",
                "transaction.created",
                "transaction.network_records.processing_completed",
                "transaction.status.updated",
            )
        private val RESEND_LOOKBACK = Duration.ofHours(24)
        private val WEBHOOK_ID_PATTERN = Regex("[A-Za-z0-9-]{1,64}")
        private val SUBSCRIPTION_RESULTS =
            setOf(WebhookRecoveryEventStatus.STATUS_OBSERVED, WebhookRecoveryEventStatus.ACTIVATED)
        private const val MAX_STEPS = 4

        fun eventPayload(events: Set<String>): String = events.sorted().joinToString(prefix = "[", postfix = "]") { "\"$it\"" }

        fun subscriptionPayload(subscription: VendorWebhookSubscription): String =
            "{\"events\":${eventPayload(subscription.events)},\"status\":\"${subscription.status.name}\"," +
                "\"webhookId\":\"${subscription.webhookId}\"}"

        fun sha256(value: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}

@Endpoint(id = "webhookRecovery")
@Component
@ConditionalOnProperty(prefix = "bcm.webhook-recovery", name = ["enabled"], havingValue = "true")
class WebhookRecoveryEndpoint(
    private val recovery: WebhookRecoveryService,
) {
    @ReadOperation
    fun status(requestId: String): WebhookRecoveryStatus = recovery.status(requestId)

    @WriteOperation
    fun request(
        idempotencyKey: String,
        reason: String,
        workTicket: String,
        employeeNo: String,
        branchCode: String,
        approverEmployeeNo: String,
        approverBranchCode: String,
    ): WebhookRecoveryStatus =
        recovery.request(
            RequestWebhookRecoveryCommand(
                idempotencyKey,
                reason,
                workTicket,
                AdminActor(employeeNo, branchCode, setOf(AdminRole.BCM_OPERATOR)),
                AdminActor(approverEmployeeNo, approverBranchCode, setOf(AdminRole.BCM_APPROVER)),
            ),
        )
}

@Endpoint(id = "webhookRecoveryExecution")
@Component
@ConditionalOnProperty(prefix = "bcm.webhook-recovery", name = ["enabled"], havingValue = "true")
class WebhookRecoveryExecutionEndpoint(
    private val recovery: WebhookRecoveryService,
) {
    @WriteOperation
    fun execute(
        requestId: String,
        employeeNo: String,
        branchCode: String,
    ): WebhookRecoveryStatus =
        recovery.execute(
            ExecuteWebhookRecoveryCommand(
                requestId,
                AdminActor(employeeNo, branchCode, setOf(AdminRole.BCM_OPERATOR)),
            ),
        )
}

@ConfigurationProperties("bcm.webhook-recovery")
data class WebhookRecoveryProperties(
    val enabled: Boolean = false,
    val webhookId: String = "",
    val intentTimeoutSeconds: Long = 60,
) {
    init {
        require(intentTimeoutSeconds in 30..3600) { "intent-timeout-seconds must be between 30 and 3600" }
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WebhookRecoveryProperties::class)
class WebhookRecoveryConfig
