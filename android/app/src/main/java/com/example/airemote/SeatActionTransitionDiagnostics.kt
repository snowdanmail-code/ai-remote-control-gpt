package com.example.airemote

/**
 * READ-ONLY transition plausibility checks. These measurements must originate
 * from an INDEPENDENT, calibrated source, never from the seat action label.
 * No current production provider supplies such measurements. A plausible
 * transition is NOT an authenticated opponent action or hand receipt.
 */
data class SeatStateSnapshot(
    val ticket: OcrFrameTicket,
    val tableKey: String,
    val handKey: String,
    val independentContextVerified: Boolean,
    val independentStateVerified: Boolean,
    val street: String,
    val turnSeat: Int?,
    val seatContributions: Map<Int, Long>,
    val seatStacks: Map<Int, Long>,
    val activeSeats: Set<Int>,
    val amountToMatch: Long,
    val displayedPot: Long
)

enum class SeatTransitionStatus { PLAUSIBLE_DIAGNOSTIC, INCONCLUSIVE, INVALIDATED }

data class SeatTransitionReport(
    val status: SeatTransitionStatus,
    val reason: String,
    val seat: Int? = null,
    val action: SeatCueAction? = null
) {
    // Intentionally no sourceVerified/actionConfirmed fields: callers may not
    // turn this untrusted report into a ConfirmedOpponentAction.
    val canAuthorizeGameplay: Boolean get() = false
    val canEnterOpponentHistory: Boolean get() = false
}

/** Stateless evaluation: STOP / context changes cannot leave stale evidence. */
object SeatActionTransitionDiagnostics {
    private const val MAX_CHIPS = 1_000_000_000_000L
    private const val MAX_AGE_MS = 1500L
    private const val MIN_GAP_MS = 100L
    private const val MAX_GAP_MS = 2500L

    private fun valid(s: SeatStateSnapshot): Boolean {
        val t = s.ticket
        val seats = s.seatContributions.keys
        return s.independentContextVerified && s.independentStateVerified &&
            s.tableKey.isNotBlank() && s.tableKey.length <= 128 &&
            s.handKey.isNotBlank() && s.handKey.length <= 128 &&
            !s.tableKey.any { it.isISOControl() } && !s.handKey.any { it.isISOControl() } &&
            t.captureEpoch >= 0 && t.windowGeneration >= 0 &&
            t.frameSequence > 0 && t.capturedAtMs >= 0 && t.sourcePackage.isNotBlank() &&
            s.street in setOf("PREFLOP", "FLOP", "TURN", "RIVER") &&
            seats.size in 2..10 && seats == s.seatStacks.keys &&
            seats.all { it in 0..9 } && s.activeSeats.all { it in seats } &&
            (s.turnSeat == null || s.turnSeat in s.activeSeats) &&
            s.amountToMatch in 0..MAX_CHIPS && s.displayedPot in 0..MAX_CHIPS &&
            s.seatContributions.values.all { it in 0..MAX_CHIPS } &&
            s.seatStacks.values.all { it in 0..MAX_CHIPS }
    }

    private fun sameContext(a: SeatStateSnapshot, b: SeatStateSnapshot): Boolean =
        a.tableKey == b.tableKey && a.handKey == b.handKey && a.street == b.street &&
            a.ticket.captureEpoch == b.ticket.captureEpoch &&
            a.ticket.windowGeneration == b.ticket.windowGeneration &&
            a.ticket.sourcePackage == b.ticket.sourcePackage &&
            a.seatContributions.keys == b.seatContributions.keys

    private fun later(a: SeatStateSnapshot, b: SeatStateSnapshot): Boolean =
        b.ticket.frameSequence > a.ticket.frameSequence &&
            b.ticket.capturedAtMs > a.ticket.capturedAtMs &&
            b.ticket.capturedAtMs - a.ticket.capturedAtMs in MIN_GAP_MS..MAX_GAP_MS

