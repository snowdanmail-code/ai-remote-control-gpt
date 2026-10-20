package com.example.airemote

/**
 * A single, testable diagnostic gate: all sources must be independent,
 * fresh and agree on the same capture epoch before the app can even *consider*
 * asking the strategy model for a decision. This does NOT authorize touching
 * any third-party application (separate legal action and platform gates).
 */
enum class GameReadinessReason {
    STOPPED, NO_CAPTURE, UNKNOWN_APP, UNKNOWN_TABLE, OLD_CARD_FRAME,
    OLD_TURN_FRAME, WRONG_CAPTURE, UNCONFIRMED_CARDS,
    UNCONFIRMED_PHASE, NOT_OUR_TURN, ACTION_LABEL_UNKNOWN,
    STRATEGY_NOT_CONNECTED, READY_FOR_STRATEGY
}

data class GameReadinessInput(
    val stopped: Boolean,
    val captureActive: Boolean,
    val appVerified: Boolean,
    val tableVerified: Boolean,
    val cardEpoch: Long,
    val turnEpoch: Long,
    val currentEpoch: Long,
    val cardObservedAtMs: Long,
    val turnObservedAtMs: Long,
    val nowMs: Long,
    val cardsConfirmed: Boolean,
    val handStageConfirmed: Boolean,
    val ourTurnConfirmed: Boolean,
    val exactActionLabelsConfirmed: Boolean,
    val strategyConnected: Boolean
)

object GameReadinessStatus {
    const val MAX_CARDS_AGE_MS = 1_700L
    const val MAX_TURN_AGE_MS = 1_700L

    fun inspect(input: GameReadinessInput): GameReadinessReason {
        if (input.stopped) return GameReadinessReason.STOPPED
        if (!input.captureActive) return GameReadinessReason.NO_CAPTURE
        if (!input.appVerified) return GameReadinessReason.UNKNOWN_APP
        if (!input.tableVerified) return GameReadinessReason.UNKNOWN_TABLE
        if (input.cardEpoch < 0L || input.turnEpoch < 0L ||
            input.currentEpoch != input.cardEpoch || input.currentEpoch != input.turnEpoch)
            return GameReadinessReason.WRONG_CAPTURE
        if (!fresh(input.cardObservedAtMs, input.nowMs, MAX_CARDS_AGE_MS))
            return GameReadinessReason.OLD_CARD_FRAME
        if (!fresh(input.turnObservedAtMs, input.nowMs, MAX_TURN_AGE_MS))
            return GameReadinessReason.OLD_TURN_FRAME
        if (!input.cardsConfirmed) return GameReadinessReason.UNCONFIRMED_CARDS
        if (!input.handStageConfirmed) return GameReadinessReason.UNCONFIRMED_PHASE
        if (!input.ourTurnConfirmed) return GameReadinessReason.NOT_OUR_TURN
        if (!input.exactActionLabelsConfirmed) return GameReadinessReason.ACTION_LABEL_UNKNOWN
        if (!input.strategyConnected) return GameReadinessReason.STRATEGY_NOT_CONNECTED
        return GameReadinessReason.READY_FOR_STRATEGY
    }

    private fun fresh(observedAt: Long, now: Long, maxAge: Long): Boolean =
        observedAt >= 0L && now >= observedAt && now - observedAt <= maxAge
}
