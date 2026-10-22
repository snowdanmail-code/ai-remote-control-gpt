package com.example.airemote

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import kotlin.math.abs

/**
 * The REAL Android gesture transport. It does not call 1win/private APIs and never
 * dispatches merely because the action strip looks colorful. LiveInputCoordinator
 * must supply fully verified identity, card/turn stage, actual button bounds/label,
 * operator ARM, and platform permission. No screen content leaves the phone.
 */
class LiveGestureAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var manager: WindowManager? = null
    private var stopOverlay: TextView? = null
    private var params: WindowManager.LayoutParams? = null
    // Root identity is sampled periodically, not inferred from AccessibilityEvent.packageName.
    private val rootSampler = object : Runnable {
        override fun run() {
            val now = SystemClock.elapsedRealtime()
            val root = rootInActiveWindow
            LiveForegroundProbe.recordRoot(root?.packageName?.toString(), now)
            val foreground = LiveForegroundProbe.snapshot()
            if (!CaptureObservationMode.isSpectator() &&
                !OperatorStopStore.isStopped(applicationContext) &&
                FrameStore.isCapturing &&
                root != null && foreground != null &&
                foreground.packageName != packageName &&
                foreground.packageName == FrameStore.windowOrigin?.observedPackage &&
                FrameStore.windowOrigin?.safeForLocalDiagnostics == true) {
                sampleNativeActions(root, foreground, now)
            } else OnDevicePlayerNativeControls.clear()
            main.postDelayed(this, 350L)
        }
    }

    /** Independent Android Accessibility UI-node scan: factual bounds and
     * exact labels only. Some games provide zero nodes (Canvas/WebGL). Never
     * infer missing controls or grant play permission from this scan. */
    private fun sampleNativeActions(root: AccessibilityNodeInfo,
                                    foreground: ForegroundPackageEvidence,
                                    now: Long) {
        try {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            val buttons = mutableListOf<NativeActionButton>()
            var seen = 0
            while (queue.isNotEmpty() && seen < PlayerNativeControlEvidence.MAX_SCANNED_NODES) {
                val node = queue.removeFirst()
                seen++
                val words = node.text?.toString() ?: node.contentDescription?.toString()
                val action = words?.let(PlayerNativeControlEvidence::classifyExactLabel)
                if (action != null && node.isVisibleToUser && node.isEnabled) {
                    val clickable = if (node.isClickable) node else node.parent
                    if (clickable?.isClickable == true && clickable.isEnabled &&
                        clickable.isVisibleToUser) {
                        val rect = Rect()
                        clickable.getBoundsInScreen(rect)
                        buttons.add(NativeActionButton(action, words!!.trim(),
                            rect.left, rect.top, rect.right, rect.bottom,
                            foreground.packageName, foreground.windowGeneration, now))
                    }
                }
                for (i in 0 until node.childCount) {
                    node.getChild(i)?.let(queue::addLast)
                }
            }
            val snapshot = NativeControlSnapshot(foreground.packageName,
                foreground.windowGeneration, now, buttons,
                seen, queue.isNotEmpty())
            // Always replace, including empty: no historical stale buttons.
            OnDevicePlayerNativeControls.publish(snapshot)
            // Deliberately restricted pilot: only a free CHECK, no CALL/FOLD/BET/
            // RAISE and no cloud decisions. Requires explicit existing operator
            // authorization plus both native UI scans and fresh player cards.
            val origin = FrameStore.windowOrigin
            if (LocalPlayerCheckPilot.isActive() &&
                !OperatorStopStore.isStopped(applicationContext) &&
                LiveGamePermissionGate.permitted() &&
                LiveGamePermissionGate.packageName() == foreground.packageName &&
                origin?.windowGeneration == foreground.windowGeneration &&
                origin.safeForLocalDiagnostics) {
                val ticket = LocalPlayerCheckPilot.observe(
                    FrameStore.cardConsensus.current(now), snapshot,
                    foreground.packageName, foreground.windowGeneration,
                    FrameStore.captureEpoch, now,
                    resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
                if (ticket != null) dispatchScopedLocalCheck(ticket)
            }
        } catch (_: Exception) { OnDevicePlayerNativeControls.clear() }
    }

    /**
     * Narrow production transport for zero-chip CHECK only. Independent from
     * external authenticated-hand dispatch and NEVER available for paid actions.
     * An OS callback is not proof the poker game accepted the move.
     */
    private fun dispatchScopedLocalCheck(ticket: LocalCheckTicket) {
        val now = SystemClock.elapsedRealtime()
        val currentPackage = rootInActiveWindow?.packageName?.toString()
        val foreground = LiveForegroundProbe.snapshot()
        val origin = FrameStore.windowOrigin
        val cards = FrameStore.cardConsensus.current(now)
        val native = OnDevicePlayerNativeControls.current(now, currentPackage,
            ticket.windowGeneration, resources.displayMetrics.widthPixels,
            resources.displayMetrics.heightPixels)
        val b = ticket.button
        val tap = VerifiedTapArea(b.left, b.top, b.right, b.bottom,
            (b.left + b.right) / 2, (b.top + b.bottom) / 2,
            resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels,
            LiveAction.CHECK, b.label, ticket.packageName, ticket.windowGeneration)
        val command = LiveCommand("local-check-${ticket.commandSequence}",
            "local-visual-only", LiveAction.CHECK,
            ticket.captureEpoch, ticket.cardSequence,
            "unverified-visual-pilot", now, now, ticket.windowGeneration)
        val stop = params
        val occludedByStop = stop != null && stopOverlay != null &&
            tap.centerX >= stop.x && tap.centerX < stop.x + stop.width &&
            tap.centerY >= stop.y && tap.centerY < stop.y + stop.height
        // Local pilot has NO authority for hand/turn IDs and MUST NEVER be
        // promoted to the paid-action VerifiedDispatchAdmission by this code.
        if (CaptureObservationMode.isSpectator() ||
            OperatorStopStore.isStopped(applicationContext) ||
            !LiveGamePermissionGate.permitted() ||
            !FrameStore.isCapturing || FrameStore.captureEpoch != ticket.captureEpoch ||
            currentPackage != ticket.packageName ||
            LiveGamePermissionGate.packageName() != ticket.packageName ||
            foreground?.windowGeneration != ticket.windowGeneration ||
            origin?.windowGeneration != ticket.windowGeneration ||
            origin?.safeForLocalDiagnostics != true ||
            cards?.captureEpoch != ticket.captureEpoch ||
            cards.frameSequence != ticket.cardSequence ||
            cards.hole != ticket.hole || cards.community != ticket.board ||
            !when (ticket.source) {
                LocalCheckSource.ANDROID_NATIVE ->
                    native != null && native.buttons.singleOrNull {
                        it.action == LiveAction.CHECK && it.left == b.left &&
                            it.top == b.top && it.right == b.right && it.bottom == b.bottom
                    } != null && native.buttons.none { it.action == LiveAction.CALL }
                LocalCheckSource.VISUAL_OCR_CALIBRATED -> {
                    val ocr = FrameStore.lastAutomaticOcr
                    ocr != null && ocr.ticket.captureEpoch == ticket.captureEpoch &&
                        ocr.ticket.sourcePackage == ticket.packageName &&
                        ocr.ticket.windowGeneration == ticket.windowGeneration &&
                        ocr.agreement.status == OcrDiagnosticStatus.CONSISTENT_DIAGNOSTIC_ONLY &&
                        ocr.turnDisplay == TurnDisplayState.READY &&
                        ocr.interpretation.actions.count {
                            it.action == ExactActionCandidate.CHECK
                        } == 1 &&
                        ocr.interpretation.actions.none {
                            it.action == ExactActionCandidate.CALL
                        } &&
                        b.left == 184 * tap.screenWidth / 540 &&
                        b.right == 352 * tap.screenWidth / 540 &&
                        b.top == 1108 * tap.screenHeight / 1170 &&
                        b.bottom == 1138 * tap.screenHeight / 1170
                }
            } ||
            b.top < (tap.screenHeight * 0.74).toInt() ||
            occludedByStop ||
            LiveTapSafety.verify(tap, command, currentPackage,
                foreground.atMs, now, tap.screenWidth, tap.screenHeight,
                LiveGamePermissionGate.packageName()) != TapSafetyResult.VALID) {
            LocalPlayerCheckPilot.rejected()
            return
        }
        if (!LocalPlayerCheckPilot.claim(ticket, now) ||
            OperatorStopStore.isStopped(applicationContext)) return
        // No race with STOP touch dispatch on main Looper before this call.
        val path = Path().apply {
            moveTo(tap.centerX.toFloat(), tap.centerY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 65L)).build()
        val sent = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                // NEVER record success of the poker move on an OS callback.
                OnDeviceIncidentRecorder.record(applicationContext,
                    IncidentCode.GESTURE_COMPLETED_OS, captureEpoch = ticket.captureEpoch,
                    frameSequence = ticket.cardSequence, action = "CHECK")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                LocalPlayerCheckPilot.rejected()
                OnDeviceIncidentRecorder.record(applicationContext,
                    IncidentCode.COMMAND_REJECTED, IncidentSeverity.WARNING,
                    ticket.captureEpoch, ticket.cardSequence, action = "CHECK")
            }
        }, main)
        if (!sent) LocalPlayerCheckPilot.rejected()
        OnDeviceIncidentRecorder.record(applicationContext,
            if (sent) IncidentCode.COMMAND_DISPATCHED else IncidentCode.COMMAND_REJECTED,
            if (sent) IncidentSeverity.INFO else IncidentSeverity.WARNING,
            ticket.captureEpoch, ticket.cardSequence, action = "CHECK")
    }

    /** Called only after a new, valid, distinct Canvas OCR frame. */
    internal fun offerScopedVisualCheck(ticket: LocalCheckTicket) {
        if (ticket.source != LocalCheckSource.VISUAL_OCR_CALIBRATED) return
        main.post { dispatchScopedLocalCheck(ticket) }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        LiveForegroundProbe.attach(this)
        main.post(rootSampler)
        installMovableStop()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            // Even a same-package dialog/window transition voids the old binding.
            LiveForegroundProbe.noteWindowTransition()
            OnDeviceIncidentRecorder.record(applicationContext, IncidentCode.FOREGROUND_CHANGED)
            // Event.packageName may identify an overlay or a previous window.
            LiveForegroundProbe.recordRoot(
                rootInActiveWindow?.packageName?.toString(), SystemClock.elapsedRealtime())
        }
    }

    override fun onInterrupt() {
        main.removeCallbacks(rootSampler)
        OnDevicePlayerNativeControls.clear()
        // An interrupted accessibility source must never retain armed gestures.
        LiveForegroundProbe.invalidate()
        OnDeviceAutonomousRuntime.stop("Accessibility прервана")
        OnDeviceLiveActionControl.stop()
    }

    internal fun dispatchVerifiedTap(command: LiveCommand, area: VerifiedTapArea, onAccepted: (Boolean) -> Unit) {
        main.post {
            val now = SystemClock.elapsedRealtime()
            // An old window event alone is insufficient; re-read the actual active root.
            val rootPackage = rootInActiveWindow?.packageName?.toString()
            // Never trust a previous window event if the current active root is missing.
            LiveForegroundProbe.recordRoot(rootPackage, now)
            val dispatchCheck = LiveDispatchFreshness.verify(command, now,
                FrameStore.captureEpoch, FrameStore.isCapturing,
                rootPackage, LiveGamePermissionGate.packageName(),
                LiveForegroundProbe.snapshot()?.windowGeneration)
            // STOP overlay must not be the target of a gesture intended for the game.
            val overlay = params
            val stopOccludesTarget = overlay != null && stopOverlay != null &&
                area.centerX >= overlay.x && area.centerX < overlay.x + overlay.width &&
                area.centerY >= overlay.y && area.centerY < overlay.y + overlay.height
            if (dispatchCheck != LiveDispatchCheck.VALID ||
                area.windowGeneration != command.expectedWindowGeneration ||
                area.windowGeneration < 0L || stopOccludesTarget) {
                OnDeviceLiveActionControl.stop()
                OnDeviceIncidentRecorder.record(applicationContext, IncidentCode.COMMAND_REJECTED,
                    IncidentSeverity.WARNING, command.captureEpoch, command.basedOnFrame,
                    action = command.action.name)
                onAccepted(false)
                return@post
            }
            val foreground = LiveForegroundProbe.snapshot()
            val metrics = resources.displayMetrics
            val safe = LiveTapSafety.verify(area, command, foreground?.packageName, foreground?.atMs ?: -1L,
                now, metrics.widthPixels, metrics.heightPixels, LiveGamePermissionGate.packageName())
            // Double-check durable STOP *immediately* before the OS gesture call.
            if (safe != TapSafetyResult.VALID || !LiveGamePermissionGate.permitted() ||
                OperatorStopStore.isStopped(applicationContext) ||
                !OnDeviceLiveActionControl.guard.claimDispatch(command.id)) {
                onAccepted(false)
                return@post
            }
            // STOP can occur between the first check and claiming the ticket.
            // Recheck immediately before calling Android's gesture transport.
            if (OperatorStopStore.isStopped(applicationContext) ||
                !LiveGamePermissionGate.permitted()) {
                OnDeviceLiveActionControl.guard.stop()
                onAccepted(false)
                return@post
            }
            // A stop, capture restart, or delayed UI thread invalidates the ticket.
            val finalCheck = LiveDispatchFreshness.verify(command, SystemClock.elapsedRealtime(),
                FrameStore.captureEpoch, FrameStore.isCapturing,
                rootInActiveWindow?.packageName?.toString(), LiveGamePermissionGate.packageName(),
                LiveForegroundProbe.snapshot()?.windowGeneration)
            if (finalCheck != LiveDispatchCheck.VALID ||
                OperatorStopStore.isStopped(applicationContext)) {
                OnDeviceLiveActionControl.stop()
                onAccepted(false)
                return@post
            }
            val path = Path().apply { moveTo(area.centerX.toFloat(), area.centerY.toFloat()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, 65L)).build()
            val submitted = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    // OS dispatch completion != action accepted by the game.
                    // Wait for a separate fresh, verified game-state observation.
                    OnDeviceIncidentRecorder.record(applicationContext,
                        IncidentCode.GESTURE_COMPLETED_OS, captureEpoch = command.captureEpoch,
                        frameSequence = command.basedOnFrame, action = command.action.name)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    OnDeviceLiveActionControl.guard.stop()
                    OnDeviceIncidentRecorder.record(applicationContext,
                        IncidentCode.COMMAND_REJECTED, IncidentSeverity.ERROR,
                        command.captureEpoch, command.basedOnFrame, action = command.action.name)
                }
            }, main)
            OnDeviceIncidentRecorder.record(applicationContext,
                if (submitted) IncidentCode.COMMAND_DISPATCHED else IncidentCode.COMMAND_REJECTED,
                if (submitted) IncidentSeverity.INFO else IncidentSeverity.ERROR,
                command.captureEpoch, command.basedOnFrame, action = command.action.name)
            onAccepted(submitted)
            if (!submitted) OnDeviceLiveActionControl.guard.stop()
        }
    }

    private fun installMovableStop() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val density = resources.displayMetrics.density
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val size = (55 * density).toInt()
        val margin = (10 * density).toInt()
        val layout = WindowManager.LayoutParams(size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (w - size - margin).coerceAtLeast(margin)
            y = (h * 0.30).toInt() // below status icons, away from lower game controls
            title = "AI Remote Control STOP"
        }
        val view = TextView(this).apply {
            text = "STOP"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.rgb(170, 22, 22))
                cornerRadius = (28 * density)
            }
            contentDescription = "Аварийный STOP AI Remote Control. Можно переместить."
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragged = false
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = layout.x; startY = layout.y
                    dragged = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.rawX - downX) > 12 * density || abs(event.rawY - downY) > 12 * density)
                        dragged = true
                    if (dragged) {
                        layout.x = (startX + (event.rawX - downX)).toInt().coerceIn(0, w - size)
                        layout.y = (startY + (event.rawY - downY)).toInt()
                            .coerceIn((h * 0.09).toInt(), (h * 0.87).toInt())
                        try { wm.updateViewLayout(view, layout) } catch (_: Exception) { }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragged) {
                        OperatorStopStore.stop(applicationContext)
                        stopService(Intent(this, CaptureService::class.java))
                        LiveForegroundProbe.invalidate()
                    }
                    true
                }
                else -> false
            }
        }
        try {
            wm.addView(view, layout)
            manager = wm; params = layout; stopOverlay = view
        } catch (_: Exception) {
            // Notification STOP and in-app STOP remain available on restricted devices.
        }
    }

    override fun onDestroy() {
        main.removeCallbacks(rootSampler)
        OnDevicePlayerNativeControls.clear()
        OnDeviceAutonomousRuntime.stop("Служба доступности отключена")
        OnDeviceLiveActionControl.stop()
        LiveForegroundProbe.detach(this)
        stopOverlay?.let { view -> try { manager?.removeView(view) } catch (_: Exception) {} }
        stopOverlay = null
        super.onDestroy()
    }
}

