package com.example.airemote

/**
 * Fail-closed protection against accidentally replacing an external screenshot with
 * this application's own UI. It does NOT prove which external app is displayed.
 * Pure Kotlin so the transition rules can be tested without Android or a device.
 */
class ForegroundCaptureGate {
    private var activityVisible = true
    private var generation = 0L

    @Synchronized fun onActivityVisible() {
        activityVisible = true
        generation++
    }

    @Synchronized fun onActivityHidden() {
        activityVisible = false
        generation++
    }

    /** Null means that a frame must not be captured/published. */
    @Synchronized fun acquireTicket(): Long? = if (activityVisible) null else generation

    /** Invalidate work that was started before the activity returned to foreground. */
    @Synchronized fun canPublish(ticket: Long?): Boolean =
        ticket != null && !activityVisible && ticket == generation
}

/** Shared process-local gate; starts closed even before MainActivity exists. */
object AppVisibilityGate {
    private val gate = ForegroundCaptureGate()
    fun onActivityVisible() = gate.onActivityVisible()
    fun onActivityHidden() = gate.onActivityHidden()
    fun acquireTicket(): Long? = gate.acquireTicket()
    fun canPublish(ticket: Long?) = gate.canPublish(ticket)
}
