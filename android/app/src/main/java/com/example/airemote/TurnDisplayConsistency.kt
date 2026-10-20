package com.example.airemote

/**
 * Independent two-frame validation of a visual turn cue.
 * Visual agreement never grants permission to press anything. Legal-action
 * names (Check vs Call, Bet vs Raise), hand state, current timer and foreground
 * package must be checked separately before any future executor can exist.
 */
data class TurnVisualSignal(
    val capturedAtMs: Long,
    val captureEpoch: Long,
    val frameSequence: Long,
    val sourceVerified: Boolean,
    val reading: TurnVisualReading
)

data class ConfirmedTurnDisplay(
    val state: TurnDisplayState,
    val roles: Set<VisibleButtonRole>,
    val capturedAtMs: Long,
    val captureEpoch: Long,
    val frameSequence: Long,
    val sourceVerified: Boolean
) {
    /** Turn visibility alone must NEVER trigger a tap or strategic request. */
    val authorizedToAct: Boolean get() = false
}

class TurnDisplayConsistency(
    private val requiredSamples: Int = 2,
    private val maximumGapMs: Long = 2_600L,
    private val maximumAgeMs: Long = 1_700L
) {
    private var previous: TurnVisualSignal? = null
    private var matchingCount = 0
    private var accepted: ConfirmedTurnDisplay? = null
    private var lastSequence = -1L
    private var lastEpoch = -1L

    @Synchronized fun reset() {
        previous = null
        matchingCount = 0
        accepted = null
        lastSequence = -1L
        lastEpoch = -1L
    }

    @Synchronized fun accept(signal: TurnVisualSignal, nowMs: Long): ConfirmedTurnDisplay? {
        val invalid = signal.captureEpoch < 0 || signal.frameSequence < 0 ||
            signal.reading.state == TurnDisplayState.UNKNOWN ||
            signal.capturedAtMs > nowMs || nowMs - signal.capturedAtMs > maximumAgeMs ||
            (lastEpoch == signal.captureEpoch && signal.frameSequence <= lastSequence)
        if (invalid) { reset(); return null }
        val prev = previous
        val same = prev != null && prev.captureEpoch == signal.captureEpoch &&
            signal.frameSequence > prev.frameSequence &&
            signal.capturedAtMs >= prev.capturedAtMs &&
            signal.capturedAtMs - prev.capturedAtMs <= maximumGapMs &&
            prev.reading.state == signal.reading.state &&
            prev.reading.roles == signal.reading.roles &&
            prev.sourceVerified == signal.sourceVerified
        matchingCount = if (same) matchingCount + 1 else 1
        lastSequence = signal.frameSequence
        lastEpoch = signal.captureEpoch
        previous = signal
        // Never keep the old, seemingly actionable snapshot while a new cue is pending.
        accepted = if (matchingCount >= requiredSamples) ConfirmedTurnDisplay(
            signal.reading.state, signal.reading.roles, signal.capturedAtMs,
            signal.captureEpoch, signal.frameSequence, signal.sourceVerified
        ) else null
        return accepted
    }

    @Synchronized fun current(nowMs: Long): ConfirmedTurnDisplay? {
        val current = accepted ?: return null
        return if (nowMs >= current.capturedAtMs &&
            nowMs - current.capturedAtMs <= maximumAgeMs) current else null
    }
}
