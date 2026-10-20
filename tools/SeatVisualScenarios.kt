package com.example.airemote

object SeatVisualScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var n = 0
        fun verify(ok: Boolean, label: String) { check(ok) { label }; n++ }
        val layout = SeatLayoutCalibration("portrait-test-1", "app.test.cards", 1080, 2340,
            listOf(SeatRegion(1, NormalizedTextBox(50, 250, 250, 450)),
                   SeatRegion(2, NormalizedTextBox(750, 250, 950, 450))))
        fun ticket(seq: Long = 1, at: Long = 1000, epoch: Long = 10,
                   window: Long = 3, app: String = "app.test.cards") =
            OcrFrameTicket(epoch, seq, at, app, window)
        fun line(text: String, seat: Int = 1) = PositionedOcrLine(text,
            if (seat == 1) NormalizedTextBox(90, 310, 170, 350)
            else NormalizedTextBox(790, 310, 870, 350))
        fun probe(mon: SeatVisualDiagnostics, seq: Long = 1, at: Long = 1000,
            lines: List<PositionedOcrLine> = listOf(line("Call")),
            epoch: Long = 10, window: Long = 3, app: String = "app.test.cards",
            width: Int = 1080, height: Int = 2340, now: Long = at + 50) =
            mon.inspect(ticket(seq, at, epoch, window, app), lines, width, height, now)
        val m = SeatVisualDiagnostics()
        verify(layout.isValid(), "valid layout")
        verify(!layout.copy(layoutKey = "").isValid(), "layout key")
        verify(!layout.copy(frameWidth = 0).isValid(), "bad dimensions")
        verify(!layout.copy(regions = listOf(layout.regions.first())).isValid(), "not enough seats")
        verify(!layout.copy(regions = listOf(layout.regions[0], layout.regions[0].copy(seatIndex = 2))).isValid(), "overlap")
        verify(!layout.copy(regions = listOf(layout.regions[0], layout.regions[1].copy(seatIndex = 1))).isValid(), "duplicate index")
        verify(!layout.copy(regions = listOf(layout.regions[0], SeatRegion(2, NormalizedTextBox(750,910,950,970)))).isValid(), "action bar excluded")
        verify(probe(m).status == SeatDiagnosticStatus.NOT_CALIBRATED, "not calibrated, fail closed")
        verify(!m.configure(layout.copy(frameHeight = 0)), "bad calibration denied")
        verify(m.configure(layout), "configured by independent caller")
        val first = probe(m)
        verify(first.status == SeatDiagnosticStatus.COLLECTING, "one frame never confirmed")
        verify(first.candidates == listOf(SeatCue(1, SeatCueAction.CALL)), "seat 1 candidate")
        verify(first.stableCues.isEmpty(), "no agreement yet")
        val second = probe(m, seq = 2, at = 1200)
        verify(second.status == SeatDiagnosticStatus.STABLE_DIAGNOSTIC, "two frames")
        verify(second.stableCues == listOf(SeatCue(1, SeatCueAction.CALL)), "matching same seat")
        verify(!second.summary().contains("ПОДТВЕРЖДЕНЫ"), "no trusted status")
        verify(probe(m, seq = 3, at = 1400, lines = listOf(line("Raise"))).stableCues.isEmpty(), "change of action needs second confirmation")
        verify(probe(m, seq = 4, at = 1600, lines = listOf(line("Raise"))).stableCues ==
            listOf(SeatCue(1, SeatCueAction.RAISE)), "two raise overlays")
        verify(probe(m, seq = 5, at = 1800, lines = listOf(line("Call",2))).stableCues.isEmpty(), "no cross-seat proof")
        verify(probe(m, seq = 6, at = 2000, lines = listOf(line("Call",2))).stableCues ==
            listOf(SeatCue(2, SeatCueAction.CALL)), "second seat diagnostic")
        verify(probe(m, seq = 6, at = 2000).status == SeatDiagnosticStatus.INVALIDATED, "repeated frame rejected")
        verify(probe(m).status == SeatDiagnosticStatus.NOT_CALIBRATED, "invalidated calibration erased")
        m.configure(layout)
        verify(probe(m, now = 3500).status == SeatDiagnosticStatus.INVALIDATED, "expired frame")
        m.configure(layout)
        verify(probe(m, width = 720).status == SeatDiagnosticStatus.INVALIDATED, "resolution changed")
        m.configure(layout)
        verify(probe(m, app = "com.other").status == SeatDiagnosticStatus.INVALIDATED, "foreground package switched")
        m.configure(layout)
        verify(probe(m).status == SeatDiagnosticStatus.COLLECTING, "new epoch candidate")
        verify(probe(m, seq = 2, at = 1200, epoch = 11).status == SeatDiagnosticStatus.INVALIDATED, "epoch changed")
        m.configure(layout)
        probe(m)
        verify(probe(m, seq = 2, at = 1200, window = 4).status == SeatDiagnosticStatus.INVALIDATED, "window changed")
        m.configure(layout)
        probe(m)
        verify(probe(m, seq = 2, at = 1050).stableCues.isEmpty(), "minimum independent time")
        verify(probe(m, seq = 3, at = 1300).status == SeatDiagnosticStatus.STABLE_DIAGNOSTIC, "subsequent cadence")
        m.configure(layout)
        probe(m)
        verify(probe(m, seq = 2, at = 4000, now = 4050).stableCues.isEmpty(), "gap too large")
        m.configure(layout)
        probe(m)
        verify(probe(m, seq = 2, at = 1200, lines = listOf(line("Call"), line("Raise"))).status ==
            SeatDiagnosticStatus.REJECTED, "two conflicting OCR labels one seat")
        verify(probe(m, seq = 3, at = 1400, lines = listOf(line("Call"))).stableCues.isEmpty(), "conflict clears pending proof")
        verify(probe(m, seq = 4, at = 1600, lines = listOf(line("Call"))).stableCues.isNotEmpty(), "recovery")
        m.configure(layout)
        val bottom = PositionedOcrLine("Fold", NormalizedTextBox(90,920,180,950))
        verify(probe(m, lines = listOf(bottom)).candidates.isEmpty(), "hero button cannot impersonate seat")
        verify(probe(m, seq = 2, at = 1200, lines = listOf(line("Pot 100"))).candidates.isEmpty(), "pot text not a player action")
        verify(probe(m, seq = 3, at = 1400, lines = listOf(line("call",2))).candidates ==
            listOf(SeatCue(2, SeatCueAction.CALL)), "recognize another seat")
        verify(probe(m, seq = 4, at = 1600, lines = listOf(line("CALL",2))).stableCues.size == 1, "case normalization")
        m.reset()
        verify(probe(m).status == SeatDiagnosticStatus.NOT_CALIBRATED, "stop resets calibration")
        m.configure(layout)
        verify(probe(m, lines = List(101) { line("CALL") }).status == SeatDiagnosticStatus.REJECTED, "OCR flood bounded")
        m.configure(layout)
        verify(probe(m, lines = listOf(line("Call"), line("Call"))).status == SeatDiagnosticStatus.REJECTED, "same label duplicate rejected")
        m.configure(layout)
        verify(probe(m, lines = listOf(line("Call player"))).candidates.isEmpty(), "partial match rejected")
        m.configure(layout)
        verify(probe(m, lines = listOf(line("ALL-IN"))).candidates == listOf(SeatCue(1,SeatCueAction.ALL_IN)), "all-in label supported")
        m.configure(layout)
        verify(probe(m, lines = listOf(line("Колл"))).candidates == listOf(SeatCue(1,SeatCueAction.CALL)), "Russian overlay")
        println("Seat visual diagnostic scenarios: $n checks passed")
    }
}
