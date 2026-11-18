package com.example.airemote

/** Two explicit state constraints: mini-rank comparison and preflop-only bottom text. */
object PreflopRankHintsScenarios {
    @JvmStatic fun main(args:Array<String>) {
        var n=0
        fun t(ok:Boolean,reason:String){check(ok){reason};n++}
        val gate=PreflopRankHintGate()
        t(gate.offer(1,1,0,listOf('A','K'),1000),"first hand allows preflop")
        t(gate.offer(1,2,0,listOf('A','K'),2400),"same preflop allows")
        t(!gate.offer(1,3,3,listOf('A','K'),3800),"first three board cards lock")
        t(!gate.offer(1,4,0,listOf('A','K'),5200),"board detection lapse NEVER unlocks")
        t(!gate.offer(1,5,0,emptyList(),6600),"missing mini never unlocks")
        t(!gate.offer(1,6,0,listOf('6','5'),8000),"one new mini image insufficient")
        t(gate.offer(1,7,0,listOf('6','5'),9350),"two new mini images start next hand")
        t(gate.offer(1,8,0,listOf('6','5'),10700),"new preflop remains open")
        t(!gate.offer(1,9,4,listOf('6','5'),12000),"turn board locks")
        t(!gate.offer(1,10,-1,listOf('3','4'),13400),"unknown board never permits hint")
        t(!gate.offer(1,11,0,listOf('3','4'),14800),"first next hand sample not enough")
        t(gate.offer(1,12,0,listOf('3','4'),16150),"second known new hand enables")
        t(gate.offer(2,1,0,listOf('Q','J'),17000),"new capture epoch resets gate")
        t(!gate.offer(-1,1,0,emptyList(),18000),"invalid capture rejected")

        fun line(text:String,y:Int)=PositionedOcrLine(text,
            NormalizedTextBox(280,y,760,y+24))
        val before=listOf(line("Старшая рука: 10 4",918))
        t(PreflopBottomRankText.parse(before,true)==listOf('T','4'),
            "parse two ranks only in allowed phase")
        t(PreflopBottomRankText.parse(before,false)==null,
            "ignore identical text after flop")
        // A standard deck has exactly 13 ranks. Standalone digits 1 and 0
        // are NOT ranks: two glyphs are a single 10 within ONE card crop.
        t(CardVisionCore.parseRank("10")=="T",
            "the two digits 1+0 inside one card mean rank ten")
        t(CardVisionCore.parseRank("1O")=="T",
            "OCR letter O may be the zero in 10 in the same card crop")
        t(CardVisionCore.parseRank("1 0")=="T",
            "spaced 1 and 0 inside one card crop remain a single ten")
        t(CardVisionCore.parseRank("1")==null &&
            CardVisionCore.parseRank("0")==null,
            "standalone 1 and standalone 0 can never be card ranks")
        t(PreflopBottomRankText.parse(listOf(line("Старшая рука: 1 0",918)),true)==null,
            "OCR 1 and 0 must not become two different card ranks")
        t(PreflopBottomRankText.parse(listOf(line("Старшая рука: 10 6",918)),true)
            ==listOf('T','6'),"10 is one rank, not 1 and 0");
        t(CardGlyphChain.combine(RankCandidate('1',.99),
            SuitCandidate('h',.99),true).code==null &&
            CardGlyphChain.combine(RankCandidate('0',.99),
            SuitCandidate('d',.99),true).code==null,
            "a suit cannot turn an invalid 1 or 0 into a valid card")

        t(PreflopBottomRankText.parse(listOf(line("Пара 9 с кикерами К Д 10",918)),true)==null,
            "postflop combination text is not preflop hint")
        t(PreflopBottomRankText.parse(listOf(line("Старшая рука: 10 4",110)),true)==null,
            "wrong screen region ignored")
        t(PreflopBottomRankText.parse(listOf(
            line("Старшая рука: Т 4",918),line("Старшая рука: К 4",919)),true)==null,
            "conflicting OCR text discarded")
        t(MiniHeroRankReader.read(VisionRaster(540,1170,
            IntArray(540*1170){0xff242424.toInt()})).isEmpty(),
            "empty mini chip must not invent card ranks")
        val pair = CardReading(listOf(null,null),emptyList(),false,0.0,
            "hero visual preliminary", miniRankHints=listOf('T','4'))
        val hint = PreflopRankHintSnapshot(8,2,1000,true,
            listOf('T','4'),listOf('T','4'))
        val current = PreflopRankHintSnapshot(8,3,1100,true,listOf('T','4'))
        val inPreflop=VisibleCardOverlayText.project(
            pair,null,8,3,1150,1400,true,false,8,
            bottomRankHint=hint,currentPreflopHint=current)
        t(inPreflop?.hero?.contains("ДО ФЛОПА: 10 4")==true,
            "preflop OCR rank hint visible on overlay")
        val afterFlop=VisibleCardOverlayText.project(
            pair,null,8,3,1150,1400,true,false,8,
            bottomRankHint=hint,currentPreflopHint=current.copy(allowed=false))
        t(afterFlop?.hero?.contains("ДО ФЛОПА:")==false,
            "flop blocks old bottom rank on UI")
        val differentHand=VisibleCardOverlayText.project(
            pair.copy(miniRankHints=listOf('3','7')),null,8,3,1150,1400,
            true,false,8,bottomRankHint=hint,currentPreflopHint=current)
        t(differentHand?.hero?.contains("ДО ФЛОПА:")==false,
            "new hero cannot receive stale old-hand bottom rank")
        println("PreflopRankHintsScenarios: $n checks PASS")
    }
}
