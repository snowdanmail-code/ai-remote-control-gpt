package com.example.airemote

/** Owner-labeled 8 corner suit silhouettes; glyph-only, no account info. */
object VerifiedCardCornerSuitScenarios {
    @JvmStatic fun main(args:Array<String>){
        val count=VerifiedCardCornerSuitSamples.templateCount
        check(count==8){"Expected 8 separate board/hero suit templates"}
        check(VerifiedCardCornerSuitSamples.selfCheck()==count){
            "At least one suit mask not self-consistent"
        }
        check(VerifiedCardCornerSuitSamples.classifyMask(IntArray(24),true)==null){
            "Blank cannot become a red suit"
        }
        check(VerifiedCardCornerSuitSamples.classifyMask(IntArray(24),false)==null){
            "Blank cannot become a black suit"
        }
        check(VerifiedCardCornerSuitSamples.classifyMask(IntArray(24){0xffffff},true)==null){
            "Solid rectangle cannot be accepted as red suit"
        }
        println("VerifiedCardCornerSuitScenarios: "+(count+3)+" checks PASS")
    }
}
