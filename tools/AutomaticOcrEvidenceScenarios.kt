package com.example.airemote

fun main() {
    var checks=0
    fun test(name:String, f:()->Unit) { f(); println("PASS $name"); checks++ }
    val pkg="org.sample.poker"
    fun t(seq:Long, epoch:Long=5L, generation:Long=8L, ms:Long=1000L+seq*100) =
        OcrFrameTicket(epoch,seq,ms,pkg,generation)
    val a=AutomaticOcrEvidenceAssembler()
    val reading=FrameTextEvidence.parse(listOf(
        PositionedOcrLine("CHECK",NormalizedTextBox(390,920,470,961)),
        PositionedOcrLine("Банк 120",NormalizedTextBox(350,420,580,450))))
    test("fresh 1st OCR cannot enable taps") {
        val out=a.inspect(t(1),reading,null,null,1140)!!
        check(!out.automationReady)
        check(out.agreement.status==OcrDiagnosticStatus.WAITING_SECOND_FRAME)
        check(out.identity.status==TableHandBindingStatus.UNVERIFIED_IDENTITY)
    }
    test("agreement on 2 images remains diagnostic") {
        val out=a.inspect(t(2),reading,null,null,1250)!!
        check(out.agreement.status==OcrDiagnosticStatus.CONSISTENT_DIAGNOSTIC_ONLY)
        check(out.identity.status==TableHandBindingStatus.UNVERIFIED_IDENTITY)
        check(!out.automationReady)
    }
    test("duplicate image cannot form a proof") {
        val out=a.inspect(t(2),reading,null,null,1251)!!
        check(out.agreement.status==OcrDiagnosticStatus.REJECTED)
        check(!out.automationReady)
    }
    test("fresh window generation invalidates consensus") {
        a.inspect(t(3),reading,null,null,1350)
        val out=a.inspect(t(4,generation=9),reading,null,null,1450)!!
        check(out.agreement.status==OcrDiagnosticStatus.WAITING_SECOND_FRAME)
        check(!out.automationReady)
    }
    test("epoch change invalidates consensus") {
        val out=a.inspect(t(5,epoch=6,generation=9),reading,null,null,1550)!!
        check(out.agreement.status==OcrDiagnosticStatus.WAITING_SECOND_FRAME)
    }
    test("stale OCR is refused") {
        check(a.inspect(t(6,epoch=6,generation=9),reading,null,null,4000)==null)
    }
    test("future OCR is refused") {
        check(a.inspect(t(7,epoch=6,generation=9),reading,null,null,1000)==null)
    }
    test("stage hint does not turn into proof") {
        val stage=CardStageAssessment(HandStage.RIVER,false,false,7,"hint")
        val out=a.inspect(t(8,epoch=6,generation=9),reading,stage,null,1820)!!
        check(out.probableStage==HandStage.RIVER && !out.automationReady)
        check(out.identity.status==TableHandBindingStatus.UNVERIFIED_IDENTITY)
    }
    test("turn status from other epoch is rejected") {
        val turn=ConfirmedTurnDisplay(TurnDisplayState.READY,emptySet(),1740,5,7,false)
        val out=a.inspect(t(9,epoch=6,generation=9),reading,null,turn,1950)!!
        check(out.turnDisplay==TurnDisplayState.UNKNOWN)
    }
    test("turn status in same epoch only hints") {
        val turn=ConfirmedTurnDisplay(TurnDisplayState.READY,emptySet(),1950,6,9,false)
        val out=a.inspect(t(10,epoch=6,generation=9),reading,null,turn,2020)!!
        check(out.turnDisplay==TurnDisplayState.READY && !out.automationReady)
    }
    test("reset erases consensus") {
        a.reset()
        val out=a.inspect(t(11,epoch=6,generation=9),reading,null,null,2150)!!
        check(out.agreement.status==OcrDiagnosticStatus.WAITING_SECOND_FRAME)
    }
    println("AutomaticOcrEvidenceScenarios: $checks checks passed")
}
