package com.example.airemote

/**
 * Process-local MediaProjection lifecycle. Prevents a new consent result from
 * racing onDestroy() of the previous service and silently reusing its projection.
 * Does not grant action permission or establish the foreground game's identity.
 */
enum class CaptureLifecycle { IDLE, STARTING, ACTIVE, STOPPING }

class CaptureLifecycleGuard {
    private var phase = CaptureLifecycle.IDLE

    @Synchronized fun current(): CaptureLifecycle = phase
    @Synchronized fun mayRequestNewCapture(): Boolean = phase == CaptureLifecycle.IDLE

    /** Only the real service, after receiving a new consent token, may begin. */
    @Synchronized fun beginStart(): Boolean {
        if (phase != CaptureLifecycle.IDLE) return false
        phase = CaptureLifecycle.STARTING
        return true
    }

    @Synchronized fun markActive(): Boolean {
        if (phase != CaptureLifecycle.STARTING) return false
        phase = CaptureLifecycle.ACTIVE
        return true
    }

    /** Called before stopService/stopSelf; never reopen while teardown is pending. */
    @Synchronized fun requestStop() {
        if (phase != CaptureLifecycle.IDLE) phase = CaptureLifecycle.STOPPING
    }

    /** Only CaptureService.onDestroy calls this after cleaning up its projection. */
    @Synchronized fun completeStop() { phase = CaptureLifecycle.IDLE }
}

object OnDeviceCaptureLifecycle {
    val gate = CaptureLifecycleGuard()
}
