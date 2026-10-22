package com.example.airemote

object VisualOcrCheckScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var n=0
        fun ok(v:Boolean, label:String) { check(v) { label }; n++ }
        val pkg="test.virtual.game"
        fun cards(at:Long) = StableCardReading(at,7,8,listOf("Ah","Td"),
            listOf("3c","9s","Jh"),.96)
        fun ocr(seq:Long, at:Long, agreed:Boolean, action:ExactActionCandidate =
            ExactActionCandidate.CHECK, ready:Boolean=true):AutomaticOcrEvidence {
            val line=ActionLabelCandidate(action,
                NormalizedTextBox(380,944,620,975),if(action==ExactActionCandidate.CHECK)"Чек" else "Колл")
            val t=OcrFrameTicket(7,seq,at,pkg,5)
            return AutomaticOcrEvidence(t,FrameTextInterpretation(listOf(line),emptyList(),emptyList()),
                OcrDiagnosticResult(if(agreed)OcrDiagnosticStatus.CONSISTENT_DIAGNOSTIC_ONLY
                    else OcrDiagnosticStatus.WAITING_SECOND_FRAME,""),
                TableHandBindingResult(TableHandBindingStatus.UNVERIFIED,""),
                HandStage.FLOP,if(ready) TurnDisplayState.READY else TurnDisplayState.WAITING,at+90)
        }
        val pilot=LocalVisualCheckGate()
        ok(pilot.arm(),"arm")
        ok(pilot.observeOcr(cards(1000),ocr(12,1000,false),7,1100,1080,2340)==null,
            "one OCR frame rejected")
        ok(pilot.observeOcr(cards(1000),ocr(13,1000,true,ready=false),7,1100,1080,2340)==null,
            "waiting state rejected")
        ok(pilot.observeOcr(cards(1000),ocr(13,1000,true,action=ExactActionCandidate.CALL),
            7,1100,1080,2340)==null,"paid call rejected")
        ok(pilot.observeOcr(cards(1000),ocr(13,1000,true),7,1100,1080,2200)==null,
            "unsupported viewport rejected")
        val ticket=pilot.observeOcr(cards(1000),ocr(13,1000,true),7,1100,1080,2340)
        ok(ticket!=null && ticket.source==LocalCheckSource.VISUAL_OCR_CALIBRATED,
            "correct Canvas candidate")
        ok(ticket?.button?.top == 2216 && ticket.button.bottom==2276,"calibrated real pixels")
        ok(pilot.claim(ticket!!,1120),"one action")
        ok(!pilot.claim(ticket,1121),"no duplicate")
        ok(pilot.observeOcr(cards(1000),ocr(14,1110,true),7,1150,1080,2340)==null,
            "not again before button disappears")
        pilot.stop()
        ok(pilot.observeOcr(cards(1000),ocr(14,1110,true),7,1150,1080,2340)==null,
            "STOP cancels")
        println("VisualOcrCheckScenarios: $n/$n passed")
    }
}
