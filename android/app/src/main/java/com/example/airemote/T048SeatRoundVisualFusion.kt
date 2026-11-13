package com.example.airemote

/**
 * T048: Measured chips, temporary action badges, and card BACKS near seats.
 * Every observation here is READ-ONLY. It is not a certified game event,
 * an opponent identity, a pot accounting instruction, or a betting authority.
 */
data class T048SeatFrame(
    val ticket: OcrFrameTicket,
    val frameWidth: Int,
    val frameHeight: Int,
    val contributionsMinor: Map<Int, Long>,
    val stacksMinor: Map<Int, Long>,
    /** Present ONLY for calibrated card-back regions. No key means UNKNOWN. */
    val blueCardBacks: Map<Int, Boolean>,
    val actions: Map<Int, SeatCueAction>,
    /** The pot label may ALREADY INCLUDE visible per-seat chips. */
    val displayedPotMinor: Long?,
    /** Only a stable hero-card/board snapshot acquired with this JPEG. */
    val boardCount: Int?
)

enum class T048CueKind {
    RAISE_WITH_SEAT_CHIPS,
    FOLD_WITH_BACKS_DISAPPEARING,
    BETS_MOVING_TO_CENTER
}

data class T048CombinedCue(
    val kind: T048CueKind,
    val seat: Int?,
    val amountBeforeMinor: Long?,
    val amountAfterMinor: Long?,
    val detail: String
)

enum class T048ReadingStatus { WAITING, OBSERVED, INCONCLUSIVE, STALE_OR_CHANGED }
data class T048Reading(
    val status: T048ReadingStatus,
    val frameSequence: Long,
    val potMinor: Long? = null,
    val seatContributionsMinor: Map<Int, Long> = emptyMap(),
    val seatCardBacks: Map<Int, Boolean> = emptyMap(),
    val actionBadges: Map<Int, SeatCueAction> = emptyMap(),
    val combined: List<T048CombinedCue> = emptyList()
) {
    val authorizesGameplay: Boolean get() = false
    val provesOpponentAction: Boolean get() = false
    val canUpdateOpponentMemory: Boolean get() = false
}

class T048SeatSignalFusion {
    private var previous: T048SeatFrame? = null
    @Synchronized fun reset() { previous = null }

