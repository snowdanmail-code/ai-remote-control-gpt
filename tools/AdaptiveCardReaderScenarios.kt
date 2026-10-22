package com.example.airemote

/** Synthetic image geometry + pure trust-boundary tests, no Android taps. */
object AdaptiveCardReaderScenarios {
    @JvmStatic fun main(args: Array<String>) {
        var passed = 0
        fun verify(value:Boolean, why:String){check(value){why};passed++}
        val blank = IntArray(540 * 1170) { 0xff202020.toInt() }
        // Shifted full pair outside the fixed 240-420 legacy scan assumptions.
        for (y in 830 until 930) for (x in 165 until 289) {
            blank[y*540+x] = 0xffffffff.toInt()
        }
        val raster=VisionRaster(540,1170,blank)
        val boxes=AdaptivePlayerCardReader.locateHeroBoxes(raster)
        verify(boxes.isNotEmpty(), "Adaptive detector finds a shifted near-white hero pair")
        verify(boxes.any { pair -> pair.size==2 && pair[0].left <= 165 &&
            pair[1].right >= 289 }, "Two connected cards in broad ROI")
        verify(AdaptivePlayerCardReader.read(raster)==null,
            "Blank white rectangles cannot be invented as known cards")
        val wrongGeometry=VisionRaster(1170,540,IntArray(1170*540))
        verify(AdaptivePlayerCardReader.locateHeroBoxes(wrongGeometry).isEmpty(),
            "Reject landscape")
        val source=CardReading(listOf("Ah",null),emptyList(),false,0.0,"incomplete")
        val safe=CardReading(listOf("Ah","Kd"),emptyList(),true,.94,
            "ADAPTIVE_HERO_PAIR_UNVERIFIED")
        val conflict=CardReading(listOf("Qs","Kd"),emptyList(),true,.94,
            "ADAPTIVE_HERO_PAIR_UNVERIFIED")
        verify(AdaptiveCardCandidateResolver.choose(source,safe)==safe,
            "Only compatible complete fallback accepted as candidate")
        verify(AdaptiveCardCandidateResolver.choose(source,conflict)==source,
            "Existing known rank disagreement rejects fallback")
        val original=CardReading(listOf("Ah","Kd"),emptyList(),true,.97,"PRIMARY")
        verify(AdaptiveCardCandidateResolver.choose(original,safe)==original,
            "Legacy complete original always wins")
        val boardConflict=CardReading(emptyList(),listOf("2h",null,null),false,0.0,"partial")
        verify(AdaptiveCardCandidateResolver.choose(boardConflict,safe)==boardConflict,
            "Never erase a partial board to fake preflop")
        verify(AdaptiveCardCandidateResolver.choose(source,null)==source,
            "Missing alternative leaves legacy unchanged")
        println("AdaptiveCardReaderScenarios: "+passed+" checks passed")
    }
}
