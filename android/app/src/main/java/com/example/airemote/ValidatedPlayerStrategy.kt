package com.example.airemote

/**
 * Player intelligence is a model-independent LOCAL strategy by default.
 * An optional ChatGPT proposal is just a candidate tied to ONE signed frame;
 * it is never a command, gesture, game-state proof or provider signature.
 */
class TurnScopedProposalMailbox {
    private var pending: PlayerModelProposal? = null
    @Synchronized fun offer(value: PlayerModelProposal): Boolean {
        if (pending != null || value.handId.isBlank() || value.turnId.isBlank() ||
            value.captureEpoch < 0 || value.frameSequence <= 0 ||
            value.expiresAtMs <= value.proposedAtMs) return false
        pending = value
        return true
    }
    @Synchronized fun take(): PlayerModelProposal? {
        val result=pending
        pending=null
        return result
    }
    @Synchronized fun clear() { pending=null }
}

class ValidatedPlayerStrategy(
    private val local: AutonomousStrategy = VerifiedLocalPokerStrategy(),
    private val proposals: TurnScopedProposalMailbox = TurnScopedProposalMailbox(),
    private val nowMs: () -> Long
): AutonomousStrategy {
    override fun decide(observation: AutonomousObservation): AutonomousDecision? {
        val localDecision = local.decide(observation)
        val proposed = proposals.take() ?: return localDecision
        val reviewed = PlayerModelDecisionGuard.inspect(observation,proposed,nowMs())
        if (reviewed.status != PlayerProposalStatus.ACCEPTABLE_CANDIDATE)
            return localDecision
        val candidate = reviewed.candidate ?: return localDecision
        // A model cannot manufacture equity, stake ranges, legality or timer.
        // Only an explicitly verified independent frame-bound equity allows
        // an action beyond zero-chip CHECK.
        val freeCheck = candidate.action == LiveAction.CHECK &&
            observation.callChips == 0L
        val trustedMath = observation.equityEvidence?.matches(observation) == true
        return if (freeCheck || trustedMath) candidate else localDecision
    }
}
