package com.example.airemote

/** Synthetic pixels only; never a genuine hand or an action. */
object CenteredBoardAnchorScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var n=0
        fun ok(v:Boolean,s:String){check(v){s};n++}
        fun raster(count:Int,w:Int=540,h:Int=1170,sy:Int=546,x0:Int=94):VisionRaster {
            val arr=IntArray(w*h){0xff202020.toInt()}
            val cardW=(w*0.124).toInt()
            val cardH=(h*0.079).toInt()
            val pitch=(w*0.131).toInt()
            for(i in 0 until count)for(y in sy until sy+cardH)for(x in x0+i*pitch until x0+i*pitch+cardW)
                if(x in 0 until w && y in 0 until h)arr[y*w+x]=0xfff6f6f6.toInt()
            return VisionRaster(w,h,arr)
        }
        for (k in 3..5) {
            val pixels=raster(k)
            ok(CenteredTableBoardReader.locate(pixels).size==k,"find card row: "+k)
            val reading=CenteredTableBoardReader.read(pixels)
            ok(!reading.complete && reading.cards.size==k,
                "Blank rectangles NEVER hallucinate rank and suit: "+k)
        }
        ok(CenteredTableBoardReader.locate(raster(2)).isEmpty(),"2 white cards not board")
        ok(CenteredTableBoardReader.locate(raster(4,sy=600)).size==4,"vertical variation")
        ok(CenteredTableBoardReader.locate(raster(3,w=640,x0=112)).size==3,
            "non-standard portrait aspect")
        val blank=VisionRaster(540,1170,IntArray(540*1170){0xff202020.toInt()})
        ok(CenteredTableBoardReader.locate(blank).isEmpty(),"empty frame")
        val known=VisibleCommunityReading(listOf("2d","9s","4h"),true,"CENTERED")
        val unknown=VisibleCommunityReading(emptyList(),false,"OLD")
        ok(TableBoardCandidateSelector.choose(unknown,known)==known,"recover missed board")
        val conflict=VisibleCommunityReading(listOf("Ad","9s","4h"),true,"OLD")
        ok(!TableBoardCandidateSelector.choose(conflict,known).complete,"reject reader mismatch")
        val hero=CardReading(listOf("Ah","Kd"),emptyList(),true,.95,"OLD_PREFLOP")
        val guarded=CenteredPlayerBoardFusion.merge(hero,known)
        ok(!guarded.complete&&guarded.community==known.cards,"do not fake preflop")
        val noHero=CardReading(emptyList(),emptyList(),false,0.0,"NO_HERO")
        ok(!CenteredPlayerBoardFusion.merge(noHero,known).complete,"do not invent hero cards")
        fun line(text:String,x:Int,y:Int)=PositionedOcrLine(text,
            NormalizedTextBox(x,y,x+80,y+20))
        val anchor=CenterTableAnchorText.inspect(listOf(
            line("Банк:",460,404),line("3.95 $",460,576)))
        ok(anchor.bankLabelAbove&&anchor.chipAmountBelow,"both pot anchors")
        val wrong=CenterTableAnchorText.inspect(listOf(
            line("Банк:",460,70),line("3.95 $",460,800)))
        ok(!wrong.bankLabelAbove&&!wrong.chipAmountBelow,"wrong labels not anchors")
        println("CenteredBoardAnchorScenarios: "+n+" checks passed")
    }
}