/** Foreground package evidence only; active-root reads, never event package guesses. */
data class ForegroundPackageEvidence(
    val packageName: String, val atMs: Long, val windowGeneration: Long
)
object LiveForegroundProbe {
    @Volatile private var active: LiveGestureAccessibilityService? = null
    @Volatile private var evidence: ForegroundPackageEvidence? = null
    @Volatile private var lastExternal: ForegroundPackageEvidence? = null
    private var generation = 0L
    @Synchronized fun attach(service: LiveGestureAccessibilityService) {
        active = service; evidence = null; lastExternal = null; generation++
    }
    @Synchronized fun detach(service: LiveGestureAccessibilityService) {
        if (active === service) { active = null; invalidate() }
    }
    @Synchronized fun noteWindowTransition() { invalidate() }
    @Synchronized fun recordRoot(name: String?, nowMs: Long) {
        if (active == null || name.isNullOrBlank()) { invalidate(); return }
        if (evidence?.packageName != name) generation++
        evidence = ForegroundPackageEvidence(name, nowMs, generation)
        if (name != active?.packageName && name != "com.android.systemui" &&
            !name.contains("permissioncontroller", ignoreCase = true))
            lastExternal = evidence
    }
    fun snapshot(): ForegroundPackageEvidence? = evidence
    /** Local diagnostic only: not a confirmed 1win/table identifier. */
    fun lastExternalPackage(): ForegroundPackageEvidence? = lastExternal
    fun activeService(): LiveGestureAccessibilityService? = active
    fun connected(): Boolean = active != null
    @Synchronized fun invalidate() { generation++; evidence = null }
}

