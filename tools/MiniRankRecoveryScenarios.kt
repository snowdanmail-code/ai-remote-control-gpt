package com.example.airemote

/** Regression on the two observed preflop screenshots 2s+6d / Kd+7h. */
object MiniRankRecoveryScenarios {
    @JvmStatic fun main(args:Array<String>){
        var n=0
        fun checkThat(v:Boolean,reason:String){check(v){reason};n++}
        fun line(t:String,x:Int=450,y:Int=923)=PositionedOcrLine(
            t,NormalizedTextBox(x-90,y-12,x+90,y+12))
        checkThat(PreflopBottomRankText.parse(listOf(line("6 2")),true)
            == listOf('6','2'),"Cyrillic may disappear, leaving numeric ranks")
        checkThat(PreflopBottomRankText.parse(listOf(line("К 7")),true)
            == listOf('K','7'),"Cyrillic king rank and seven")
        checkThat(PreflopBottomRankText.parse(listOf(line("Старшая рука: 6 2")),true)
            == listOf('6','2'),"full bottom text still parses")
        checkThat(PreflopBottomRankText.parse(listOf(line("6 2")),false)==null,
            "lower preflop diagnostic banned after flop")
        checkThat(PreflopBottomRankText.parse(listOf(line("6 2",450,600)),true)==null,
            "two random numbers elsewhere are NOT bottom hand")
        checkThat(PreflopBottomRankText.parse(listOf(line("1 0")),true)==null,
            "1 and 0 are NEVER two separate card ranks")
        checkThat(PreflopBottomRankText.parse(listOf(line("10 4")),true)
            == listOf('T','4'),"ten is a single valid rank")
        val partial=CardReading(listOf("2s",null), emptyList(),false,0.0,
            "unreadable rank",listOf("2 ♠","? ♦"),emptyList(),
            miniRankHints=listOf('2','6'))
        val shown=VisibleCardOverlayText.showHeroWithMini(partial)
        checkThat("2 ♠" in shown && "6 ♦*" in shown && "НЕ ПОДТВЕРЖДЕНО" in shown,
            "mini can assist display-only rank with explicit warning")
        val without=VisibleCardOverlayText.showHeroWithMini(partial.copy(
            miniRankHints=emptyList()))
        checkThat("? ♦" in without && "6 ♦*" !in without,
            "no nonexistent mini observations")
        val known=partial.copy(hole=listOf("2s",null),
            holeGlyphs=listOf("2 ♠","К ♦"),miniRankHints=listOf('2','7'))
        checkThat("К ♦" in VisibleCardOverlayText.showHeroWithMini(known) &&
            "7 ♦*" !in VisibleCardOverlayText.showHeroWithMini(known),
            "a conflicting mini hint must NOT replace visual rank")
        val gate=PreflopRankHintGate()
        checkThat(gate.offer(4,1,0,listOf('2','6'),1000),"preflop initially open")
        checkThat(!gate.offer(4,2,3,listOf('2','6'),1500),"flop latches closed")
        checkThat(!gate.offer(4,3,0,listOf('2','6'),2000),"missed flop cannot re-arm")
        checkThat(!gate.offer(4,4,0,listOf('K','7'),2500),"one new hand hint insufficient")
        checkThat(gate.offer(4,5,0,listOf('K','7'),3300),"two distinct mini frames rearm")
        println("MiniRankRecoveryScenarios: $n PASS")
    }
}
