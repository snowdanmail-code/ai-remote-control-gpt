package com.example.airemote

/**
 * Stage T054 checkpoint 3: frame scoped, read-only poker context for the
 * existing GPT recommendation contract. This envelope is NOT an attestation.
 * A future authenticated producer must validate its own source before making
 * this envelope; constructing it does not confer trust or gesture permission.
 */
data class T054GptTurnBoundContext(
    val hand: T054ReadOnlyHandContext,
    val turnId: String,
    val captureEpoch: Long,
    val frameSequence: Long,
    val windowGeneration: Long,
    val capturedAtMs: Long
) {
    /** Checks current-frame binding, NOT cryptographic source authenticity. */
    fun matches(observation: AutonomousObservation): Boolean {
        val view = observation.view
        val expectedStreet = when (observation.phase) {
            HandStage.PREFLOP -> PokerStreet.PREFLOP
            HandStage.FLOP -> PokerStreet.FLOP
            HandStage.TURN -> PokerStreet.TURN
            HandStage.RIVER -> PokerStreet.RIVER
            else -> return false
        }
        if (hand.source.tableId != observation.tableId ||
            hand.source.handId != observation.handId ||
            hand.source.sessionId.isNullOrBlank() ||
            hand.source.namespace.isBlank() ||
            hand.street != expectedStreet ||
            turnId != observation.turnId ||
            captureEpoch != view.captureEpoch ||
            frameSequence != view.frameSequence ||
            windowGeneration != observation.windowGeneration ||
            capturedAtMs != view.capturedAtMs ||
            !observation.tableHandIndependentlyVerified ||
            !observation.windowIndependentlyVerified ||
            !view.foregroundVerified || !view.stageVerified ||
            !view.turnVerified || !view.exactLabelsVerified) return false

        // No contradictory numeric facts across old and new state envelopes.
        if (observation.potChips != null &&
            hand.potBeforeHeroActionChips != null &&
            observation.potChips != hand.potBeforeHeroActionChips) return false
        if (observation.callChips != null &&
            hand.costToCallChips != null &&
            observation.callChips != hand.costToCallChips) return false
        // The canonical pipeline cannot assert a complete history yet.
        // Never accept an "all known" snapshot if it hides missing actions.
        if ("ACTION_HISTORY_COMPLETENESS" !in hand.unknownFields) return false
        if (hand.history.size > 120 ||
            hand.opponents.size > 8 ||
            hand.history.zipWithNext().any { (a, b) -> a.sequence >= b.sequence } ||
            hand.history.any { it.actorSeat != null && it.actorSeat !in 0..8 } ||
            !actionStreetsFitContext(hand.history.asSequence().map { it.street }, hand.street))
            return false
        return true
    }
}
