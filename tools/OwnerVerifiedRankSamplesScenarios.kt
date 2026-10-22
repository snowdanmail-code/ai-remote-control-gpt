package com.example.airemote

/** Source-mask self-consistency is NOT proof of generalization or live game success. */
object OwnerVerifiedRankSamplesScenarios {
    @JvmStatic fun main(args:Array<String>){
        val count=VerifiedCardRankSamples.sampleCount
        check(count==23){"Unexpected card data size: $count"}
        check(VerifiedCardRankSamples.sampleSanityChecks()==count){
            "At least one owner-labeled glyph is self-inconsistent"
        }
        check(VerifiedCardRankSamples.classifyMask(IntArray(24))==null){
            "Blank rank must be unknown"
        }
        check(VerifiedCardRankSamples.classifyMask(IntArray(24){0xffffff})==null){
            "Solid rectangle is not a rank"
        }
        println("OwnerVerifiedRankSamplesScenarios: "+(count+2)+" checks PASS")
    }
}
