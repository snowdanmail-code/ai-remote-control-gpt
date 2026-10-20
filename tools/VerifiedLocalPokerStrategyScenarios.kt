package com.example.airemote

object VerifiedLocalPokerStrategyScenarios {
    private var count = 0
    private fun checkCase(value: Boolean, title: String) { check(value) { title }; count++ }
    private fun observation(
        legal: Set<LiveAction>, call: Long = 0L,
        equity: Double? = null, bound: Boolean = true,
        selected: Long? = null, min: Long? = null, max: Long? = null,
        tableVerified: Boolean = true
    ): AutonomousObservation {
        val view = VerifiedLiveView(7, 22, 1000, "hand", "fp", true, true, true, true, legal)
        return AutonomousObservation(view,"table","hand","turn","game.package",1,
            tableVerified,true,HandStage.FLOP,5000,100,call,emptyMap(),selected,
            equity?.let { FrameBoundEquity("hand","turn",7, if(bound)22 else 21,it,200,true) },min,max)
    }
    @JvmStatic fun main(args:Array<String>) {
        val s = VerifiedLocalPokerStrategy()
        checkCase(s.decide(observation(setOf(LiveAction.CHECK)))?.action==LiveAction.CHECK,"free check unknown equity")
        checkCase(s.decide(observation(setOf(LiveAction.FOLD,LiveAction.CALL),call=20))==null,"never gamble without equity")
        checkCase(s.decide(observation(setOf(LiveAction.CALL,LiveAction.FOLD),call=20,equity=.7))?.action==LiveAction.CALL,"call above odds")
        checkCase(s.decide(observation(setOf(LiveAction.CALL,LiveAction.FOLD),call=50,equity=.2))?.action==LiveAction.FOLD,"fold below odds")
        checkCase(s.decide(observation(setOf(LiveAction.BET,LiveAction.CHECK),equity=.9,selected=30,min=20,max=80))?.action==LiveAction.BET,"bet")
        checkCase(s.decide(observation(setOf(LiveAction.RAISE,LiveAction.CALL),call=20,equity=.9,selected=80,min=50,max=100))?.action==LiveAction.RAISE,"raise")
        checkCase(s.decide(observation(setOf(LiveAction.RAISE,LiveAction.CALL),call=20,equity=.9,selected=15,min=50,max=100))?.action==LiveAction.CALL,"no below min raise")
        checkCase(s.decide(observation(setOf(LiveAction.RAISE,LiveAction.CALL),call=20,equity=.9,selected=200,min=50,max=100))?.action==LiveAction.CALL,"no above max raise")
        checkCase(s.decide(observation(setOf(LiveAction.RAISE,LiveAction.CALL),call=20,equity=.9,selected=80))?.action==LiveAction.CALL,"no unknown limits")
        checkCase(s.decide(observation(setOf(LiveAction.FOLD,LiveAction.CALL),call=10,equity=.9,bound=false))==null,"frame mismatch")
        checkCase(s.decide(observation(setOf(LiveAction.CHECK),tableVerified=false))==null,"identity mismatch")
        checkCase(s.decide(observation(setOf(LiveAction.CHECK,LiveAction.BET),equity=Double.NaN))?.action==LiveAction.CHECK,"NaN rejected")
        checkCase(s.decide(observation(setOf(LiveAction.CHECK,LiveAction.BET),equity=2.0))?.action==LiveAction.CHECK,"unphysical equity rejected")
        checkCase(s.decide(observation(setOf(LiveAction.FOLD,LiveAction.CALL),call=30,equity=.75))?.action==LiveAction.CALL,"positive edge")
        val river = observation(setOf(LiveAction.FOLD, LiveAction.CALL),call=10,equity=.75)
        checkCase(s.decide(river.copy(equityEvidence = requireNotNull(river.equityEvidence).copy(
            independentSampleCount=1, exactEnumeration=true)))?.action == LiveAction.CALL,
            "exact small enumeration accepted")
        checkCase(s.decide(river.copy(equityEvidence = requireNotNull(river.equityEvidence).copy(
            independentSampleCount=1, exactEnumeration=false))) == null,
            "under-sampled estimate rejected")
        println("VerifiedLocalPokerStrategy: $count/$count passed")
    }
}
