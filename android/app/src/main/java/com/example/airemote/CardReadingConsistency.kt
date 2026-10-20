package com.example.airemote

/**
 * Source-independent stability check: two separate, fresh observations must
 * agree before the Android UI can show a locally confirmed card reading.
 * This is NOT proof that 1win is in the foreground or that an action is legal.
 */
data class CardVisionSignal(
    val capturedAtMs: Long,
    val captureEpoch: Long,
    val frameSequence: Long,
    val reading: CardReading
)

data class StableCardReading(
    val capturedAtMs: Long,
    val captureEpoch: Long,
    val frameSequence: Long,
    val hole: List<String>,
    val community: List<String>,
    val minimumSimilarity: Double
)

class CardReadingConsistency(
    private val requiredSamples: Int = 2,
    private val maximumGapMs: Long = 2_500L,
    private val maximumSampleAgeMs: Long = 1_200L
) {
    private var pending: CardVisionSignal? = null
    private var count = 0
    private var accepted: StableCardReading? = null

    @Synchronized fun reset() { pending = null; count = 0; accepted = null }

    @Synchronized fun accept(signal: CardVisionSignal, inspectedAtMs: Long): StableCardReading? {
        if (signal.captureEpoch < 0 || signal.frameSequence < 0 ||
            inspectedAtMs < signal.capturedAtMs ||
            inspectedAtMs - signal.capturedAtMs > maximumSampleAgeMs ||
            !signal.reading.complete ||
            signal.reading.hole.size != 2 ||
            signal.reading.community.size !in listOf(0, 3, 4, 5) ||
            !CardVisionCore.checkUnique(signal.reading.hole.filterNotNull(),
                signal.reading.community.filterNotNull()) ||
            signal.reading.minimumSimilarity !in 0.78..1.0
        ) { reset(); return null }
        val old = pending
        val same = old != null && old.captureEpoch == signal.captureEpoch &&
            old.frameSequence < signal.frameSequence &&
            signal.capturedAtMs >= old.capturedAtMs &&
            signal.capturedAtMs - old.capturedAtMs <= maximumGapMs &&
            old.reading.hole == signal.reading.hole &&
            old.reading.community == signal.reading.community
        count = if (same) count + 1 else 1
        pending = signal
        accepted = if (count >= requiredSamples) StableCardReading(
            signal.capturedAtMs, signal.captureEpoch, signal.frameSequence,
            signal.reading.hole.filterNotNull(),
            signal.reading.community.filterNotNull(),
            signal.reading.minimumSimilarity
        ) else null
        return accepted
    }

    @Synchronized fun current(nowMs: Long): StableCardReading? {
        val sample = accepted ?: return null
        return if (nowMs >= sample.capturedAtMs && nowMs - sample.capturedAtMs <= maximumSampleAgeMs)
            sample else null
    }
}
