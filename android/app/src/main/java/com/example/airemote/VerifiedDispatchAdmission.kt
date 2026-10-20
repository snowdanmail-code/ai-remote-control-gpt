package com.example.airemote

/** A single-use bridge between externally attested turn and the Android dispatcher.
 * Issued only after runtime has independently checked table/hand/turn/window.
 * No OCR producer or user interface is permitted to mint admission directly.
 */
data class DispatchFrameAdmission(
    val captureEpoch: Long, val frameSequence: Long, val capturedAtMs: Long,
    val handKey: String, val foregroundPackage: String, val windowGeneration: Long,
    val legalActions: Set<LiveAction>, val expiresAtMs: Long
)

class VerifiedDispatchAdmission(private val maxLifeMs: Long = 1_200L) {
    private var current: DispatchFrameAdmission? = null
    @Synchronized fun clear() { current = null }
    @Synchronized fun admit(view: VerifiedLiveView, pkg: String, generation: Long,
        nowMs: Long, trustedExternalBinding: Boolean, stopped: Boolean): Boolean {
        clear()
        if (stopped || !trustedExternalBinding || pkg.isBlank() || generation < 0L ||
            !view.foregroundVerified || !view.stageVerified || !view.turnVerified ||
            !view.exactLabelsVerified || view.handKey.isBlank() || view.legalActions.isEmpty() ||
            view.captureEpoch < 0L || view.frameSequence <= 0L ||
            nowMs < view.capturedAtMs || nowMs-view.capturedAtMs > maxLifeMs) return false
        current = DispatchFrameAdmission(view.captureEpoch,view.frameSequence,view.capturedAtMs,
            view.handKey,pkg,generation,view.legalActions.toSet(),view.capturedAtMs+maxLifeMs)
        return true
    }
    @Synchronized fun claim(view: VerifiedLiveView, tap: VerifiedTapArea, action: LiveAction,
                            nowMs: Long, stopped: Boolean): Boolean {
        val ticket = current ?: return false
        // Consume before checking: failures and delayed callbacks may never reattempt.
        current = null
        return !stopped && nowMs >= view.capturedAtMs && nowMs <= ticket.expiresAtMs &&
            ticket.captureEpoch == view.captureEpoch && ticket.frameSequence == view.frameSequence &&
            ticket.capturedAtMs == view.capturedAtMs && ticket.handKey == view.handKey &&
            tap.foregroundPackage == ticket.foregroundPackage &&
            tap.windowGeneration == ticket.windowGeneration &&
            tap.exactAction == action && action in ticket.legalActions && action in view.legalActions &&
            view.foregroundVerified && view.turnVerified && view.exactLabelsVerified
    }
}

object OnDeviceVerifiedDispatchAdmission {
    val gate = VerifiedDispatchAdmission()
    fun clear() = gate.clear()
}
