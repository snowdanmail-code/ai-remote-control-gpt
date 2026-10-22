package com.example.airemote

object ValidatedPlayerStrategyScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var passed=0
        fun ok(v:Boolean,title:String){check(v){title};passed++}
        val view=VerifiedLiveView(7,20,1000,"hand-1","finger",true,true,true,true,
            setOf(LiveAction.FOLD,LiveAction.CALL,LiveAction.CHECK))
        val tap=mapOf(
            LiveAction.CHECK to VerifiedTapArea(350,2140,660,2240,505,2190,
                1080,2340,LiveAction.CHECK,"CHECK","game.test",4),
            LiveAction.FOLD to VerifiedTapArea(30,2140,320,2240,175,2190,
                1080,2340,LiveAction.FOLD,"FOLD","game.test",4),
            LiveAction.CALL to VerifiedTapArea(350,2140,660,2240,505,2190,
                1080,2340,LiveAction.CALL,"CALL","game.test",4))
        fun obs(cost:Long, equity:FrameBoundEquity? = null) =
            AutonomousObservation(view,"table-1","hand-1","turn-2",
                "game.test",4,true,true,HandStage.TURN,5000,100,cost,tap,
                equityEvidence=equity)
        var now=1080L
        val inbox=TurnScopedProposalMailbox()
        val strategy=ValidatedPlayerStrategy(VerifiedLocalPokerStrategy(),inbox,{now})
        val trustedEquity=FrameBoundEquity("hand-1","turn-2",7,20,.20,300,true)
        fun proposal(action:LiveAction=LiveAction.CALL,turn:String="turn-2") =
            PlayerModelProposal(action,"hand-1",turn,7,20,4,1050,1200)
        ok(strategy.decide(obs(0))?.action==LiveAction.CHECK,
            "local plan works without cloud")
        ok(inbox.offer(proposal()),"accept one candidate")
        ok(!inbox.offer(proposal()),"inbox does not replace advice mid-turn")
        ok(strategy.decide(obs(20))==null,
            "cloud CALL cannot manufacture missing equity")
        ok(strategy.decide(obs(0))?.action==LiveAction.CHECK,
            "after one-shot message local fallback resumes")
        ok(inbox.offer(proposal(turn="other")),"queue stale suggestion")
        ok(strategy.decide(obs(20,trustedEquity))?.action==LiveAction.FOLD,
            "wrong turn advice ignored; local math decides")
        ok(inbox.offer(proposal()),"queue good CALL")
        ok(strategy.decide(obs(20,trustedEquity))?.action==LiveAction.CALL,
            "current, signed-equity call is an authorized candidate only")
        now=1205
        ok(inbox.offer(proposal()),"queue expired suggestion")
        ok(strategy.decide(obs(20,trustedEquity))?.action==LiveAction.FOLD,
            "expired GPT decision never reaches transport")
        now=1080
        ok(inbox.offer(proposal()),"queue before STOP")
        inbox.clear()
        ok(strategy.decide(obs(20,trustedEquity))?.action==LiveAction.FOLD,
            "STOP resets queued cloud decision")
        println("ValidatedPlayerStrategyScenarios: $passed/$passed passed")
    }
}
