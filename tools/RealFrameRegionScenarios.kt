package com.example.airemote

object RealFrameRegionScenarios {
    private var total = 0
    private fun ck(condition: Boolean, explanation: String) { total++; check(condition) { explanation } }
    private fun bounds(x: Int, y: Int, right: Int, bottom: Int) = NormalizedTextBox(x,y,right,bottom)
    private fun profile() = RealFrameCalibration("measured-portrait-v1", "com.example.pokertest", 1080, 2340,
        listOf(
            RealFrameZone(RealFieldRole.SEAT_ACTION, bounds(50,90,350,220), 0),
            RealFrameZone(RealFieldRole.SEAT_ACTION, bounds(600,90,900,220), 1),
            RealFrameZone(RealFieldRole.SEAT_STACK, bounds(55,240,300,320), 0),
            RealFrameZone(RealFieldRole.SEAT_STACK, bounds(680,240,900,320), 1),
            RealFrameZone(RealFieldRole.POT, bounds(350,400,650,510)),
            RealFrameZone(RealFieldRole.TIMER, bounds(350,700,650,800))
        ))
    private fun ticket(n: Long, time: Long, ep: Long=7, window: Long=2,
        app: String="com.example.pokertest") = OcrFrameTicket(ep,n,time,app,window)
    @JvmStatic fun main(args: Array<String>) {
        val ok = profile()
        ck(ok.valid(), "valid geometry")
        ck(ok.seatVisualCalibration().isValid(), "seat zone conversion")
        ck(!ok.copy(expectedPackage="oneword").valid(), "package syntax")
        ck(!ok.copy(expectedPackage="").valid(), "blank package")
        ck(!ok.copy(frameWidth=0).valid(), "size")
        ck(!ok.copy(layoutKey="\u0000").valid(), "control code")
        ck(!ok.copy(zones=ok.zones.drop(1)).valid(), "required seats")
        ck(!ok.copy(zones=ok.zones + ok.zones[0]).valid(), "duplicate role-seat")
        ck(!ok.copy(zones=ok.zones.map { if (it.seatIndex==1 && it.role==RealFieldRole.SEAT_ACTION)
            it.copy(box=bounds(200,90,500,220)) else it }).valid(), "overlap")
        ck(!ok.copy(zones=ok.zones.map { if (it.role==RealFieldRole.POT)
            it.copy(seatIndex=1) else it }).valid(), "global field cannot have seat")
        ck(!ok.copy(zones=ok.zones.map { if (it.role==RealFieldRole.SEAT_STACK && it.seatIndex==0)
            it.copy(seatIndex=null) else it }).valid(), "seat field must have seat")
        ck(!ok.copy(zones=ok.zones.map { if (it.role==RealFieldRole.SEAT_ACTION && it.seatIndex==0)
            it.copy(box=bounds(50,1,350,220)) else it }).seatVisualCalibration().isValid(), "legacy top safety")
        val gate = RealFrameRegionDiagnostics()
        ck(!gate.configured(), "must begin uncalibrated")
        ck(gate.inspect(ticket(1,1000),1080,2340,1100, emptyList()).status == RegionObservationStatus.NOT_CALIBRATED, "uncalibrated state")
        ck(gate.configure(ok), "configure")
        val lines = listOf(
            PositionedOcrLine("Raise",bounds(100,120,200,190)),
            PositionedOcrLine("Fold",bounds(680,125,780,195)),
            PositionedOcrLine("1 200",bounds(385,430,545,475)),
            PositionedOcrLine("20",bounds(395,725,485,755)),
            PositionedOcrLine("750",bounds(90,250,180,290)),
            PositionedOcrLine("2.5K",bounds(710,255,790,295)),
            PositionedOcrLine("outside",bounds(1,1,15,15))
        )
        val first = gate.inspect(ticket(1,1000),1080,2340,1100,lines)
        ck(first.status == RegionObservationStatus.OBSERVED, "first observed")
        ck(first.fields.size == 6, "six regions")
        ck(first.fields.single { it.role==RealFieldRole.POT }.numericValue==1200L, "thousands")
        ck(first.fields.single { it.role==RealFieldRole.TIMER }.numericValue==20L, "timer plain")
        ck(first.fields.single { it.seatIndex==1 && it.role==RealFieldRole.SEAT_STACK }.numericValue==null, "no abbreviation guessing")
        ck(!first.canCommitOpponentHistory && !first.authorizesAction, "read only flags")
        // Actual 1win recording labels use two decimals and a visible $ sign.
        // Preserve legacy integer semantics separately; store exact minor units
        // without treating these untrusted OCR readings as validated stakes.
        val dollars = listOf(
            PositionedOcrLine("0.17 $", bounds(70,252,190,291)),
            PositionedOcrLine("3.16 $", bounds(710,252,810,291)),
            PositionedOcrLine("0.87 $", bounds(380,427,510,470)))
        val money = RealFrameRegionDiagnostics()
        ck(money.configure(ok), "currency OCR calibration")
        val parsed = money.inspect(ticket(1,1000),1080,2340,1100,dollars)
        ck(parsed.fields.single { it.role==RealFieldRole.POT }.amountMinorUnits==87L, "pot exact cents")
        ck(parsed.fields.single { it.role==RealFieldRole.SEAT_STACK && it.seatIndex==0 }.amountMinorUnits==17L, "seat minor units")
        ck(parsed.fields.single { it.role==RealFieldRole.SEAT_STACK && it.seatIndex==1 }.amountMinorUnits==316L, "stack exact cents")
        ck(parsed.fields.all { it.numericValue == null }, "money does not leak into legacy integer units")
        ck(money.inspect(ticket(2,1200),1080,2340,1250, listOf(
            PositionedOcrLine("0.175 $", bounds(380,427,510,470))
        )).fields.single().amountMinorUnits==null, "three-decimal ambiguity rejected")
        ck(money.inspect(ticket(3,1400),1080,2340,1450, listOf(
            PositionedOcrLine("2.5K", bounds(380,427,510,470))
        )).fields.single().amountMinorUnits==null, "abbreviations rejected")
        ck(money.inspect(ticket(4,1600),1080,2340,1650, listOf(
            PositionedOcrLine("0.17 €", bounds(380,427,510,470))
        )).fields.single().amountMinorUnits==null, "different currency rejected")
        ck(!parsed.authorizesAction && !parsed.canCommitOpponentHistory,
            "real-currency OCR cannot trigger actions or learning")
        val timerReader = RealFrameRegionDiagnostics()
        ck(timerReader.configure(ok), "timer-region profile")
        ck(timerReader.inspect(ticket(1,3000),1080,2340,3100,
            listOf(PositionedOcrLine("60 с",bounds(395,725,485,755))))
                .fields.single().numericValue==60L, "recorded Cyrillic seconds token")
        ck(timerReader.inspect(ticket(2,3200),1080,2340,3250,
            listOf(PositionedOcrLine("999 с",bounds(395,725,485,755))))
                .fields.single().numericValue==null, "out-of-range timer cannot enter calculation")


        ck(gate.inspect(ticket(2,1200),1080,2340,1250,lines).status==RegionObservationStatus.OBSERVED, "new frame")
        ck(gate.inspect(ticket(2,1200),1080,2340,1270,lines).status==RegionObservationStatus.REJECTED, "replay")
        ck(gate.inspect(ticket(3,1400),1080,2340,1500,lines).status==RegionObservationStatus.OBSERVED, "rejection resets prior")
        ck(gate.inspect(ticket(4,1600),1080,2340,1700,lines, operatorStopped=true).status==RegionObservationStatus.REJECTED, "STOP")
        ck(gate.inspect(ticket(5,1800),1080,2000,1900,lines).status==RegionObservationStatus.REJECTED, "wrong geometry")
        ck(gate.inspect(ticket(6,2000,app="com.other.app"),1080,2340,2100,lines).status==RegionObservationStatus.REJECTED, "wrong package")
        ck(gate.inspect(ticket(7,1000),1080,2340,3000,lines).status==RegionObservationStatus.REJECTED, "stale")
        ck(gate.inspect(ticket(8,3000),1080,2340,3200,
            lines + PositionedOcrLine("999",bounds(390,440,500,480))).fields.none { it.role==RealFieldRole.POT }, "ambiguous OCR omitted")
        ck(gate.inspect(ticket(9,3400),1080,2340,3500,lines).status==RegionObservationStatus.OBSERVED, "restored")
        ck(gate.inspect(ticket(10,3600,window=3),1080,2340,3700,lines).status==RegionObservationStatus.REJECTED, "window generation")
        gate.resetSession()
        ck(gate.configured(), "session reset keeps geometry")
        ck(gate.inspect(ticket(1,5000,ep=8),1080,2340,5100,lines).status==RegionObservationStatus.OBSERVED, "new capture with same profile")
        gate.clear()
        ck(!gate.configured(), "clear calibration")
        ck(gate.inspect(ticket(2,5400),1080,2340,5500,lines).status==RegionObservationStatus.NOT_CALIBRATED, "clear fail closed")
        println("Real frame region scenarios: $total passed")
    }
}
