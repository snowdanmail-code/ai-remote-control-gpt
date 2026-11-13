package com.example.airemote

/**
 * A buy-in dialog disappearing is NOT proof of seating: user may close it
 * with X or navigate away. The existing on-device card reader must positively
 * identify two bottom "hole" cards after a once-verified buy-in was seen.
 *
 * This proof has no authority to press a financial button or choose a wager.
 */
data class OneWinHeroAfterBuyInFrame(
    val sourcePackage: String,
    val captureEpoch: Long,
    val windowGeneration: Long,
    val frameSequence: Long,
    val capturedAtMs: Long,
    val cardCaptureEpoch: Long,
    val cardCapturedAtMs: Long,
    val holeCards: List<String>
) {
    fun valid(nowMs: Long): Boolean =
        sourcePackage.isNotBlank() && captureEpoch >= 0L &&
        windowGeneration >= 0L && frameSequence > 0L &&
        capturedAtMs >= 0L && nowMs >= capturedAtMs &&
        // The separate rus+eng OCR worker can lag behind the capture frame.
        // Root/window/epoch and capture-time card provenance are required.
        nowMs - capturedAtMs <= 6_000L &&
        cardCaptureEpoch == captureEpoch &&
        cardCapturedAtMs in (capturedAtMs-1_500L)..(capturedAtMs+300L) &&
        holeCards.size == 2 &&
        holeCards.all { it.isNotBlank() } &&
        holeCards[0] != holeCards[1]
}

/**
 * Two independent frames from the same active app/window/epoch with the
 * same fully recognized bottom cards. The preceding buy-in must already
 * be independently confirmed by OneWinBuyInAgreement.
 */
class OneWinBottomHeroAfterBuyInAgreement {
    private var previous: OneWinHeroAfterBuyInFrame? = null

    @Synchronized fun reset() { previous = null }

    @Synchronized fun observe(sample: OneWinHeroAfterBuyInFrame?,
                              nowMs: Long, buyInPreviouslyVerified: Boolean,
                              dialogStillVisible: Boolean): Boolean {
        if (!buyInPreviouslyVerified || dialogStillVisible ||
            sample == null || !sample.valid(nowMs)) {
            reset()
            return false
        }
        val old = previous
        val stable = old != null &&
            old.sourcePackage == sample.sourcePackage &&
            old.captureEpoch == sample.captureEpoch &&
            old.windowGeneration == sample.windowGeneration &&
            sample.frameSequence > old.frameSequence &&
            sample.capturedAtMs > old.capturedAtMs &&
            sample.capturedAtMs - old.capturedAtMs in 500L..5_000L &&
            old.holeCards == sample.holeCards &&
            sample.cardCapturedAtMs >= old.cardCapturedAtMs
        previous = sample
        return stable
    }
}