    /**
     * before: prior turn; after/settled: independent state estimates AFTER
     * the action, on two fresh frames. cueReport is still untrusted OCR.
     */
    fun evaluate(
        before: SeatStateSnapshot,
        after: SeatStateSnapshot,
        settled: SeatStateSnapshot,
        cueReport: SeatVisualReport,
        nowMs: Long,
        operatorStopped: Boolean = false
    ): SeatTransitionReport {
        fun invalid(reason: String) = SeatTransitionReport(SeatTransitionStatus.INVALIDATED, reason)
        fun unclear(reason: String) = SeatTransitionReport(SeatTransitionStatus.INCONCLUSIVE, reason)
        if (operatorStopped) return invalid("STOP")
        if (!valid(before) || !valid(after) || !valid(settled)) return invalid("unverified or malformed source")
        if (!sameContext(before, after) || !sameContext(after, settled)) return invalid("table, hand, source or street changed")
        if (!later(before, after) || !later(after, settled) ||
            nowMs < settled.ticket.capturedAtMs ||
            nowMs - settled.ticket.capturedAtMs > MAX_AGE_MS) return invalid("stale/replayed frames")
        if (cueReport.status != SeatDiagnosticStatus.STABLE_DIAGNOSTIC ||
            cueReport.frameSequence != settled.ticket.frameSequence ||
            cueReport.stableCues.size != 1 || cueReport.candidates.size != 1 ||
            cueReport.candidates.single() != cueReport.stableCues.single())
            return unclear("OCR action cue ambiguous or not aligned with settled frame")
        val cue = cueReport.stableCues.single()
        val seat = cue.seatIndex
        if (seat !in before.activeSeats || before.turnSeat != seat ||
            seat !in before.seatContributions) return unclear("turn ownership not independently established")
        if (after.turnSeat == seat || settled.turnSeat == seat ||
            after.turnSeat != settled.turnSeat) return unclear("no stable turn transition")
        // Do not infer change from an animation: two *post-action* frames must agree.
        if (after.seatContributions != settled.seatContributions ||
            after.seatStacks != settled.seatStacks ||
            after.activeSeats != settled.activeSeats ||
            after.amountToMatch != settled.amountToMatch ||
            after.displayedPot != settled.displayedPot) return unclear("post-action numbers/seat state unstable")
        if (after.displayedPot < before.displayedPot ||
            after.amountToMatch < before.amountToMatch) return unclear("pot or required bet regressed")
        // No other player's contribution or stack may change inside this narrow proof.
        val others = before.seatContributions.keys - seat
        if (others.any { before.seatContributions[it] != after.seatContributions[it] ||
                before.seatStacks[it] != after.seatStacks[it] })
            return unclear("multiple seats changed in the observed interval")
        val oldBet = before.seatContributions.getValue(seat)
        val newBet = after.seatContributions.getValue(seat)
        val oldStack = before.seatStacks.getValue(seat)
        val newStack = after.seatStacks.getValue(seat)
        if (newBet < oldBet || newStack > oldStack ||
            newBet - oldBet != oldStack - newStack)
            return unclear("chip movement inconsistent")
        val change = newBet - oldBet
        val plausible = when (cue.action) {
            SeatCueAction.FOLD -> seat !in after.activeSeats && change == 0L &&
                after.amountToMatch == before.amountToMatch
            SeatCueAction.CHECK -> seat in after.activeSeats && change == 0L &&
                oldBet == before.amountToMatch && after.amountToMatch == before.amountToMatch
            SeatCueAction.CALL -> seat in after.activeSeats && change > 0L &&
                newBet == before.amountToMatch && after.amountToMatch == before.amountToMatch
            SeatCueAction.BET -> seat in after.activeSeats && change > 0L &&
                oldBet == before.amountToMatch && after.amountToMatch == newBet &&
                newBet > before.amountToMatch
            SeatCueAction.RAISE -> seat in after.activeSeats && change > 0L &&
                oldBet < before.amountToMatch && newBet > before.amountToMatch &&
                after.amountToMatch == newBet
            SeatCueAction.ALL_IN -> seat in after.activeSeats && oldStack > 0L &&
                newStack == 0L && change > 0L && after.amountToMatch >= before.amountToMatch
        }
        return if (plausible) SeatTransitionReport(SeatTransitionStatus.PLAUSIBLE_DIAGNOSTIC,
            "independent numeric/turn delta agrees with OCR; still unverified gameplay", seat, cue.action)
        else unclear("visible action contradicts numeric/turn delta")
    }
}
