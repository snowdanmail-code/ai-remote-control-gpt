package com.example.airemote

/** Strictly read-only board progression diagnostics; NOT a hand identity detector. */
enum class SpectatorBoardEvent {
    FIRST_VISIBLE_FLOP, FIRST_VISIBLE_TURN, FIRST_VISIBLE_RIVER,
    STABLE_UNCHANGED, POSSIBLE_FLOP_TO_TURN, POSSIBLE_TURN_TO_RIVER,
    POSSIBLE_SKIPPED_STREET, POSSIBLE_NEW_HAND, BOARD_REPLACED_UNVERIFIED
}

class SpectatorBoardTimeline {
    private var epoch = -1L
    private var source = ""
    private var last: List<String>? = null

    @Synchronized fun reset() { epoch = -1L; source = ""; last = null }

    /** Never asserts showdown, winners, opponent actions, or finished hands. */
    @Synchronized fun observe(board: StableSpectatorBoard): SpectatorBoardEvent {
        if (epoch != board.captureEpoch || source != board.sourcePackage) {
            epoch = board.captureEpoch
            source = board.sourcePackage
            last = null
        }
        val old = last
        last = board.cards.toList()
        return when {
            old == null -> when (board.cards.size) {
                3 -> SpectatorBoardEvent.FIRST_VISIBLE_FLOP
                4 -> SpectatorBoardEvent.FIRST_VISIBLE_TURN
                else -> SpectatorBoardEvent.FIRST_VISIBLE_RIVER
            }
            old == board.cards -> SpectatorBoardEvent.STABLE_UNCHANGED
            board.cards.size == old.size + 1 && board.cards.take(old.size) == old ->
                if (old.size == 3) SpectatorBoardEvent.POSSIBLE_FLOP_TO_TURN
                else SpectatorBoardEvent.POSSIBLE_TURN_TO_RIVER
            board.cards.size == old.size + 2 && board.cards.take(old.size) == old ->
                SpectatorBoardEvent.POSSIBLE_SKIPPED_STREET
            board.cards.size == 3 && old.size >= 3 && board.cards != old ->
                SpectatorBoardEvent.POSSIBLE_NEW_HAND
            else -> SpectatorBoardEvent.BOARD_REPLACED_UNVERIFIED
        }
    }
}

/**
 * A/B result for TWO observer-only algorithms on the same source.
 * Agreement is not accuracy, and neither algorithm receives gesture permission.
 */
enum class SpectatorShadowVerdict { AGREE, DISAGREE, INCOMPLETE, INVALID }
object SpectatorShadowComparison {
    private val cardPattern = Regex("^[2-9TJQKA][cdhs]$")
    private fun valid(cards: List<String>): Boolean =
        cards.size in 3..5 && cards.toSet().size == cards.size &&
            cards.all { cardPattern.matches(it) }

    fun compare(baseline: VisibleCommunityReading, candidate: VisibleCommunityReading):
        SpectatorShadowVerdict {
        if (baseline.cards.size !in 3..5 || candidate.cards.size !in 3..5)
            return SpectatorShadowVerdict.INCOMPLETE
        val a = baseline.cards.filterNotNull()
        val b = candidate.cards.filterNotNull()
        if ((baseline.complete && !valid(a)) ||
            (candidate.complete && !valid(b)))
            return SpectatorShadowVerdict.INVALID
        if (!baseline.complete || !candidate.complete ||
            a.size != baseline.cards.size || b.size != candidate.cards.size)
            return SpectatorShadowVerdict.INCOMPLETE
        return if (a == b) SpectatorShadowVerdict.AGREE
        else SpectatorShadowVerdict.DISAGREE
    }
}
