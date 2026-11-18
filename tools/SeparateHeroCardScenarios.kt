package com.example.airemote

/** Checks isolated lower-half white-card geometry; no real gameplay/touches. */
object SeparateHeroCardScenarios {
    @JvmStatic fun main(args:Array<String>) {
        var checks=0
        fun ok(test:Boolean,label:String){check(test){label};checks++}
        fun screen(count:Int,offset:Int=0,w:Int=540,h:Int=1170):VisionRaster {
            val px=IntArray(w*h){0xff242424.toInt()}
            val cardWidth=(w*0.124).toInt()
            val cardHeight=(h*0.077).toInt()
            val top=(h*0.69).toInt()
            val left=(w*0.365).toInt()+offset
            val gap=(w*0.016).toInt()
            for(k in 0 until count) {
                val x0=left+k*(cardWidth+gap)
                for(y in top until top+cardHeight)for(x in x0 until x0+cardWidth)
                    if(x in 0 until w && y in 0 until h)
                        px[y*w+x]=0xfff6f6f6.toInt()
            }
            return VisionRaster(w,h,px)
        }
        val r=screen(2)
        val pair=SeparateHeroCardReader.locate(r)
        ok(pair.size==2, "two independent lower-half white cards")
        ok(pair[0].left<pair[1].left, "left-to-right hand")
        ok(SeparateHeroCardReader.read(r)==null,
            "plain white cards are NOT claimed as rank/suit")
        ok(SeparateHeroCardReader.locate(screen(1)).isEmpty(),
            "one card is not a complete hero pair")
        ok(SeparateHeroCardReader.locate(screen(3)).isEmpty(),
            "three cards must not become the player's hand")
        ok(SeparateHeroCardReader.locate(screen(2,offset=16)).size==2,
            "pair can move within the lower-center area")
        ok(SeparateHeroCardReader.locate(screen(2,w=640,h=1170)).size==2,
            "nonstandard phone aspect")
        val blank=VisionRaster(540,1170,IntArray(540*1170))
        ok(SeparateHeroCardReader.locate(blank).isEmpty(), "no cards: no guess")
        val legacy=CardReading(listOf("Ah","Kd"),emptyList(),true,.95,"VERIFIED_ONLY_BY_OLD_VISION")
        val alternative=CardReading(listOf("Qs","Jc"),emptyList(),true,.90,
            "SEPARATE_HERO_PAIR_UNVERIFIED")
        ok(AdaptiveCardCandidateResolver.choose(legacy,alternative)==legacy,
            "independent observation cannot replace known completed primary hand")
        val oldUnknown=CardReading(emptyList(),emptyList(),false,0.0,"UNKNOWN")
        ok(AdaptiveCardCandidateResolver.choose(oldUnknown,alternative)==alternative,
            "complete nonconflicting candidate may enter existing consensus")
        fun line(text:String,y:Int)=PositionedOcrLine(text,
            NormalizedTextBox(430,y,590,y+20))
        val bank=CenterTableAnchorText.inspect(
            listOf(line("3.95 $",444),line("3.95 $",585)))
        ok(bank.amountAbove && bank.chipAmountBelow,
            "decimal bank amounts recognized without Cyrillic text")
        ok(!bank.bankLabelAbove, "no fictional Cyrillic bank label")
        println("SeparateHeroCardScenarios: $checks checks passed")
    }
}
