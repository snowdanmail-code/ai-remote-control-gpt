package com.example.airemote

/** Final time/identity checks immediately before an Android gesture is queued to the OS.
 *  No Android classes, networking, or game input: independently unit-testable.
 */
enum class LiveDispatchCheck {
    VALID, MISSING_CAPTURE, DIFFERENT_CAPTURE, LATE_QUEUE,
    STALE_OBSERVATION, UNVERIFIED_FOREGROUND, WINDOW_CHANGED
}

object LiveDispatchFreshness {
    const val MAX_QUEUE_DELAY_MS: Long = 600L
    const val MAX_FRAME_AGE_MS: Long = 1_600L

    fun verify(command: LiveCommand, nowMs: Long, currentCaptureEpoch: Long,
               captureActive: Boolean, actualRootPackage: String?,
               approvedPackage: String?, actualWindowGeneration: Long? = null): LiveDispatchCheck {
        if (!captureActive) return LiveDispatchCheck.MISSING_CAPTURE
        if (command.captureEpoch != currentCaptureEpoch) return LiveDispatchCheck.DIFFERENT_CAPTURE
        if (nowMs < command.createdMs || nowMs - command.createdMs > MAX_QUEUE_DELAY_MS)
            return LiveDispatchCheck.LATE_QUEUE
        if (command.sourceCapturedAtMs < 0 || nowMs < command.sourceCapturedAtMs ||
            nowMs - command.sourceCapturedAtMs > MAX_FRAME_AGE_MS)
            return LiveDispatchCheck.STALE_OBSERVATION
        if (approvedPackage.isNullOrBlank() || actualRootPackage.isNullOrBlank() ||
            actualRootPackage != approvedPackage)
            return LiveDispatchCheck.UNVERIFIED_FOREGROUND
        if (command.expectedWindowGeneration >= 0 &&
            command.expectedWindowGeneration != actualWindowGeneration)
            return LiveDispatchCheck.WINDOW_CHANGED
        return LiveDispatchCheck.VALID
    }
}
