package com.example.airemote

/** Safe decision-latency budget. A timer read from a previous frame ages with that frame.
 * This gate never authorizes actions: it only rejects expired/stale work.
 */
enum class DeadlineResult { SAFE_TO_CONTINUE, TIMER_UNKNOWN, FRAME_STALE, INSUFFICIENT_TIME, CLOCK_INVALID }

object DecisionDeadlineGate {
    fun assess(capturedAtMs: Long, observedAtMs: Long, currentAtMs: Long,
               remainingAtCaptureMs: Long?, minimumReserveMs: Long,
               maximumFrameAgeMs: Long): DeadlineResult {
        if (currentAtMs < observedAtMs || observedAtMs < capturedAtMs || capturedAtMs < 0L)
            return DeadlineResult.CLOCK_INVALID
        if (currentAtMs - capturedAtMs > maximumFrameAgeMs)
            return DeadlineResult.FRAME_STALE
        if (remainingAtCaptureMs == null || remainingAtCaptureMs < 0L)
            return DeadlineResult.TIMER_UNKNOWN
        if (minimumReserveMs < 0L || maximumFrameAgeMs < 0L) return DeadlineResult.CLOCK_INVALID
        val agedRemaining = remainingAtCaptureMs - (currentAtMs - capturedAtMs)
        return if (agedRemaining < minimumReserveMs) DeadlineResult.INSUFFICIENT_TIME
        else DeadlineResult.SAFE_TO_CONTINUE
    }
}
