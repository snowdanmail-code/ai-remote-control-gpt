package com.example.airemote

import kotlin.math.min

/**
 * T054 checkpoint 2: passive, deterministic reconstruction of a Hold'em turn.
 *
 * All arithmetic is read-only. An INDEPENDENT enum marker is not a signature;
 * callers still require an authenticated provider/verifier before using this
 * information outside offline analysis. Nothing here can authorize UI actions.
 */
data class T054IndependentBettingSnapshot(
    val source: PokerSourceIdentity,
    val street: PokerStreet,
    /** Pot displayed before HERO contributes the new call; includes prior wagers. */
    val potBeforeHeroActionChips: Long? = null,
    val costToCallChips: Long? = null,
    val bigBlindChips: Long? = null,
    /** Reported totals on this street, NOT amounts added by the last action. */
    val currentBetToChips: Long? = null,
    val heroCommittedOnStreetChips: Long? = null,
    val lastFullRaiseSizeChips: Long? = null,
    /**
     * Canonical 'stackChips' has no defined snapshot timing.
     * The provider must separately attest that every stack is remaining
     * behind at this exact decision, not pre-hand or before prior wagers.
     */
    val stacksAreRemainingBehind: Boolean = false,
    val evidence: PokerEvidence = PokerEvidence.UNKNOWN
) {
    fun numericallyValid(): Boolean =
        listOf(potBeforeHeroActionChips, costToCallChips, bigBlindChips,
            currentBetToChips, heroCommittedOnStreetChips, lastFullRaiseSizeChips)
            .all { it == null || it >= 0L } &&
            (bigBlindChips == null || bigBlindChips > 0L) &&
            (lastFullRaiseSizeChips == null || lastFullRaiseSizeChips > 0L) &&
            (currentBetToChips == null || heroCommittedOnStreetChips == null ||
                heroCommittedOnStreetChips <= currentBetToChips) &&
            // A short all-in may call LESS than outstanding street chips.
            // The builder checks its exact amount against HERO's verified stack.
            (currentBetToChips == null || heroCommittedOnStreetChips == null ||
                costToCallChips == null ||
                costToCallChips <= currentBetToChips - heroCommittedOnStreetChips)

    fun stableHand(): Boolean =
        source.tableId != null && source.sessionId != null && source.handId != null
}

/** Only the event order and reported amount are known; 'raise-to' vs delta is unknown. */
data class T054ReadOnlyAction(
    val sourceEventId: String,
    val sequence: Long,
    val actorSeat: Int?,
    val street: PokerStreet,
    val action: PokerActionKind,
    val sourceReportedAmountChips: Long?,
    val evidence: PokerEvidence,
    val amountSemanticsVerified: Boolean = false
)
data class T054EffectiveOpponent(
    val seatIndex: Int,
    val position: PokerSeatPosition?,
    val remainingStackChips: Long?,
    val effectiveRemainingStackChips: Long?,
    val effectiveRemainingStackBb: Double?,
    /** Remaining-stack-to-current-pot ratio, NOT pre-hand stack-to-pot. */
    val spr: Double?
)
data class T054ReadOnlyHandContext(
    val source: PokerSourceIdentity,
    val heroSeat: Int,
    val heroPosition: PokerSeatPosition?,
    val street: PokerStreet,
    val activeOpponents: Int?,
    val isMultiway: Boolean?,
    val heroRemainingStackChips: Long?,
    val heroRemainingStackBb: Double?,
    val bigBlindChips: Long?,
    val potBeforeHeroActionChips: Long?,
    val costToCallChips: Long?,
    val potOddsRequiredEquity: Double?,
    val opponents: List<T054EffectiveOpponent>,
    val minimumEffectiveRemainingStackChips: Long?,
    val minimumEffectiveRemainingSpr: Double?,
    /** Pure mathematical bound. No proof that the action is reopened/allowed. */
    val mathematicalRaiseBounds: PokerMath.RaiseBounds?,
    val history: List<T054ReadOnlyAction>,
    val unknownFields: Set<String>
) {
    fun diagnosticLine(): String =
        "T054_READ_ONLY; position=${heroPosition ?: "UNKNOWN"}; " +
        "active_opponents=${activeOpponents ?: "UNKNOWN"}; " +
        "hero_stack=${heroRemainingStackChips ?: "UNKNOWN"}; " +
        "effective_remaining=${minimumEffectiveRemainingStackChips ?: "UNKNOWN"}; " +
        "spr=${minimumEffectiveRemainingSpr ?: "UNKNOWN"}; " +
        "pot_odds=${potOddsRequiredEquity ?: "UNKNOWN"}; " +
        "actions=${history.size}; unknown=${unknownFields.sorted().joinToString(",")}"
}

