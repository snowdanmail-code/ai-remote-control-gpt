package com.example.airemote

/**
 * Fail-closed controller for interaction with an ACTUAL game application.
 * It does not issue Android gestures: user/operator and platform authorization,
 * exact foreground identity, legality of the requested move, and independent
 * post-action observations must be supplied by separate verified components.
 * In particular, a colorful button alone is never sufficient authorization.
 */
enum class LiveAction { FOLD, CHECK, CALL, BET, RAISE }
enum class LiveCommandStatus { REFUSED, READY_TO_DISPATCH, AWAITING_CONFIRMATION, CONFIRMED, UNCERTAIN }

data class VerifiedLiveView(
    val captureEpoch: Long,
    val frameSequence: Long,
    val capturedAtMs: Long,
    val handKey: String,
    val stateFingerprint: String,
    val foregroundVerified: Boolean,
    val stageVerified: Boolean,
    val turnVerified: Boolean,
    val exactLabelsVerified: Boolean,
    val legalActions: Set<LiveAction>,
    /** Must be backed by a verified post-tap event, not merely a changed animation. */
    /** Legacy diagnostic flag: not sufficient to confirm an OS/game action. */
    val actionEffectVerified: Boolean = false,
    val verifiedEffectTicket: VerifiedEffectTicket? = null
)

data class LiveCommand(
    val id: String,
    val handKey: String,
    val action: LiveAction,
    val captureEpoch: Long,
    val basedOnFrame: Long,
    val stateFingerprint: String,
    val createdMs: Long,
    val sourceCapturedAtMs: Long = createdMs,
    val expectedWindowGeneration: Long = -1L
)

/** Block duplicate dispatch even when Android callback or network response is delayed. */
class LiveGameActionGuard(private val maximumFrameAgeMs: Long = 1_600L,
                          private val acknowledgementTimeoutMs: Long = 4_000L,
                          private val effectTicketVerifier: (VerifiedEffectTicket, LiveCommand, VerifiedLiveView, Long) -> Boolean =
                              { _, _, _, _ -> false }) {
    private var emergencyStopped = true
    private var pending: LiveCommand? = null
    private var pendingSent = false
    private var currentStatus = LiveCommandStatus.REFUSED
    private val usedIds = mutableSetOf<String>()

    @Synchronized fun stop() {
        emergencyStopped = true
        pending = null
        pendingSent = false
        currentStatus = LiveCommandStatus.REFUSED
    }

    /** Operator-controlled arm; an unconfirmed action remains blocked until explicit reset. */
    @Synchronized fun armByOperator(): Boolean {
        if (pending != null) return false
        emergencyStopped = false
        return true
    }

    @Synchronized fun begin(
        id: String, action: LiveAction, view: VerifiedLiveView, nowMs: Long,
        platformAutomationAuthorized: Boolean
    ): LiveCommandStatus {
        if (emergencyStopped || !platformAutomationAuthorized || pending != null || id.isBlank() || id in usedIds ||
            view.captureEpoch < 0 || view.frameSequence < 0 || view.handKey.isBlank() ||
            view.stateFingerprint.isBlank() || !view.foregroundVerified || !view.stageVerified ||
            !view.turnVerified || !view.exactLabelsVerified || action !in view.legalActions ||
            nowMs < view.capturedAtMs || nowMs - view.capturedAtMs > maximumFrameAgeMs) {
            return LiveCommandStatus.REFUSED
        }
        pending = LiveCommand(id, view.handKey, action, view.captureEpoch,
            view.frameSequence, view.stateFingerprint, nowMs, view.capturedAtMs)
        usedIds.add(id)
        pendingSent = false
        currentStatus = LiveCommandStatus.READY_TO_DISPATCH
        return currentStatus
    }

    /** Exactly one dispatch ticket; gesture dispatch must check durable STOP again. */
    @Synchronized fun claimDispatch(id: String): Boolean {
        if (emergencyStopped || pending?.id != id || pendingSent) return false
        pendingSent = true
        currentStatus = LiveCommandStatus.AWAITING_CONFIRMATION
        return true
    }

    /** A newer independent observation MUST establish a changed state. */
    @Synchronized fun observe(view: VerifiedLiveView, nowMs: Long): LiveCommandStatus {
        val command = pending ?: return LiveCommandStatus.REFUSED
        if (emergencyStopped) return LiveCommandStatus.REFUSED
        if (nowMs - command.createdMs > acknowledgementTimeoutMs) {
            // No automatic retry: uncertain means the tap might have happened.
            currentStatus = LiveCommandStatus.UNCERTAIN
            return currentStatus
        }
        if (!pendingSent) return LiveCommandStatus.READY_TO_DISPATCH
        if (!view.foregroundVerified || !view.stageVerified ||
            view.captureEpoch != command.captureEpoch || view.handKey != command.handKey ||
            view.frameSequence <= command.basedOnFrame || nowMs < view.capturedAtMs ||
            nowMs - view.capturedAtMs > maximumFrameAgeMs) return currentStatus
        val ticket = view.verifiedEffectTicket
        if (view.stateFingerprint != command.stateFingerprint && ticket != null &&
            effectTicketVerifier(ticket, command, view, nowMs)) {
            pending = null
            pendingSent = false
            currentStatus = LiveCommandStatus.CONFIRMED
        }
        return currentStatus
    }

    @Synchronized fun status(): LiveCommandStatus = currentStatus
    @Synchronized fun hasUnconfirmedAction(): Boolean = pending != null
    /** Read-only current command for an independently authenticated effect
     * receipt. Never exposes a new dispatch capability. */
    @Synchronized fun pendingCommandForEffect(): LiveCommand? =
        pending?.takeIf { pendingSent && !emergencyStopped }

    /** Explicit operator reset after checking real game; never automated after timeout. */
    @Synchronized fun operatorResetAfterInspection() {
        pending = null
        pendingSent = false
        emergencyStopped = true
        currentStatus = LiveCommandStatus.REFUSED
    }
}

/** Initialized disarmed; durable OperatorStopStore controls re-arming of the app. */
object OnDeviceLiveActionControl {
    val guard = LiveGameActionGuard(effectTicketVerifier = { ticket, command, view, now ->
        OnDeviceEffectAuthority.consume(ticket, command, view, now)
    })
    fun stop() = guard.stop()
}