/**
 * The only entry point for future strategy decisions. It currently has no live
 * producer of VerifiedLiveView or verified button geometry, so no taps are sent.
 */
object LiveInputCoordinator {
    @Synchronized fun requestVerifiedAction(
        context: Context,
        id: String,
        desired: LiveAction,
        view: VerifiedLiveView,
        tap: VerifiedTapArea,
        nowMs: Long,
        acknowledgement: (Boolean) -> Unit
    ): LiveCommandStatus {
        val service = LiveForegroundProbe.activeService() ?: return LiveCommandStatus.REFUSED
        if (!LiveGamePermissionGate.permitted() || OperatorStopStore.isStopped(context))
            return LiveCommandStatus.REFUSED
        val evidence = LiveForegroundProbe.snapshot() ?: return LiveCommandStatus.REFUSED
        if (evidence.packageName != LiveGamePermissionGate.packageName() ||
            tap.windowGeneration < 0L || tap.windowGeneration != evidence.windowGeneration ||
            nowMs < evidence.atMs || nowMs - evidence.atMs > LiveTapSafety.MAX_FOREGROUND_AGE_MS)
            return LiveCommandStatus.REFUSED
        // Avoid consuming a command ID before target geometry/label has passed validation.
        if (LiveTapSafety.verify(tap,
                LiveCommand(id, view.handKey, desired, view.captureEpoch,
                    view.frameSequence, view.stateFingerprint, nowMs, view.capturedAtMs,
                    evidence.windowGeneration),
                evidence.packageName, evidence.atMs, nowMs, tap.screenWidth,
                tap.screenHeight, LiveGamePermissionGate.packageName()) != TapSafetyResult.VALID)
            return LiveCommandStatus.REFUSED
        // A call to this coordinator is NOT proof of a verified turn.
        // Only runtime may issue one frame-scoped admission after authenticating
        // external table/hand/turn evidence. This eliminates direct-API bypass.
        if (!OnDeviceVerifiedDispatchAdmission.gate.claim(view, tap, desired, nowMs,
                stopped = OperatorStopStore.isStopped(context))) {
            OnDeviceIncidentRecorder.record(context, IncidentCode.COMMAND_REJECTED,
                IncidentSeverity.WARNING, view.captureEpoch, view.frameSequence,
                action = desired.name)
            return LiveCommandStatus.REFUSED
        }
        // The authoritative legal-action list and exact labels are independent of color OCR.
        val initial = OnDeviceLiveActionControl.guard.begin(
            id, desired, view, nowMs, platformAutomationAuthorized = LiveGamePermissionGate.permitted())
        if (initial != LiveCommandStatus.READY_TO_DISPATCH) return initial
        val command = LiveCommand(id, view.handKey, desired, view.captureEpoch,
            view.frameSequence, view.stateFingerprint, nowMs, view.capturedAtMs,
                    evidence.windowGeneration)
        service.dispatchVerifiedTap(command, tap, acknowledgement)
        return initial
    }
}
