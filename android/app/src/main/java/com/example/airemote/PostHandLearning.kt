package com.example.airemote

/**
 * Verified facts from one finished, real table hand. Unknown facts are null, never
 * guessed as false. No claim that the pixel recognizer supplies these facts yet.
 */
data class OpponentHandFacts(
    val opponentKey: String,
    /** Only a reliable session-scoped identity may be used for cross-hand aggregation. */
    val identityVerified: Boolean,
    val vpip: Boolean? = null,
    val preflopRaise: Boolean? = null,
    val postflopBetsOrRaises: Int? = null,
    val postflopCalls: Int? = null,
    val facedBet: Boolean? = null,
    val foldedWhenFacingBet: Boolean? = null,
    val showdown: Boolean? = null,
    val showdownWon: Boolean? = null
)

data class VerifiedExecutedAction(
    val actionKey: String,
    val street: String,
    val action: String,
    val frameSequence: Long,
    val executionConfirmed: Boolean,
    val amountChips: Double? = null
)

data class VerifiedFinishedHand(
    val tableKey: String,
    /** Stable identifier for this exact hand, NOT an arbitrary screenshot sequence. */
    val handKey: String,
    val captureEpoch: Long,
    val firstFrame: Long,
    val terminalFrame: Long,
    val finishedAtElapsedMs: Long,
    val sourceVerified: Boolean,
    val terminalConfirmedByIndependentFrames: Boolean,
    val opponents: List<OpponentHandFacts>,
    val verifiedHeroActions: List<VerifiedExecutedAction> = emptyList(),
    /** Null means that the result was not reliably observed. */
    val heroNetChips: Double? = null,
    /**
     * Optional, separately authenticated complete opponent opportunity histories.
     * No OCR collector can supply these; caller must bind them to this
     * already verified table/hand receipt before invoking the learner.
     */
    val verifiedOpponentOpportunities: List<T054OpponentOpportunityTranscript> = emptyList()
)

data class OpponentLearningSummary(
    val opponentKey: String,
    val observedHands: Int,
    val vpipSeen: Int,
    val vpipOpportunities: Int,
    val pfrSeen: Int,
    val pfrOpportunities: Int,
    val aggressionActions: Int,
    val postflopCalls: Int,
    val foldsFacingBet: Int,
    val facedBetOpportunities: Int,
    val showdownsSeen: Int,
    val showdownsWon: Int
) {
    fun contextLine(): String {
        fun rate(a: Int, b: Int): String = if (b == 0) "недостаточно данных" else "$a/$b"
        val sampleWarning = if (observedHands < 20 || vpipOpportunities < 20 || pfrOpportunities < 20)
            "Малая/неполная выборка: стиль не определён. " else ""
        return "$opponentKey: раздач $observedHands, VPIP ${rate(vpipSeen, vpipOpportunities)}, " +
            "PFR ${rate(pfrSeen, pfrOpportunities)}, агрессия $aggressionActions действий, " +
            "коллы $postflopCalls, fold-vs-bet ${rate(foldsFacingBet, facedBetOpportunities)}, " +
            "вскрытия $showdownsSeen (победы $showdownsWon). " +
            sampleWarning + "Это наблюдения, не доказательство фиксированного стиля."
    }
}

enum class HandLearningResult { APPLIED, ALREADY_SEEN, REJECTED, STORAGE_ERROR }

/** Transactional contract: receipt + all opponent deltas must be committed together. */
interface HandLearningStore {
    fun commitHandAtomically(hand: VerifiedFinishedHand): HandLearningResult
    fun opponentProfile(opponentKey: String): OpponentLearningSummary?
    fun completedHandsCount(): Int
    fun recentHandOutcomes(limit: Int): List<String> = emptyList()
}

class PostHandLearner(private val store: HandLearningStore) {
    /** Called IMMEDIATELY on a verified terminal observation, not from a scheduled job. */
    @Synchronized fun onHandFinished(hand: VerifiedFinishedHand): HandLearningResult {
        if (!valid(hand)) return HandLearningResult.REJECTED
        val normalized = incorporateVerifiedOpportunities(hand)
            ?: return HandLearningResult.REJECTED
        if (!valid(normalized)) return HandLearningResult.REJECTED
        return try { store.commitHandAtomically(normalized) }
        catch (_: Exception) { HandLearningResult.STORAGE_ERROR }
    }

