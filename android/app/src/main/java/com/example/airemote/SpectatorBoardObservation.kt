package com.example.airemote

/**
 * Read-only observations of OPEN, community cards while the operator is not seated.
 * This path is deliberately independent of hero hole cards, legal buttons,
 * hand identity and every automation gate.
 */
data class VisibleCommunityReading(
    val cards: List<String?>,
    val complete: Boolean,
    val reason: String
)

data class StableSpectatorBoard(
    val cards: List<String>,
    val captureEpoch: Long,
    val frameSequence: Long,
    val capturedAtMs: Long,
    val sourcePackage: String
) {
    /** Viewing these cards never authorizes a gesture or poker action. */
    val authorizesAction: Boolean get() = false
}

class SpectatorBoardConsensus {
    private var previous: StableSpectatorBoard? = null
    private var consecutive = 0

    @Synchronized fun reset() { previous = null; consecutive = 0 }

    /** Two different fresh frames, same package, same open board; no guessing. */
    @Synchronized fun accept(
        reading: VisibleCommunityReading,
        captureEpoch: Long,
        frameSequence: Long,
        capturedAtMs: Long,
        observedAtMs: Long,
        sourcePackage: String?
    ): StableSpectatorBoard? {
        val cards = reading.cards.filterNotNull()
        val valid = reading.complete && reading.cards.size in 3..5 &&
            cards.size == reading.cards.size &&
            cards.toSet().size == cards.size &&
            cards.all { Regex("^[2-9TJQKA][cdhs]$").matches(it) } &&
            captureEpoch >= 0 && frameSequence > 0 && capturedAtMs >= 0 &&
            observedAtMs >= capturedAtMs && observedAtMs - capturedAtMs <= 1_500L &&
            !sourcePackage.isNullOrBlank()
        if (!valid) { reset(); return null }

        val current = StableSpectatorBoard(cards, captureEpoch, frameSequence,
            capturedAtMs, requireNotNull(sourcePackage))
        val old = previous
        val same = old != null &&
            old.captureEpoch == current.captureEpoch &&
            old.sourcePackage == current.sourcePackage &&
            current.frameSequence > old.frameSequence &&
            current.capturedAtMs >= old.capturedAtMs &&
            current.capturedAtMs - old.capturedAtMs <= 3_000L &&
            current.cards == old.cards
        consecutive = if (same) consecutive + 1 else 1
        previous = current
        return if (consecutive >= 2) current else null
    }
}

/** Unverified, read-only chips shown on the public table. NOT an opponent
 * attribution, betting event, hand identity, or action permission. */
data class SpectatorNumericHint(
    val captureEpoch: Long,
    val frameSequence: Long,
    val potCandidateChips: Long?,
    val callLabelCandidateChips: Long?,
    val rejectedLabels: Int,
    val completedAtMs: Long
) {
    val authorizesAction: Boolean get() = false
    fun summary(): String = "Наблюдатель: банк (OCR, НЕ подтверждён) " +
        (potCandidateChips?.toString() ?: "?") +
        "; числовая подпись CALL (НЕ действие) " +
        (callLabelCandidateChips?.toString() ?: "?") +
        "; отброшено неоднозначных чисел: $rejectedLabels. Без касаний."
}

