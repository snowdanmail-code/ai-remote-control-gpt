package com.example.airemote

object SeatTransitionScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var count = 0
        fun verify(value: Boolean, description: String) { check(value) { description }; count++ }
        fun snap(seq: Long, time: Long, bet: Long = 20, stack: Long = 180,
                 toMatch: Long = 40, pot: Long = 90, turn: Int? = 1,
                 active: Set<Int> = setOf(1, 2), hand: String = "H1",
                 window: Long = 1, verified: Boolean = true,
                 another: Long = 40): SeatStateSnapshot = SeatStateSnapshot(
            OcrFrameTicket(1, seq, time, "test.package", window), "T1", hand,
            verified, verified, "FLOP", turn, mapOf(1 to bet, 2 to another),
            mapOf(1 to stack, 2 to 160L), active, toMatch, pot)
        fun cue(seq: Long = 3, action: SeatCueAction = SeatCueAction.CALL,
                seat: Int = 1) = SeatVisualReport(SeatDiagnosticStatus.STABLE_DIAGNOSTIC,
            listOf(SeatCue(seat, action)), listOf(SeatCue(seat, action)), seq)
        fun check(before: SeatStateSnapshot = snap(1, 1000),
                  after: SeatStateSnapshot = snap(2, 1200, 40, 160, turn = 2),
                  settled: SeatStateSnapshot = snap(3, 1400, 40, 160, turn = 2),
                  label: SeatVisualReport = cue(), time: Long = 1450, stop: Boolean = false) =
            SeatActionTransitionDiagnostics.evaluate(before, after, settled, label, time, stop)
        val ok = check()
        verify(ok.status == SeatTransitionStatus.PLAUSIBLE_DIAGNOSTIC, "call plausible")
        verify(!ok.canAuthorizeGameplay && !ok.canEnterOpponentHistory, "never trusted")
        verify(ok.seat == 1 && ok.action == SeatCueAction.CALL, "diagnostic details")
        verify(check(stop = true).status == SeatTransitionStatus.INVALIDATED, "STOP")
        verify(check(after = snap(2, 1200, 40, 160, turn = 2, hand = "H2")).status == SeatTransitionStatus.INVALIDATED, "hand changed")
        verify(check(settled = snap(3, 1400, 40, 160, turn = 2, window = 2)).status == SeatTransitionStatus.INVALIDATED, "window changed")
        verify(check(before = snap(1, 1000, verified = false)).status == SeatTransitionStatus.INVALIDATED, "unverified independent state")
        verify(check(settled = snap(3, 1400, 40, 160, turn = 2, verified = false)).status == SeatTransitionStatus.INVALIDATED, "unverified settled")
        verify(check(settled = snap(2, 1400, 40, 160, turn = 2)).status == SeatTransitionStatus.INVALIDATED, "repeated frame number")
        verify(check(settled = snap(3, 1200, 40, 160, turn = 2)).status == SeatTransitionStatus.INVALIDATED, "duplicate frame time")
        verify(check(time = 3000).status == SeatTransitionStatus.INVALIDATED, "stale")
        verify(check(label = cue(seq = 4)).status == SeatTransitionStatus.INCONCLUSIVE, "cue attached to wrong frame")
        verify(check(label = cue().copy(status = SeatDiagnosticStatus.COLLECTING)).status == SeatTransitionStatus.INCONCLUSIVE, "one-frame cue")
        verify(check(label = cue().copy(stableCues = listOf(SeatCue(1,SeatCueAction.RAISE)))).status == SeatTransitionStatus.INCONCLUSIVE, "conflicting OCR")
        verify(check(label = cue(seat = 2)).status == SeatTransitionStatus.INCONCLUSIVE, "other player")
        verify(check(after = snap(2, 1200, 40, 160, turn = 1)).status == SeatTransitionStatus.INCONCLUSIVE, "same actor")
        verify(check(after = snap(2, 1200, 40, 160, turn = 2), settled = snap(3, 1400, 40, 160, turn = null)).status == SeatTransitionStatus.INCONCLUSIVE, "unstable next actor")
        verify(check(after = snap(2, 1200, 40, 160, turn = 2), settled = snap(3, 1400, 45, 155, turn = 2)).status == SeatTransitionStatus.INCONCLUSIVE, "animated bet")
        verify(check(after = snap(2, 1200, 40, 180, turn = 2), settled = snap(3, 1400, 40, 180, turn = 2)).status == SeatTransitionStatus.INCONCLUSIVE, "bet without chips")
        verify(check(after = snap(2, 1200, 40, 160, turn = 2, another = 41), settled = snap(3, 1400, 40, 160, turn = 2, another = 41)).status == SeatTransitionStatus.INCONCLUSIVE, "concurrent opponents")
        verify(check(after = snap(2, 1200, 40, 160, pot = 80, turn = 2), settled = snap(3, 1400, 40, 160, pot = 80, turn = 2)).status == SeatTransitionStatus.INCONCLUSIVE, "pot decreased")
        verify(check(after = snap(2, 1200, 40, 160, toMatch = 50, turn = 2), settled = snap(3, 1400, 40, 160, toMatch = 50, turn = 2)).status == SeatTransitionStatus.INCONCLUSIVE, "call vs raise")
        val foldBefore = snap(1, 1000, bet = 20, stack = 180, toMatch = 40)
        val foldAfter = snap(2, 1200, bet = 20, stack = 180, toMatch = 40, turn = 2, active = setOf(2))
        val foldDone = snap(3, 1400, bet = 20, stack = 180, toMatch = 40, turn = 2, active = setOf(2))
        verify(check(foldBefore, foldAfter, foldDone, cue(action = SeatCueAction.FOLD)).status == SeatTransitionStatus.PLAUSIBLE_DIAGNOSTIC, "fold")
        verify(check(foldBefore, foldAfter, foldDone, cue(action = SeatCueAction.CALL)).status == SeatTransitionStatus.INCONCLUSIVE, "fold is not call")
        val checkBefore = snap(1, 1000, bet = 40, stack = 160)
        val checkAfter = snap(2, 1200, bet = 40, stack = 160, turn = 2)
        val checkDone = snap(3, 1400, bet = 40, stack = 160, turn = 2)
        verify(check(checkBefore, checkAfter, checkDone, cue(action = SeatCueAction.CHECK)).status == SeatTransitionStatus.PLAUSIBLE_DIAGNOSTIC, "check")
        verify(check(checkBefore, checkAfter, checkDone, cue(action = SeatCueAction.CALL)).status == SeatTransitionStatus.INCONCLUSIVE, "check is not call")
        val betBefore = snap(1, 1000, bet = 0, stack = 200, toMatch = 0)
        val betAfter = snap(2, 1200, bet = 30, stack = 170, toMatch = 30, turn = 2)
        val betDone = snap(3, 1400, bet = 30, stack = 170, toMatch = 30, turn = 2)
        verify(check(betBefore, betAfter, betDone, cue(action = SeatCueAction.BET)).status == SeatTransitionStatus.PLAUSIBLE_DIAGNOSTIC, "bet")
        val raiseAfter = snap(2, 1200, bet = 60, stack = 140, toMatch = 60, turn = 2)
        val raiseDone = snap(3, 1400, bet = 60, stack = 140, toMatch = 60, turn = 2)
        verify(check(foldBefore, raiseAfter, raiseDone, cue(action = SeatCueAction.RAISE)).status == SeatTransitionStatus.PLAUSIBLE_DIAGNOSTIC, "raise")
        verify(check(foldBefore, raiseAfter, raiseDone, cue(action = SeatCueAction.CALL)).status == SeatTransitionStatus.INCONCLUSIVE, "raise not call")
        val allInBefore = snap(1, 1000, bet = 20, stack = 50)
        val allInAfter = snap(2, 1200, bet = 70, stack = 0, toMatch = 70, turn = 2)
        val allInDone = snap(3, 1400, bet = 70, stack = 0, toMatch = 70, turn = 2)
        verify(check(allInBefore, allInAfter, allInDone, cue(action = SeatCueAction.ALL_IN)).status == SeatTransitionStatus.PLAUSIBLE_DIAGNOSTIC, "all in")
        verify(check(allInBefore, allInAfter, allInDone, cue(action = SeatCueAction.CALL)).status == SeatTransitionStatus.INCONCLUSIVE, "all-in not call")
        verify(check(settled = snap(3, 1400, -1, 160, turn = 2)).status == SeatTransitionStatus.INVALIDATED, "negative money")
        verify(check(before = snap(1, 1000, bet = Long.MAX_VALUE)).status == SeatTransitionStatus.INVALIDATED, "bounded amounts")
        verify(check(after = snap(2, 1200, 40, 160, turn = 2, pot = 90), settled = snap(3, 1400, 40, 160, turn = 2, pot = 100)).status == SeatTransitionStatus.INCONCLUSIVE, "pot still animating")
        println("Seat action transition scenarios: $count checks passed")
    }
}
