package com.example.airemote

/** The rank→suit→color→card chain is deterministic; previews are NOT actions. */
object CardGlyphChainScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var n=0
        fun t(ok:Boolean, why:String) { check(ok){why};n++ }
        val jack=RankCandidate('J',0.97)
        val diamond=SuitCandidate('d',0.93)
        val full=CardGlyphChain.combine(jack,diamond,true)
        t(full.code=="Jd"&&full.preview=="В ♦","J plus diamond = jack of diamonds")
        t(full.quality>0.9,"full card retains least glyph similarity")
        val rankOnly=CardGlyphChain.combine(jack,null,true)
        t(rankOnly.code==null&&rankOnly.preview=="В ?","preserve rank-only evidence")
        val suitOnly=CardGlyphChain.combine(null,diamond,true)
        t(suitOnly.code==null&&suitOnly.preview=="? ♦","preserve suit-only evidence")
        val wrongColor=CardGlyphChain.combine(jack,diamond,false)
        t(wrongColor.code==null&&wrongColor.preview=="В ?",
            "red diamond cannot combine with black ink")
        t(CardGlyphChain.combine(RankCandidate('A',1.0),SuitCandidate('s',1.0),false)
            .preview=="Т ♠","ace spades abbreviation")
        t(CardGlyphChain.combine(RankCandidate('Q',1.0),SuitCandidate('h',1.0),true)
            .preview=="Д ♥","queen hearts abbreviation")
        val partial=CardReading(listOf(null,null),listOf(null),false,0.0,"some",
            listOf("В ?","? ♦"), listOf("10 ?"))
        val labels=VisibleCardOverlayText.project(partial,null,2,1,1000,1200,true,false,2)
        t(labels?.hero=="В ?  ·  ? ♦"&&labels.board=="10 ?",
            "on-screen display retains incomplete parts")
        t(labels?.isConfirmed==false,"partial glyphs cannot be marked confirmed")
        val original=CardReading(listOf("Ah","Kd"),emptyList(),false,0.94,"old",
            listOf("Т ♥","К ♦"))
        val board=VisibleCommunityReading(listOf("2d","9s","4h"),true,"centered",
            listOf("2 ♦","9 ♠","4 ♥"),0.95)
        val joined=CenteredPlayerBoardFusion.merge(original,board)
        t(joined.complete && joined.hole==original.hole &&
            joined.community==board.cards &&
            joined.communityGlyphs==board.glyphs,
            "existing two hero and three board cards combined from independent readers")
        val damaged=CenteredPlayerBoardFusion.merge(original.copy(
            minimumSimilarity=0.0),board)
        t(!damaged.complete,"do not fabricate confidence for weakly observed cards")
        val mismatch=CenteredPlayerBoardFusion.merge(original.copy(
            community=listOf("7d",null,null)),board)
        t(!mismatch.complete,"conflicting board card invalidates combined state")
        t(VisibleCardOverlayText.show(listOf(null),"?",
            listOf("? ?"))=="НЕ РАСПОЗНАНО","fully unknown stays unknown")
        println("CardGlyphChainScenarios: "+n+" PASS")
    }
}
