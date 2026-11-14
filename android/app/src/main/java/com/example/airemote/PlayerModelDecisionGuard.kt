package com.example.airemote

data class VerifiedDecisionCards(val hole: List<String>, val board: List<String>)

/** Facts sent to GPT that must still hold on a subsequent authenticated frame. */
data class ModelDecisionFrameContext(
    val capturedAtMs: Long,
    val tableId: String,
    val foregroundPackage: String,
    val fingerprint: String,
    val stage: HandStage,
    val cards: VerifiedDecisionCards,
    val legalActions: Set<LiveAction>,
    val potChips: Long?,
    val callChips: Long?,
    val selectedBetChips: Long?,
    val minBetChips: Long?,
    val maxBetChips: Long?,
    val equity: FrameBoundEquity?
) {
    companion object {
        const val FRAME_MAX_AGE_MS = 1_000L

        fun from(observation: AutonomousObservation): ModelDecisionFrameContext? {
            val cards = observation.independentlyMatchedCards ?: return null
            val view = observation.view
            if (view.capturedAtMs < 0L || view.stateFingerprint.isBlank()) return null
            return ModelDecisionFrameContext(view.capturedAtMs, observation.tableId,
                observation.foregroundPackage, view.stateFingerprint, observation.phase,
                VerifiedDecisionCards(cards.hole.toList(), cards.board.toList()),
                view.legalActions.toSet(), observation.potChips, observation.callChips,
                observation.selectedBetChips, observation.minVerifiedBetChips,
                observation.maxVerifiedBetChips,
                observation.equityEvidence?.takeIf { it.matches(observation) }
                    ?.copy(frameSequence = 0L))
        }
    }

    fun matchesNext(observation: AutonomousObservation, nowMs: Long): Boolean {
        val current = from(observation) ?: return false
        if (current.capturedAtMs <= capturedAtMs || current.capturedAtMs > nowMs ||
            nowMs - capturedAtMs > FRAME_MAX_AGE_MS) return false
        return current.copy(capturedAtMs = capturedAtMs) == this
    }
}

/**
 * Future GPT player suggestions can only enter the existing trusted decision
 * loop after frame/hand/turn/amount validation. This gate has NO network
 * connection, OAuth token, gesture transport or source-attestation powers.
 * A passing proposal alone must NOT authorize a tap.
 */
data class PlayerModelProposal(
    val action: LiveAction,
    val handId: String,
    val turnId: String,
    val captureEpoch: Long,
    val frameSequence: Long,
    val windowGeneration: Long,
    val proposedAtMs: Long,
    val expiresAtMs: Long,
    val amountChips: Long? = null,
    val originalFrameContext: ModelDecisionFrameContext? = null
)

enum class PlayerProposalStatus {
    ACCEPTABLE_CANDIDATE, UNVERIFIED_SOURCE, WRONG_HAND_OR_TURN,
    STALE_OR_FUTURE_RESPONSE, NOT_LEGAL, BUTTON_UNVERIFIED, UNSAFE_AMOUNT
}

data class PlayerProposalReview(
    val status: PlayerProposalStatus,
    val candidate: AutonomousDecision? = null
) {
    val authorizesGesture: Boolean get() = false
}

object PlayerModelDecisionGuard {
    fun inspect(obs: AutonomousObservation, proposed: PlayerModelProposal,
                nowMs: Long): PlayerProposalReview {
        fun refuse(s: PlayerProposalStatus) = PlayerProposalReview(s)
        val view = obs.view
        if (!obs.tableHandIndependentlyVerified || !obs.windowIndependentlyVerified ||
            !view.foregroundVerified || !view.stageVerified ||
            !view.turnVerified || !view.exactLabelsVerified)
            return refuse(PlayerProposalStatus.UNVERIFIED_SOURCE)
        if (proposed.handId != obs.handId || proposed.turnId != obs.turnId ||
            proposed.captureEpoch != view.captureEpoch ||
            proposed.windowGeneration != obs.windowGeneration ||
            view.handKey != obs.handId)
            return refuse(PlayerProposalStatus.WRONG_HAND_OR_TURN)
        if (proposed.frameSequence != view.frameSequence &&
            (view.frameSequence <= proposed.frameSequence ||
                proposed.originalFrameContext?.matchesNext(obs, nowMs) != true))
            return refuse(PlayerProposalStatus.WRONG_HAND_OR_TURN)
        if (nowMs < 0L || proposed.proposedAtMs > nowMs ||
            proposed.expiresAtMs <= nowMs ||
            proposed.expiresAtMs < proposed.proposedAtMs ||
            proposed.expiresAtMs - proposed.proposedAtMs > 1200L ||
            view.capturedAtMs > nowMs ||
            nowMs - view.capturedAtMs > 1200L ||
            obs.timerRemainingMs == null || obs.timerRemainingMs < 1600L)
            return refuse(PlayerProposalStatus.STALE_OR_FUTURE_RESPONSE)
        if (proposed.action !in view.legalActions)
            return refuse(PlayerProposalStatus.NOT_LEGAL)
        val button = obs.availableTaps[proposed.action]
        if (button == null || button.exactAction != proposed.action ||
            button.foregroundPackage != obs.foregroundPackage ||
            button.windowGeneration != obs.windowGeneration)
            return refuse(PlayerProposalStatus.BUTTON_UNVERIFIED)
        if (proposed.action in setOf(LiveAction.BET, LiveAction.RAISE)) {
            val size = proposed.amountChips
            val floor = obs.minVerifiedBetChips
            val ceil = obs.maxVerifiedBetChips
            if (size == null || floor == null || ceil == null ||
                floor <= 0L || ceil < floor ||
                size !in floor..ceil || size != obs.selectedBetChips)
                return refuse(PlayerProposalStatus.UNSAFE_AMOUNT)
        } else if (proposed.amountChips != null) return refuse(PlayerProposalStatus.UNSAFE_AMOUNT)
        // The EXISTING verified admission/gesture path still makes its own
        // independent proof, STOP, provenance and UI geometry checks.
        return PlayerProposalReview(PlayerProposalStatus.ACCEPTABLE_CANDIDATE,
            AutonomousDecision(proposed.action, obs.turnId,
                "Candidate from model; requires independent dispatch admission",
                proposed.amountChips))
    }
}
