package com.example.airemote

/** Poker-only simulation. No game app, no taps and no claim about opponents. */
object UniformUnknownEquityScenarios {
    @JvmStatic fun main(args:Array<String>) {
        var n=0
        fun ok(b:Boolean, name:String){check(b){name};n++}
        fun hand(vararg c:String)=c.map(PokerCard::parse)
        val royal=hand("Ah","Kh")
        val river=hand("Qh","Jh","Th","2c","3d")
        val a=UniformUnknownPokerEquity.compute(royal,river,2,samples=200,seed=13)
        ok(a.winProbability==1.0 && a.lossProbability==0.0 &&
            a.tieProbability==0.0 && a.potShareEquity==1.0,
            "unbeatable royal flush against two hidden opponents")
        val b=UniformUnknownPokerEquity.compute(royal,river,2,samples=200,seed=13)
        ok(a==b,"deterministic simulation for reproducible diagnostics")
        val c=UniformUnknownPokerEquity.compute(hand("9c","8d"),emptyList(),
            1,samples=200,seed=71)
        ok(c.runouts==200 && !c.exact,"preflop sample count")
        ok(kotlin.math.abs(c.winProbability+c.tieProbability+c.lossProbability-1.0)<0.00001,
            "exclusive outcomes sum to 100 percent")
        ok(c.potShareEquity in 0.0..1.0,"pot share bounded")
        val d=UniformUnknownPokerEquity.compute(hand("Ac","As"),
            hand("3h","4h","5h","6h"),3,samples=200,seed=20)
        ok(d.potShareEquity in 0.0..1.0,"multiple opponents, turn")
        var rejected=false
        try { UniformUnknownPokerEquity.compute(hand("Ah","Ah"),river,1) }
        catch (_: IllegalArgumentException) { rejected=true }
        ok(rejected,"duplicate physical card rejected")
        rejected=false
        try { UniformUnknownPokerEquity.compute(royal,river,9) }
        catch (_: IllegalArgumentException) { rejected=true }
        ok(rejected,"unverified count outside bound rejected")
        println("UniformUnknownEquityScenarios: $n/$n PASS")
    }
}
