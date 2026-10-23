package com.example.airemote

/**
 * Owner screenshot: K♦ / 9♥, 709x1536, cropped to the SECOND card's rank.
 * 61x83 normalized rank+corner mask contains NO user identity, balance, app
 * controls, gestures or full-screen data. Pixels are a red/white binary mask.
 *
 * Baseline crop left=5 is 5px too far right: the stored 9 matches after a
 * leftward correction. The regression must exercise the real CardVisionCore.
 */
object NineRankBoundaryScenarios {
    @JvmStatic fun main(args: Array<String>) {
        val rowBits = longArrayOf(
        0x0L,
        0x0L,
        0x1000000000000L,
        0xfe00000000000L,
        0x1ff00000000000L,
        0x3c700000000000L,
        0x38380000000000L,
        0x70380000000000L,
        0x70180000000000L,
        0x60380000000000L,
        0x70380000000000L,
        0x70380000000000L,
        0x38780000000000L,
        0x3ff80000000000L,
        0x1ff80000000000L,
        0x7f80000000000L,
        0x380000000000L,
        0x10700000000000L,
        0x38f00000000000L,
        0x3fe00000000000L,
        0x1fc00000000000L,
        0x3800000000000L,
        0x0L,
        0x0L,
        0x0L,
        0x81c0000000000L,
        0x3f7f0000000000L,
        0x7fff0000000000L,
        0xffff8000000000L,
        0xffff8000000000L,
        0xffff8000000000L,
        0xffff8000000000L,
        0xffff8000000000L,
        0xffff8000000000L,
        0x7fff0000000000L,
        0x3fff0000000000L
        )
        check(rowBits.size == 36)
        val pixels = IntArray(61 * 83) { 0xffffffff.toInt() }
        rowBits.forEachIndexed { y, row ->
            for (x in 0 until 61) if ((row and (1L shl (60 - x))) != 0L)
                pixels[y * 61 + x] = 0xffd00000.toInt()
        }
        val raster = VisionRaster(61, 83, pixels)
        val shifted = VisionCardBox(5, 0, 61, 83)
        val exact = VisionCardBox(0, 0, 61, 83)
        check(VerifiedCardRankSamples.classify(raster, exact)?.rank == '9') {
            "The existing screenshot nine rank must remain recognized"
        }
        check(VerifiedCardRankSamples.classify(raster, shifted) == null) {
            "The shifted ROI should reproduce an unknown rank"
        }
        check(VerifiedCardRankSamples.recoverTrimmedNine(raster, shifted)?.rank == '9') {
            "Verified 9 must be recoverable from 1..4px expanded alternate crops"
        }
        check(CardVisionCore.classifyRank(raster, shifted)?.rank == '9') {
            "Real rank pipeline must use the narrow nine-only rescue"
        }
        val emptyRaster = VisionRaster(61, 83, IntArray(61*83) { -1 })
        check(VerifiedCardRankSamples.recoverTrimmedNine(emptyRaster, shifted) == null) {
            "Blank cards may NEVER become a nine"
        }
        check(CardVisionCore.classifyRank(emptyRaster, shifted) == null) {
            "Blank rank stays unknown"
        }
        println("NineRankBoundaryScenarios: 6 checks PASS")
    }
}
