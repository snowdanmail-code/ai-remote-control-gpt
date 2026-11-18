package com.example.airemote

object LivePlayerModelContextScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var n=0
        fun ok(v:Boolean,name:String){check(v){name};n++}
        val cards=StableCardReading(1000,5,7,listOf("Ah","Kd"),listOf("2c","3s","4h"),.92)
        val frame=FrameInfo(byteArrayOf(3),14,540,1170,75,0,1100,
            captureEpoch=5,sourcePackage="com.secret.demo",windowGeneration=3,
            playerCardsAtCapture=cards)
        val t=OcrFrameTicket(5,14,1100,"com.secret.demo",3)
        val labels=FrameTextInterpretation(
            listOf(ActionLabelCandidate(ExactActionCandidate.CALL,
                NormalizedTextBox(360,920,620,970),"CALL")),
            listOf(NumericLabelCandidate(NumericCandidateKind.POT,220,
                NormalizedTextBox(10,300,180,330),"Pot 220")),emptyList())
        fun evidence(s:OcrDiagnosticStatus=OcrDiagnosticStatus.CONSISTENT_DIAGNOSTIC_ONLY,
                     ticket:OcrFrameTicket=t)=AutomaticOcrEvidence(
            ticket,labels,OcrDiagnosticResult(s,"fixture"),
            TableHandBindingResult(TableHandBindingStatus.UNVERIFIED_IDENTITY,"fixture"),
            HandStage.FLOP,TurnDisplayState.UNKNOWN,1150)
        val v=requireNotNull(LivePlayerModelContextAssembler.fromFrame(frame,evidence(),1200,false))
        ok(v.holeCards==listOf("Ah","Kd")&&v.boardCards==listOf("2c","3s","4h"),"cards")
        ok(v.stageHint=="FLOP"&&v.actionHints==listOf("CALL"),"stage and OCR")
        ok(v.potDisplayUnits==220L&&v.callDisplayUnits==null,"unknown null")
        val prompt=requireNotNull(v.advisorPrompt(1300))
        ok(prompt.length<1600,"prompt length")
        ok("game_identity_verified" in prompt&&"action_authorized" in prompt,"no action authority")
        ok("com.secret.demo" !in prompt && "Pot 220" !in prompt,"no raw data")
        ok(v.advisorPrompt(100000)==null&&"historical" in requireNotNull(v.advisorPrompt(2000)),"age")
        ok(LivePlayerModelContextAssembler.fromFrame(frame,evidence(),1200,true)==null,"STOP")
        ok(LivePlayerModelContextAssembler.fromFrame(frame,evidence(ticket=t.copy(frameSequence=99)),1200,false)==null,"frame identity")
        ok(LivePlayerModelContextAssembler.fromFrame(frame,evidence(),3000,false)==null,"stale")
        val uncertain=LivePlayerModelContextAssembler.fromFrame(
            frame,evidence(s=OcrDiagnosticStatus.WAITING_SECOND_FRAME),1200,false)
        ok(uncertain!=null&&uncertain.actionHints.isEmpty()&&uncertain.potDisplayUnits==null,"no guessed actions")
        val dup=frame.copy(playerCardsAtCapture=cards.copy(hole=listOf("Ah","Ah")))
        ok(LivePlayerModelContextAssembler.fromFrame(dup,evidence(),1200,false)?.holeCards==null,"no duplicates")
        val store=LivePlayerContextBuffer(2)
        store.offer(v);store.offer(v);ok(store.size()==1,"dedup")
        store.offer(v.copy(frameSequence=15));store.offer(v.copy(frameSequence=16))
        ok(store.size()==2&&store.latest()?.frameSequence==16L,"capacity")
        store.clear();ok(store.latest()==null,"wipe")
        println("LivePlayerModelContextScenarios: "+n+" checks passed")
    }
}
