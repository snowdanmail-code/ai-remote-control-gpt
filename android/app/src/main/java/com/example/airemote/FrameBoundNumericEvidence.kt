package com.example.airemote

/** Diagnostic-only monetary and clock readings bound to a single capture frame.
 * Never grants permission for an external action. */
data class FrameNumericReading(
    val tableId: String,
    val captureEpoch: Long,
    val frameSequence: Long,
    val capturedAtMs: Long,
    val sourceVerified: Boolean,
    val pot: Long?,
    val callAmount: Long?,
    val remainingSeconds: Int?
)

class FrameBoundNumericEvidence(private val maxAgeMs: Long = 1200L) {
    private var latest: FrameNumericReading? = null
    @Synchronized fun reset() { latest = null }
    @Synchronized fun accept(input: FrameNumericReading, nowMs: Long): Boolean {
        val old = latest
        val valid = input.sourceVerified && input.tableId.isNotBlank() &&
            input.captureEpoch >= 0 && input.frameSequence >= 0 &&
            input.capturedAtMs in (nowMs - maxAgeMs)..nowMs &&
            input.pot != null && input.pot >= 0 &&
            (input.callAmount == null || input.callAmount >= 0) &&
            (input.remainingSeconds == null || input.remainingSeconds in 0..3600) &&
            (old == null || input.captureEpoch > old.captureEpoch ||
                (input.captureEpoch == old.captureEpoch && input.tableId == old.tableId &&
                    input.frameSequence > old.frameSequence && input.capturedAtMs >= old.capturedAtMs))
        if (!valid) { latest = null; return false }
        latest = input
        return true
    }
    @Synchronized fun current(tableId: String, epoch: Long, nowMs: Long): FrameNumericReading? {
        val value = latest ?: return null
        if (value.tableId != tableId || value.captureEpoch != epoch ||
            nowMs < value.capturedAtMs || nowMs - value.capturedAtMs > maxAgeMs) {
            latest = null
            return null
        }
        return value
    }
}
