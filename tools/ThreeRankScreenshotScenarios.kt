package com.example.airemote

/** Verified 3♥ from 709x1536 operator capture. Stores a single glyph only. */
object ThreeRankScreenshotScenarios {
    private const val GLYPH = "00000007ffe00fffe00fffe00f0fc00e0f80061f00003e0000fe0000ff8000ffc00001e00000e00000f00000f00000f00000f00e00e00f01e007c7c003ff8001ff00007c00000000"
    @JvmStatic fun main(args: Array<String>) {
        check(GLYPH.length == 144)
        val sample = IntArray(24) { row ->
            GLYPH.substring(row * 6, row * 6 + 6).toInt(16)
        }
        check(VerifiedCardRankSamples.classifyMask(sample)?.rank == '3') {
            "Observed table three was missed, must classify glyph as 3"
        }
        check(VerifiedCardRankSamples.sampleSanityChecks() ==
            VerifiedCardRankSamples.sampleCount)
        check(VerifiedCardRankSamples.classifyMask(IntArray(24)) == null)
        check(VerifiedCardRankSamples.classifyMask(IntArray(24) { 0xffffff }) == null)
        println("ThreeRankScreenshotScenarios: PASS")
    }
}
