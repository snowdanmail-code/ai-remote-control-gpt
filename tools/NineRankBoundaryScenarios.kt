package com.example.airemote

/** 9♥ extracted from operator Code92 screenshot, rank-only binary mask.
 * Previously rejected due to margin with Q. No user identifiers retained.
 */
object NineRankBoundaryScenarios {
    private const val NINE_MASK =
        "00000001ff0003ff8007c7800783c00783c00603c00600c00403c00603c00603c00787c00787c007ffc003ffc0007fc00003c0020780078f80078f8007ff0003fc00003800000000"

    @JvmStatic fun main(args: Array<String>) {
        check(NINE_MASK.length == 144)
        val rows = IntArray(24) { y -> NINE_MASK.substring(y*6,y*6+6).toInt(16) }
        check(VerifiedCardRankSamples.classifyMask(rows)?.rank == '9') {
            "Real owner screenshot nine must not be rejected as Q-like"
        }
        check(VerifiedCardRankSamples.classifyMask(IntArray(24)) == null) {
            "Blank card cannot become 9"
        }
        val artifact = IntArray(24) { if (it == 4) 0x100 else 0 }
        check(VerifiedCardRankSamples.classifyMask(artifact) == null) {
            "Artifact cannot become a nine"
        }
        check(VerifiedCardRankSamples.sampleSanityChecks() ==
            VerifiedCardRankSamples.sampleCount) {
            "New nine must not break existing labeled masks"
        }
        println("NineRankBoundaryScenarios: 4 checks PASS")
    }
}
