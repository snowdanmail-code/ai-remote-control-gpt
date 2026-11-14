package com.example.airemote

import android.content.Context
import android.graphics.Rect
import android.os.SystemClock

/**
 * A separate in-app destination for real Android test gestures. This gate
 * cannot target external apps or be reconfigured from OCR, GPT, network, or
 * any other part of the poker-player pipeline.
 */
object T051LabSession {
    val machine = T051LabMachine()

    @Volatile private var visible = false
    private var destination: Rect? = null
    private var registeredToken = -1L
    private var registeredAtMs = 0L

    @Synchronized fun onVisible() { visible = true }

    @Synchronized fun onHidden() {
        visible = false
        destination = null
        registeredToken = -1L
        machine.stop()
    }

    @Synchronized fun register(token: Long, area: Rect): Boolean {
        if (!visible || machine.currentToken() != token ||
            machine.currentPhase() != T051LabPhase.READY_TO_TAP ||
            area.isEmpty || area.width() < 48 || area.height() < 48) return false
        destination = Rect(area)
        registeredToken = token
        registeredAtMs = SystemClock.elapsedRealtime()
        return true
    }

    @Synchronized fun denyReason(
        context: Context, token: Long,
        screenX: Int, screenY: Int, activeRootPackage: String?
    ): String? {
        if (!OperatorStopStore.isStopped(context)) return "STOP_REQUIRED"
        if (FrameStore.isCapturing) return "CAPTURE_ACTIVE"
        if (!visible || activeRootPackage != context.packageName)
            return "NOT_OWN_ACTIVITY"
        if (token != registeredToken || token != machine.currentToken() ||
            machine.currentPhase() != T051LabPhase.WAITING_ANDROID)
            return "LAB_WINDOW_NOT_VISIBLE"
        val current = SystemClock.elapsedRealtime()
        if (current < registeredAtMs || current - registeredAtMs > 1_500L)
            return "INVALID_TARGET"
        val rect = destination
        if (rect == null || rect.isEmpty || !rect.contains(screenX, screenY))
            return "INVALID_TARGET"
        return null
    }

    /** Receipt is triggered by the local test Button's own onClick listener. */
    @Synchronized fun buttonPressed(context: Context): Boolean =
        visible && OperatorStopStore.isStopped(context) && !FrameStore.isCapturing &&
            machine.appReceipt(machine.currentToken(), "CHECK")
}