    private fun incorporateVerifiedOpportunities(hand: VerifiedFinishedHand): VerifiedFinishedHand? {
        val transcripts = hand.verifiedOpponentOpportunities
        if (transcripts.isEmpty()) return hand
        if (transcripts.size > hand.opponents.size ||
            transcripts.map { it.opponentKey }.distinct().size != transcripts.size)
            return null
        val original = hand.opponents.associateBy { it.opponentKey }
        val verified = transcripts.map { evidence ->
            val prior = original[evidence.opponentKey] ?: return null
            val calculated = T054VerifiedOpponentOpportunities.derive(evidence)
                ?: return null
            val derived = calculated.handFacts
            if (!prior.identityVerified || !derived.identityVerified ||
                (prior.vpip != null && derived.vpip != null && prior.vpip != derived.vpip) ||
                (prior.preflopRaise != null && derived.preflopRaise != null &&
                    prior.preflopRaise != derived.preflopRaise) ||
                (prior.facedBet != null && derived.facedBet != null &&
                    prior.facedBet != derived.facedBet) ||
                (calculated.verifiedFacingBetOpportunities > 1 &&
                    prior.foldedWhenFacingBet != null) ||
                (prior.foldedWhenFacingBet != null && derived.foldedWhenFacingBet != null &&
                    prior.foldedWhenFacingBet != derived.foldedWhenFacingBet))
                return null
            evidence.opponentKey to derived
        }.toMap()
        return hand.copy(
            opponents = hand.opponents.map { prior ->
                val d = verified[prior.opponentKey] ?: return@map prior
                prior.copy(
                    vpip = prior.vpip ?: d.vpip,
                    preflopRaise = prior.preflopRaise ?: d.preflopRaise,
                    facedBet = prior.facedBet ?: d.facedBet,
                    foldedWhenFacingBet = prior.foldedWhenFacingBet ?: d.foldedWhenFacingBet
                )
            },
            // Signed evidence is applied once into v2 facts. Keep the old
            // database and its exact once-per-hand transaction unchanged.
            verifiedOpponentOpportunities = emptyList()
        )
    }

    @Synchronized fun profileForNextDecision(opponentKey: String): OpponentLearningSummary? =
        try { store.opponentProfile(opponentKey) } catch (_: Exception) { null }

    /** Always assembled from freshly committed local data for the NEXT strategic request. */
    @Synchronized fun contextForNextDecision(opponentsAtTable: List<String>): String {
        val known = opponentsAtTable.distinct().take(6).mapNotNull { profileForNextDecision(it) }
        val recent = try { store.recentHandOutcomes(4) } catch (_: Exception) { emptyList() }
        return buildString {
            append("Накопленные ПОСЛЕ КАЖДОЙ раздачи наблюдения. Не считай их гарантией поведения.\n")
            known.forEach { append(it.contextLine()).append('\n') }
            recent.forEach { append(it.take(180)).append('\n') }
        }.take(2200)
    }

    private fun valid(hand: VerifiedFinishedHand): Boolean {
        if (!hand.sourceVerified || !hand.terminalConfirmedByIndependentFrames ||
            hand.tableKey.isBlank() || hand.handKey.isBlank() || hand.captureEpoch < 0 ||
            hand.firstFrame < 0 || hand.terminalFrame <= hand.firstFrame ||
            hand.finishedAtElapsedMs < 0 || hand.tableKey.length > 128 || hand.handKey.length > 128 ||
            !hand.heroNetChips.let { it == null || (it.isFinite() && kotlin.math.abs(it) <= 1e12) } ||
            hand.opponents.size > 12 || hand.verifiedHeroActions.size > 24 ||
            hand.verifiedOpponentOpportunities.size > 12) return false
        if (hand.opponents.map { it.opponentKey }.distinct().size != hand.opponents.size) return false
        if (hand.verifiedHeroActions.map { it.actionKey }.distinct().size != hand.verifiedHeroActions.size) return false
        if (!hand.verifiedHeroActions.all { d ->
                d.executionConfirmed && d.actionKey.isNotBlank() && d.actionKey.length <= 128 &&
                d.street in setOf("PREFLOP", "FLOP", "TURN", "RIVER") &&
                d.action in setOf("FOLD", "CHECK", "CALL", "BET", "RAISE") &&
                d.frameSequence in hand.firstFrame until hand.terminalFrame &&
                (d.amountChips == null || (d.amountChips.isFinite() && d.amountChips >= 0.0 && d.amountChips <= 1e12))
            }) return false
        return hand.opponents.all { o ->
            o.opponentKey.isNotBlank() && o.opponentKey.length <= 128 &&
            (o.postflopBetsOrRaises == null || o.postflopBetsOrRaises in 0..30) &&
            (o.postflopCalls == null || o.postflopCalls in 0..30) &&
            (o.foldedWhenFacingBet == null || o.facedBet == true) &&
            (o.showdownWon != true || o.showdown == true)
        }
    }
}
