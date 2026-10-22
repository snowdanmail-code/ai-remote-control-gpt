package com.example.airemote

/**
 * A locally-running decision provider for operator-confirmed virtual-chip poker.
 * It is NOT ChatGPT, a proof of game state, or an OCR recognizer. It cannot tap.
 * The caller must prove cards, observation identity and an independent equity
 * estimate on exactly this captured turn. No silent estimate from unknown hands.
 */
data class FrameBoundEquity(
    val handId: String,
    val turnId: String,
    val captureEpoch: Long,
    val frameSequence: Long,
    val potShare: Double,
    val independentSampleCount: Int,
    /** Exact enumeration can have fewer than 200 possible runouts (e.g. river). */
    /** True only if source independently verified cards and equity provenance. */
    val externallyVerified: Boolean,
    val exactEnumeration: Boolean = false,
    /** Explicit model assumption: a signed input does not reveal hidden opponents. */
    val provenance: String = "EXTERNALLY_VERIFIED_EQUITY"
) {
    fun matches(observation: AutonomousObservation): Boolean =
        externallyVerified && independentSampleCount >= (if (exactEnumeration) 1 else 200) &&
            potShare.isFinite() && potShare in 0.0..1.0 &&
            handId == observation.handId && turnId == observation.turnId &&
            captureEpoch == observation.view.captureEpoch &&
            frameSequence == observation.view.frameSequence
}

/**
 * Conservative local strategy: with no trustworthy equity it can only CHECK
 * for free, or defer. With fresh verified equity it can CALL/FOLD/BET/RAISE.
 * The verified selected size is required for every bet/raise. No blind slider taps.
 * Amounts use game chips; display currency symbols are just OCR tokens.
 */
class VerifiedLocalPokerStrategy : AutonomousStrategy {
    override fun decide(observation: AutonomousObservation): AutonomousDecision? {
        val legal = observation.view.legalActions
        val nowTurn = observation.turnId
        if (nowTurn.isBlank() || !observation.view.turnVerified ||
            !observation.view.exactLabelsVerified ||
            !observation.tableHandIndependentlyVerified || !observation.windowIndependentlyVerified ||
            observation.timerRemainingMs == null || observation.timerRemainingMs < 1600L ||
            observation.potChips == null || observation.potChips < 0L ||
            observation.callChips == null || observation.callChips < 0L) return null

        val evidence = observation.equityEvidence
        val equity = evidence?.takeIf { it.matches(observation) }?.potShare
        // A check is reversible in risk only insofar as it contributes no chips.
        // Never assume unknown equity is adequate for a paid action.
        if (equity == null) return if (LiveAction.CHECK in legal && observation.callChips == 0L)
            AutonomousDecision(LiveAction.CHECK, nowTurn, "Local: no trusted equity, free check")
        else null

        val required = try {
            PokerMath.potOddsRequiredEquity(observation.potChips, observation.callChips)
        } catch (_: IllegalArgumentException) { return null }
        val minBet = observation.minVerifiedBetChips
        val maxBet = observation.maxVerifiedBetChips
        val amount = observation.selectedBetChips
        val canRaise = amount != null && minBet != null && maxBet != null &&
            minBet > 0L && maxBet >= minBet && amount in minBet..maxBet
        if (equity >= 0.80 && canRaise) {
            if (observation.callChips > 0L && LiveAction.RAISE in legal)
                return AutonomousDecision(LiveAction.RAISE, nowTurn, "Local: strong verified equity", observation.selectedBetChips)
            if (observation.callChips == 0L && LiveAction.BET in legal)
                return AutonomousDecision(LiveAction.BET, nowTurn, "Local: strong verified equity", observation.selectedBetChips)
        }
        if (observation.callChips == 0L && LiveAction.CHECK in legal)
            return AutonomousDecision(LiveAction.CHECK, nowTurn, "Local: free check")
        if (LiveAction.CALL in legal && equity >= required + 0.06)
            return AutonomousDecision(LiveAction.CALL, nowTurn, "Local: verified equity covers pot odds")
        if (LiveAction.FOLD in legal && observation.callChips > 0L && equity < required + 0.06)
            return AutonomousDecision(LiveAction.FOLD, nowTurn, "Local: verified equity below risk threshold")
        return null
    }
}
