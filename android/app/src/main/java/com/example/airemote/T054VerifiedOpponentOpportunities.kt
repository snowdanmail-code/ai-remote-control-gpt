package com.example.airemote

/**
 * T054 checkpoint 1. Read-only derivation from independently authenticated
 * action/opportunity history. It cannot create source trust, operate OCR,
 * authorize a bet, or infer a missing action as FOLD.
 *
 * A complete preflop sequence means the provider attests all of this actor's
 * preflop events, including forced posts, and confirms this actor was dealt in.
 * A sparse OCR event sequence MUST NOT set that flag.
 */
enum class T054PreflopEventKind { POST_BLIND, ANTE, CHECK, CALL, RAISE, FOLD }
enum class T054FacingBetChoice { FOLD, CALL, RAISE }

data class T054VerifiedPreflopEvent(
    val sourceEventId: String,
    val action: T054PreflopEventKind,
    val independentlyVerified: Boolean
)
data class T054VerifiedFacingBetDecision(
    val sourceOpportunityId: String,
    val response: T054FacingBetChoice,
    val independentlyVerified: Boolean
)
data class T054OpponentOpportunityTranscript(
    val opponentKey: String,
    val identityIndependentlyVerified: Boolean,
    val sourceIndependentlyVerified: Boolean,
    val dealtInPreflopIndependentlyVerified: Boolean,
    val completePreflopSequenceIndependentlyVerified: Boolean,
    val preflop: List<T054VerifiedPreflopEvent>,
    val facingBet: List<T054VerifiedFacingBetDecision>
)
data class T054OpponentOpportunityResult(
    val handFacts: OpponentHandFacts,
    /** Per-opportunity counts for future schema migration, not yet stored. */
    val verifiedFacingBetOpportunities: Int,
    val verifiedFoldsToBet: Int,
    val preflopOpportunityConfirmed: Boolean,
    val reason: String
)

object T054VerifiedOpponentOpportunities {
    /**
     * Null means invalid/mixed evidence. Unknown remains null, not false.
     * No live producer is wired: only a signed game-history provider may
     * create complete histories. This is an offline math/consistency helper.
     */
    fun derive(source: T054OpponentOpportunityTranscript): T054OpponentOpportunityResult? {
        if (!source.sourceIndependentlyVerified ||
            source.opponentKey.isBlank() || source.opponentKey.length > 128 ||
            source.opponentKey.any(Char::isISOControl) ||
            source.preflop.size > 40 || source.facingBet.size > 40) return null
        if (source.preflop.any { !it.independentlyVerified ||
                it.sourceEventId.isBlank() || it.sourceEventId.length > 128 } ||
            source.facingBet.any { !it.independentlyVerified ||
                it.sourceOpportunityId.isBlank() || it.sourceOpportunityId.length > 128 } ||
            source.preflop.map { it.sourceEventId }.distinct().size != source.preflop.size ||
            source.facingBet.map { it.sourceOpportunityId }.distinct().size != source.facingBet.size)
            return null

        // A known CALL/RAISE proves positive VPIP/PFR. A negative requires the
        // provider's COMPLETE actor-level preflop transcript, not the absence
        // of OCR labels or missing screenshots.
        val entered = source.preflop.any {
            it.action in setOf(T054PreflopEventKind.CALL, T054PreflopEventKind.RAISE)
        }
        val raised = source.preflop.any { it.action == T054PreflopEventKind.RAISE }
        val fullPre = source.dealtInPreflopIndependentlyVerified &&
            source.completePreflopSequenceIndependentlyVerified
        if (source.completePreflopSequenceIndependentlyVerified &&
            !source.dealtInPreflopIndependentlyVerified) return null
        val vpip = when {
            entered -> true
            fullPre -> false
            else -> null
        }
        val pfr = when {
            raised -> true
            fullPre -> false
            else -> null
        }
        val opps = source.facingBet.size
        val folded = source.facingBet.count { it.response == T054FacingBetChoice.FOLD }
        // Existing storage has one Boolean per HAND. Two separate facing-bet
        // decisions cannot be losslessly represented in that field, so retain
        // aggregate counts for future v3 migration and mark the v2 field UNKNOWN.
        val foldInV2: Boolean? = if (opps == 1) folded == 1 else null
        return T054OpponentOpportunityResult(
            handFacts = OpponentHandFacts(
                opponentKey = source.opponentKey,
                identityVerified = source.identityIndependentlyVerified,
                vpip = vpip,
                preflopRaise = pfr,
                facedBet = if (opps > 0) true else null,
                foldedWhenFacingBet = foldInV2
            ),
            verifiedFacingBetOpportunities = opps,
            verifiedFoldsToBet = folded,
            preflopOpportunityConfirmed = fullPre,
            reason = if (fullPre) "VERIFIED_COMPLETE_PREFLOP" else "PARTIAL_PREFLOP_UNKNOWN_NEGATIVES"
        )
    }
}