    @Synchronized fun observe(current: T048SeatFrame, nowMs: Long): T048Reading {
        fun result(s: T048ReadingStatus, cues: List<T048CombinedCue> = emptyList()) =
            T048Reading(s, current.ticket.frameSequence,
                current.displayedPotMinor, current.contributionsMinor,
                current.blueCardBacks, current.actions, cues)
        val t = current.ticket
        if (t.sourcePackage.isBlank() || t.captureEpoch < 0 ||
            t.windowGeneration < 0 || t.frameSequence <= 0 ||
            current.frameWidth !in 320..5000 ||
            current.frameHeight !in 640..9000 ||
            nowMs - t.capturedAtMs !in 0L..2_000L ||
            current.contributionsMinor.any {
                it.key !in 0..9 || it.value !in 0L..100_000_000_000L
            } || current.stacksMinor.any {
                it.key !in 0..9 || it.value !in 0L..100_000_000_000L
            } || current.blueCardBacks.keys.any { it !in 0..9 } ||
            current.actions.keys.any { it !in 0..9 } ||
            current.displayedPotMinor?.let { it < 0L } == true ||
            current.boardCount?.let { it !in listOf(0,3,4,5) } == true) {
            reset()
            return result(T048ReadingStatus.STALE_OR_CHANGED)
        }
        val old = previous
        previous = current
        if (old == null) return result(T048ReadingStatus.WAITING)
        val a = old.ticket
        if (a.sourcePackage != t.sourcePackage ||
            a.captureEpoch != t.captureEpoch ||
            a.windowGeneration != t.windowGeneration ||
            old.frameWidth != current.frameWidth ||
            old.frameHeight != current.frameHeight ||
            t.frameSequence <= a.frameSequence ||
            t.capturedAtMs - a.capturedAtMs !in 250L..8_000L ||
            // 5 -> 0 common cards is usually a NEW hand, not a fold.
            (old.boardCount != null && current.boardCount != null &&
                current.boardCount < old.boardCount)) {
            // Keep only the new frame as the first frame of a new sequence.
            return result(T048ReadingStatus.STALE_OR_CHANGED)
        }
        val cues = mutableListOf<T048CombinedCue>()
        val observedSeats = (old.actions.keys + current.actions.keys +
            old.contributionsMinor.keys + current.contributionsMinor.keys +
            old.blueCardBacks.keys + current.blueCardBacks.keys).distinct()
        for (seat in observedSeats) {
            val label = current.actions[seat] ?: old.actions[seat]
            val oldBet = old.contributionsMinor[seat]
            val newBet = current.contributionsMinor[seat]
            val oldBacks = old.blueCardBacks[seat]
            val newBacks = current.blueCardBacks[seat]
            if (label == SeatCueAction.RAISE &&
                oldBet != null && newBet != null && newBet > oldBet &&
                (oldBacks == true || newBacks == true) &&
                // If both stacks are observed, they must not increase
                // while the player's displayed contribution increases.
                (old.stacksMinor[seat] == null ||
                    current.stacksMinor[seat] == null ||
                    current.stacksMinor.getValue(seat) <=
                        old.stacksMinor.getValue(seat))) {
                cues.add(T048CombinedCue(T048CueKind.RAISE_WITH_SEAT_CHIPS,
                    seat, oldBet, newBet,
                    "Temporary RAISE label agrees with higher visible seat wager and card backs"))
            }
            if (label == SeatCueAction.FOLD &&
                oldBacks == true && newBacks == false) {
                cues.add(T048CombinedCue(T048CueKind.FOLD_WITH_BACKS_DISAPPEARING,
                    seat, oldBet, newBet,
                    "Temporary FOLD label and disappearance of this seat's card backs"))
            }
        }
        // Pot transition: Do NOT sum center pot + uncollected displayed
        // contributions. The text 'Банк' can already account for them.
        // A board transition is required; vanishing OCR amounts alone is weak.
        if (old.boardCount != null && current.boardCount != null &&
            old.boardCount < current.boardCount &&
            old.contributionsMinor.any { it.value > 0L } &&
            old.contributionsMinor.keys.none {
                it in current.contributionsMinor.keys &&
                    current.contributionsMinor.getValue(it) > 0L
            } && old.displayedPotMinor != null &&
            current.displayedPotMinor != null &&
            current.displayedPotMinor > old.displayedPotMinor) {
            cues.add(T048CombinedCue(T048CueKind.BETS_MOVING_TO_CENTER, null,
                old.displayedPotMinor, current.displayedPotMinor,
                "New board street + prior seat wagers no longer seen; pot changed; no double count"))
        }
        return result(if (cues.isEmpty()) T048ReadingStatus.INCONCLUSIVE
            else T048ReadingStatus.OBSERVED, cues)
    }
}

/**
 * Pixels-only BLUE card-back characteristic. Work ONLY in a measured
 * SEAT_CARD_BACK region; teal avatar backgrounds are a known false positive.
 */
object T048CardBackColor {
    fun isBluePixel(rgb: Int): Boolean {
        val r = (rgb shr 16) and 255
        val g = (rgb shr 8) and 255
        val b = rgb and 255
        val max = maxOf(r,g,b).toDouble()
        val min = minOf(r,g,b).toDouble()
        if (max < 70.0 || max - min < max * 0.35) return false
        val d = max - min
        val hue = when (max.toInt()) {
            r -> 60.0 * (((g-b).toDouble()/d + 6.0) % 6.0)
            g -> 60.0 * ((b-r).toDouble()/d + 2.0)
            else -> 60.0 * ((r-g).toDouble()/d + 4.0)
        }
        return hue in 186.0..260.0
    }

    /** Between 0.08 and 0.22, the color is AMBIGUOUS, not a missing card. */
    fun evidence(bluePixels: Int, totalSampled: Int): Boolean? {
        if (totalSampled < 150 || bluePixels !in 0..totalSampled) return null
        val share = bluePixels.toDouble()/totalSampled
        return when {
            share >= 0.22 -> true
            share <= 0.08 -> false
            else -> null
        }
    }
}
