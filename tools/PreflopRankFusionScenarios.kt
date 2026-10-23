package com.example.airemote

/** Regression cases from operator screenshots: 2♠+6♦ and K♦+7♥. */
object PreflopRankFusionScenarios {
    @JvmStatic fun main(args:Array<String>){
        var n=0
        fun ok(condition:Boolean, message:String){check(condition){message};n++}
        fun reading(hole:List<String?>, glyphs:List<String>,board:List<String?> =emptyList())=
            CardReading(hole,board,false,0.0,"read only",holeGlyphs=glyphs)
        val a=reading(listOf("2s",null),listOf("2 ♠","? ♦"))
        val hint=PreflopRankFusion.preview(a,listOf('2','6'),listOf('6','2'),true)
        ok("2 ♠" in hint && "6 ♦*" in hint && "МИНИ + НИЗ" in hint,
            "same real preflop screenshots: order-independent mini+bottom recover 6")
        val bottomOnly=PreflopRankFusion.preview(a,emptyList(),listOf('6','2'),true)
        ok("6 ♦*" in bottomOnly && "НИЗ ДО ФЛОПА" in bottomOnly,
            "previous known 2 and unordered 6/2 suffice to label remaining rank")
        val first=reading(listOf(null,null),listOf("? ♦","? ♥"))
        val two=PreflopRankFusion.preview(first,listOf('K','7'),listOf('K','7'),true)
        ok("К ♦*" in two && "7 ♥*" in two,"second screenshot two ranks from mini and bottom")
        ok("К ♦*" in PreflopRankFusion.preview(first,listOf('K','7'),null,true),
            "mini-only recovery must remain marked tentative")
        val wrong=PreflopRankFusion.preview(first,listOf('K','7'),listOf('Q','7'),true)
        ok("К ♦*" !in wrong && "7 ♥*" !in wrong,
            "conflicting mini vs bottom must NOT silently fill")
        val afterFlop=PreflopRankFusion.preview(a,emptyList(),listOf('6','2'),false)
        ok("6 ♦*" !in afterFlop,"bottom never used after flop")
        val board=PreflopRankFusion.preview(
            a.copy(community=listOf(null,null,null)),emptyList(),listOf('6','2'),true)
        ok("6 ♦*" !in board,"flop on screen forbids old bottom hint")
        val inconsistent=PreflopRankFusion.preview(a,listOf('K','7'),listOf('6','2'),true)
        ok("6 ♦*" !in inconsistent && "7 ♦*" !in inconsistent,
            "known big-card rank contradiction rejects mini and bottom")
        val noSuit=reading(listOf(null,null),listOf("? ?","? ?"))
        ok("К ?" !in PreflopRankFusion.preview(noSuit,listOf('K','7'),listOf('K','7'),true),
            "cannot fabricate unknown suit")
        val noDigits=reading(listOf(null,null),listOf("? ♣","? ♥"))
        ok("1 ♣" !in PreflopRankFusion.preview(noDigits,listOf('1','0'),null,true),
            "1 and 0 are never standalone poker card ranks")
        val duplicate=PreflopRankFusion.preview(first,listOf('K','K'),listOf('K','K'),true)
        ok("К ♦*" in duplicate && "К ♥*" in duplicate,
            "a pair of two equal ranks is valid, preserve duplicate-rank multiset")
        println("PreflopRankFusionScenarios: $n checks PASS")
    }
}