/** UNKNOWN preserves partial history without resetting the last known street. */
internal fun actionStreetsFitContext(history: Sequence<PokerStreet>, current: PokerStreet): Boolean {
    var previous: PokerStreet? = null
    for (street in history) {
        if (street == PokerStreet.UNKNOWN) continue
        if (previous != null && street < previous) return false
        if (current != PokerStreet.UNKNOWN && street > current) return false
        previous = street
    }
    return true
}

object T054ReadOnlyHandContextBuilder {
    /**
     * Reject contradictory evidence. Unknown amounts/players stay null.
     * A table window, screenshot or model response is never a trusted producer.
     */
    fun build(
        observation: CanonicalPokerObservation,
        betting: T054IndependentBettingSnapshot
    ): T054ReadOnlyHandContext? {
        if (observation.rulesetId != PokerRulesets.STANDARD52_NO_LIMIT_V1 ||
            observation.observationEvidence != PokerEvidence.INDEPENDENT ||
            betting.evidence != PokerEvidence.INDEPENDENT ||
            !betting.stableHand() || observation.source != betting.source ||
            observation.street != betting.street ||
            !betting.numericallyValid() ||
            observation.source.tableId == null || observation.source.sessionId == null ||
            observation.source.handId == null) return null
        if (!actionStreetsFitContext(observation.actions.asSequence().map { it.street },
                observation.street)) return null
        val hero = observation.heroSeat ?: return null
        val seat = observation.seats.singleOrNull { it.seatIndex == hero } ?: return null
        val expectedBoard = when (observation.street) {
            PokerStreet.PREFLOP -> 0
            PokerStreet.FLOP -> 3
            PokerStreet.TURN -> 4
            PokerStreet.RIVER -> 5
            else -> null
        }
        // Known board mismatches cannot describe this street. Missing is unknown.
        if (expectedBoard != null && observation.boardCards != null &&
            observation.boardCards.size != expectedBoard) return null

        // Observation.potChips has no pre-/post-action semantic contract;
        // it may corroborate a supplied pre-action pot but cannot invent one.
        val pot = betting.potBeforeHeroActionChips
        if (betting.potBeforeHeroActionChips != null &&
            observation.potChips != null &&
            betting.potBeforeHeroActionChips != observation.potChips) return null
        val call = betting.costToCallChips
        val blind = betting.bigBlindChips
        val stacks = observation.seats
        val knownRoster = observation.seatingComplete &&
            stacks.all { it.dealtIn != null && it.activeInHand != null }
        val active = if (knownRoster && seat.activeInHand == true)
            stacks.filter { it.activeInHand == true && it.seatIndex != hero }
        else emptyList()
        val count = if (knownRoster && seat.activeInHand == true) active.size else null
        val positions = observation.positions()
        val heroStack = if (betting.stacksAreRemainingBehind) seat.stackChips else null
        val streetOutstanding = if (betting.currentBetToChips != null &&
            betting.heroCommittedOnStreetChips != null)
            betting.currentBetToChips - betting.heroCommittedOnStreetChips else null
        // Cost of CALL for a short stack is min(outstanding, stack behind).
        // If stack is unknown, a smaller call cannot be independently justified.
        if (call != null && heroStack != null && call > heroStack) return null
        if (streetOutstanding != null && call != null &&
            call != (heroStack?.let { min(streetOutstanding, it) } ?: streetOutstanding)) return null
        // If the street total is missing, a positive call is known to be full
        // only when it leaves chips behind. Otherwise a short call is possible.
        val eligiblePotUnknown = call != null &&
            if (streetOutstanding != null) call < streetOutstanding
            else call > 0L && (heroStack == null || call == heroStack)
        fun toBb(chips: Long?): Double? =
            if (chips == null || blind == null) null else chips.toDouble() / blind.toDouble()
        fun spr(chips: Long?): Double? =
            if (chips == null || pot == null || pot == 0L) null
            else chips.toDouble() / pot.toDouble()

        val opponentContexts = active.map {
            val opponentBehind = if (betting.stacksAreRemainingBehind)
                it.stackChips else null
            val effective = if (heroStack != null && opponentBehind != null)
                min(heroStack, opponentBehind) else null
            T054EffectiveOpponent(
                seatIndex = it.seatIndex,
                position = positions[it.seatIndex],
                remainingStackChips = opponentBehind,
                effectiveRemainingStackChips = effective,
                effectiveRemainingStackBb = toBb(effective),
                spr = spr(effective)
            )
        }
        val minimumEffective = if (count != null && count > 0 &&
            opponentContexts.all { it.effectiveRemainingStackChips != null })
            opponentContexts.minOf { requireNotNull(it.effectiveRemainingStackChips) }
        else null
        val raiseBounds = if (heroStack != null &&
            betting.currentBetToChips != null &&
            betting.heroCommittedOnStreetChips != null &&
            betting.lastFullRaiseSizeChips != null)
            try {
                PokerMath.raiseBounds(
                    betting.currentBetToChips, betting.heroCommittedOnStreetChips,
                    heroStack, betting.lastFullRaiseSizeChips)
            } catch (_: ArithmeticException) { return null }
        else null

        val unknown = linkedSetOf<String>()
        if (positions[hero] == null) unknown.add("HERO_POSITION")
        if (count == null) unknown.add("ACTIVE_ROSTER")
        if (!betting.stacksAreRemainingBehind) unknown.add("STACK_TIMING_SEMANTICS")
        if (heroStack == null) unknown.add("HERO_STACK")
        if (blind == null) unknown.add("BIG_BLIND")
        if (pot == null) unknown.add("POT")
        if (call == null) unknown.add("CALL_COST")
        if (eligiblePotUnknown) unknown.add("ALL_IN_ELIGIBLE_POT")
        if (observation.boardCards == null) unknown.add("BOARD")
        if (count != null && count > 0 && minimumEffective == null)
            unknown.add("ACTIVE_OPPONENT_STACK")
        if (pot == 0L) unknown.add("SPR_ZERO_POT")
        if (raiseBounds == null) unknown.add("RAISE_BOUNDS")
        // CanonicalPokerObservation never asserts that no actions are omitted.
        // Even an empty event list is not evidence that nobody acted.
        unknown.add("ACTION_HISTORY_COMPLETENESS")
        if (observation.actions.any { it.evidence != PokerEvidence.INDEPENDENT })
            unknown.add("ACTION_HISTORY_UNVERIFIED_EVENTS")
        if (observation.actions.any { it.amountChips != null })
            unknown.add("HISTORY_AMOUNT_SEMANTICS")

        // A displayed total pot can contain side pots a short-all-in HERO
        // cannot win. Never offer misleading pot odds for that case.
        val odds = if (pot != null && call != null && !eligiblePotUnknown)
            PokerMath.potOddsRequiredEquity(pot, call) else null
        return T054ReadOnlyHandContext(
            source = observation.source,
            heroSeat = hero,
            heroPosition = positions[hero],
            street = observation.street,
            activeOpponents = count,
            isMultiway = count?.let { it >= 2 },
            heroRemainingStackChips = heroStack,
            heroRemainingStackBb = toBb(heroStack),
            bigBlindChips = blind,
            potBeforeHeroActionChips = pot,
            costToCallChips = call,
            potOddsRequiredEquity = odds,
            opponents = opponentContexts,
            minimumEffectiveRemainingStackChips = minimumEffective,
            minimumEffectiveRemainingSpr = spr(minimumEffective),
            mathematicalRaiseBounds = raiseBounds,
            history = observation.actions.map {
                T054ReadOnlyAction(it.eventId, it.sequence, it.actorSeat, it.street,
                    it.kind, it.amountChips, it.evidence)
            },
            unknownFields = unknown
        )
    }
}
