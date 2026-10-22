package com.example.airemote

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
    val amountChips: Long? = null
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
            proposed.frameSequence != view.frameSequence ||
            proposed.windowGeneration != obs.windowGeneration ||
            view.handKey != obs.handId)
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
