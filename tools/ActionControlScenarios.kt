package com.example.airemote

object ActionControlScenarios {
    private var tests = 0
    private fun ck(condition: Boolean, message: String) {
        tests++
        check(condition) { message }
    }
    private fun b(l: Int, t: Int, r: Int, bot: Int) = NormalizedTextBox(l, t, r, bot)
    private val app = "com.example.pokertest"
    private fun base() = RealFrameCalibration("operator-measured", app, 1080, 2340, listOf(
        RealFrameZone(RealFieldRole.SEAT_ACTION, b(30,100,330,220), 0),
        RealFrameZone(RealFieldRole.SEAT_ACTION, b(630,100,940,220), 1),
        RealFrameZone(RealFieldRole.HERO_FOLD, b(35,918,302,977)),
        RealFrameZone(RealFieldRole.HERO_CHECK_CALL, b(352,918,639,977)),
        RealFrameZone(RealFieldRole.HERO_BET_RAISE, b(688,918,959,977))
    ))
    private fun ticket(seq: Long, time: Long, ep: Long = 4, win: Long = 8,
                       pkg: String = app) = OcrFrameTicket(ep, seq, time, pkg, win)
    private fun turn(at: Long=1000, ep: Long=4, state: TurnDisplayState=TurnDisplayState.READY,
                     roles: Set<VisibleButtonRole> = setOf(VisibleButtonRole.RED_FOLD,
                         VisibleButtonRole.GREEN_CHECK_OR_CALL, VisibleButtonRole.GOLD_BET_OR_RAISE)) =
        ConfirmedTurnDisplay(state, roles, at, ep, 1, false)
    private fun lines(vararg tokens: String) = listOf(
        PositionedOcrLine(tokens.getOrElse(0) { "fold" }, b(100,930,185,951)),
        PositionedOcrLine(tokens.getOrElse(1) { "check" }, b(423,930,530,951)),
        PositionedOcrLine(tokens.getOrElse(2) { "bet" }, b(762,930,825,951))
    )
    @JvmStatic fun main(args: Array<String>) {
        val p = base()
        ck(p.valid(), "profile with real control zones")
        ck(!p.copy(zones = p.zones.dropLast(1)).valid(), "all hero lanes must be specified")
        ck(!p.copy(zones = p.zones + p.zones.last()).valid(), "duplicate hero lane")
        ck(!p.copy(zones = p.zones.map { if (it.role == RealFieldRole.HERO_FOLD)
            it.copy(seatIndex = 0) else it }).valid(), "hero lane never has a seat index")
        val gate = ActionControlDiagnostics()
        ck(gate.inspect(ticket(1,1000),1080,2340,1100,lines(),turn(),false).status ==
            ActionStripStatus.NOT_CALIBRATED, "no implicit geometry")
        ck(gate.configure(p), "explicit geometry accepted")
        val first = gate.inspect(ticket(1,1000),1080,2340,1100,lines(),turn(),false)
        ck(first.status == ActionStripStatus.COLLECTING, "first frame not stable")
        ck(first.candidates.size == 3, "three measured lanes")
        ck(first.candidates[0].measuredButton == p.zones[2].box, "measured button, not OCR text box")
        val second = gate.inspect(ticket(2,1300),1080,2340,1350,lines(),turn(),false)
        ck(second.status == ActionStripStatus.STABLE_DIAGNOSTIC, "second fresh frame")
        ck(second.stable.map { it.action } == listOf(ExactActionCandidate.FOLD,
            ExactActionCandidate.CHECK, ExactActionCandidate.BET), "separate legal candidate names")
        ck(!second.authorizesTap && !second.confirmsHandOrTurn, "never grants dispatch or identities")
        ck(gate.inspect(ticket(2,1300),1080,2340,1400,lines(),turn(),false).status ==
            ActionStripStatus.REJECTED, "duplicate frame denied")
        ck(gate.inspect(ticket(3,1600),1080,2340,1640,lines(),turn(),false).status ==
            ActionStripStatus.COLLECTING, "replay reset")
        ck(gate.inspect(ticket(4,1950),1080,2340,1990,lines("fold","call","raise"),turn(at=1900),false).status ==
            ActionStripStatus.COLLECTING, "action changes force new pair")
        ck(gate.inspect(ticket(5,2220),1080,2340,2270,lines("fold","call","raise"),turn(at=2100),false).status ==
            ActionStripStatus.STABLE_DIAGNOSTIC, "different stable group of controls")
        ck(gate.inspect(ticket(6,2510),1080,2340,2560,lines(),turn(at=2450),true).status ==
            ActionStripStatus.REJECTED, "STOP rejects")
        ck(gate.inspect(ticket(7,2810),1080,2340,2860,lines(),turn(at=2770),false).status ==
            ActionStripStatus.COLLECTING, "after STOP new pair needed")
        ck(gate.inspect(ticket(8,3000,win=9),1080,2340,3040,lines(),turn(at=2990),false).status ==
            ActionStripStatus.REJECTED, "window switch rejects")
        ck(gate.inspect(ticket(9,3200,pkg="com.other.app"),1080,2340,3250,lines(),turn(at=3180),false).status ==
            ActionStripStatus.REJECTED, "wrong source package")
        ck(gate.inspect(ticket(10,3400),540,1170,3440,lines(),turn(at=3380),false).status ==
            ActionStripStatus.REJECTED, "wrong JPEG dimensions")
        ck(gate.inspect(ticket(11,3000),1080,2340,4700,lines(),turn(at=2900),false).status ==
            ActionStripStatus.REJECTED, "stale frame")
        ck(gate.inspect(ticket(12,4900),1080,2340,4940,lines(),turn(at=4800,
            state=TurnDisplayState.PREACTION),false).status == ActionStripStatus.REJECTED,
            "preaction buttons must never appear active")
        ck(gate.inspect(ticket(13,5300),1080,2340,5350,lines(),null,false).status ==
            ActionStripStatus.REJECTED, "no independent turn display")
        val duplicate = lines() + PositionedOcrLine("CALL",b(430,944,530,958))
        ck(gate.inspect(ticket(14,5700),1080,2340,5770,duplicate,turn(at=5620),false).status ==
            ActionStripStatus.REJECTED, "two OCR strings in same button")
        ck(gate.inspect(ticket(15,6010),1080,2340,6060,lines("fold", "call 0.17 $", "bet"),
            turn(at=5920),false).status == ActionStripStatus.COLLECTING,
            "exact fractional virtual-chip CALL first frame needs second observation")
        val decorated = ActionControlDiagnostics()
        ck(decorated.configure(p),"separate decimal control calibration")
        ck(decorated.inspect(ticket(1,7000),1080,2340,7060,
            lines("fold","Колл 0.02 $","bet"),turn(at=6990),false)
            .status == ActionStripStatus.COLLECTING, "decorated call needs second JPEG")
        val decimalCall=decorated.inspect(ticket(2,7330),1080,2340,7360,
            lines("fold","Колл 0.02 $","bet"),turn(at=7290),false)
        ck(decimalCall.status == ActionStripStatus.STABLE_DIAGNOSTIC &&
            decimalCall.stable.any { it.action == ExactActionCandidate.CALL },
            "exact fractional CALL label confirmed after two independent frames")
        ck(decorated.inspect(ticket(3,7640),1080,2340,7680,
            lines("fold","Колл 0.021 $","bet"),turn(at=7610),false)
            .status == ActionStripStatus.REJECTED, "three decimal digits never rounded")
        ck(gate.inspect(ticket(16,6300),1080,2340,6350,lines("fold", "raise", "bet"),
            turn(at=6200),false).status == ActionStripStatus.REJECTED,
            "wrong lane action")
        ck(gate.inspect(ticket(17,6700),1080,2340,6750,lines(),turn(at=6600,
            roles=setOf(VisibleButtonRole.GREEN_CHECK_OR_CALL)),false).status ==
            ActionStripStatus.REJECTED, "color/label conflict")
        val wrongArea = p.copy(zones = p.zones.map { if (it.role == RealFieldRole.HERO_FOLD)
            it.copy(box=b(200,918,590,977)) else it })
        ck(!ActionControlDiagnostics().configure(wrongArea), "overlapping or out-of-lane geometry")
        val badHeight = p.copy(zones = p.zones.map { if (it.role == RealFieldRole.HERO_FOLD)
            it.copy(box=b(35,700,302,977)) else it })
        ck(!ActionControlDiagnostics().configure(badHeight), "control outside allowed strip")
        gate.clear()
        ck(!gate.configured(), "STOP clears measured geometry")
        println("ActionControlScenarios: $tests checks passed")
    }
}
