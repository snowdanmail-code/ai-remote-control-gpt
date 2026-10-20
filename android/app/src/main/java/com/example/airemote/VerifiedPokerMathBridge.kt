package com.example.airemote

/**
 * Read-only mathematical snapshot. It does NOT create independent identity proofs,
 * a strategy decision, or an authorization to touch the game.
 * An unknown OCR amount remains unknown (null), never silently converted to zero.
 */
data class VerifiedPokerMathInput(
    val tableId: String,
    val handId: String,
    val captureEpoch: Long,
    val frameSequence: Long,
    val independentTableHandProof: Boolean,
    val frameIsCurrent: Boolean,
    val phase: HandStage,
    val heroHoleCards: List<PokerCard>,
    val boardCards: List<PokerCard>,
    val potBeforeCall: Long? = null,
    val callChips: Long? = null,
    val currentStreetBet: Long? = null,
    val playerStreetCommitted: Long? = null,
    val stackBehind: Long? = null,
    val minimumFullRaiseIncrement: Long? = null
)

data class PokerMathEvidence(
    val stage: HandStage,
    val bestHand: PokerHandValue?,
    val potOddsRequiredEquity: Double?,
    val raiseBounds: PokerMath.RaiseBounds?
)

object VerifiedPokerMathBridge {
    /** Only diagnostics; the autonomous action guard remains the separate authority. */
    fun analyze(input: VerifiedPokerMathInput): PokerMathEvidence? {
        if (!input.independentTableHandProof || !input.frameIsCurrent ||
            input.tableId.isBlank() || input.handId.isBlank() ||
            input.captureEpoch <= 0L || input.frameSequence <= 0L ||
            input.heroHoleCards.size != 2) return null
        val expectedBoard = when (input.phase) {
            HandStage.PREFLOP -> 0
            HandStage.FLOP -> 3
            HandStage.TURN -> 4
            HandStage.RIVER -> 5
            else -> return null
        }
        if (input.boardCards.size != expectedBoard) return null
        if ((input.heroHoleCards + input.boardCards).toSet().size != input.heroHoleCards.size + input.boardCards.size)
            return null
        return try {
            val hand = if (expectedBoard == 0) null else PokerMath.evaluate(input.heroHoleCards + input.boardCards)
            val odds = if (input.potBeforeCall != null && input.callChips != null)
                PokerMath.potOddsRequiredEquity(input.potBeforeCall, input.callChips) else null
            val raise = if (listOf(input.currentStreetBet, input.playerStreetCommitted,
                    input.stackBehind, input.minimumFullRaiseIncrement).all { it != null }) {
                PokerMath.raiseBounds(requireNotNull(input.currentStreetBet),
                    requireNotNull(input.playerStreetCommitted), requireNotNull(input.stackBehind),
                    requireNotNull(input.minimumFullRaiseIncrement))
            } else null
            PokerMathEvidence(input.phase, hand, odds, raise)
        } catch (_: IllegalArgumentException) { null
        } catch (_: ArithmeticException) { null }
    }
}
