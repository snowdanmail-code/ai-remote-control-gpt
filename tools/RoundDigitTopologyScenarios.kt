package com.example.airemote

/**
 * Actual screenshot CORNER GLYPHS only, no photos/names/account details.
 * 6♦/9♥ appeared on adjacent cards, 2♦/8♦ on adjacent cards.
 * Digit topology is intentionally read-only evidence, not a playing-card code.
 */
object RoundDigitTopologyScenarios {
    private val samples = mapOf(
        '6' to "000000001800007e0000ff8001e78003c10003800003fe0003ff0003e7800381c00381c00300c00380c00381c00381c001c38000ff80007e00001800000000000000000c00000000",
        '8' to "00000000ff0001ff8003c3c00381c00381c00381c003c3c001ff8001ff8003ffc003c3c00700e00700e00700e00701e003c3c003ff8000ff00003c00000000000000001c00000000",
        '9' to "000000007f8001ffc003e3c003c0e003c0e00780e00780600600e00780e00780e003c3e003c3e003ffe001ffe0003fe00000e00183c003c7c003c7c003ff8001ff00001c00000000"
    )
    @JvmStatic fun main(args: Array<String>) {
        var checks=0
        fun assertThat(value:Boolean, message:String) {
            check(value) { message }
            checks++
        }
        for((expected, encoded) in samples) {
            assertThat(encoded.length==144,"$expected: malformed source glyph")
            val mask=IntArray(24) { y ->
                encoded.substring(y*6,y*6+6).toInt(16)
            }
            assertThat(RoundDigitTopology.classifyMask(mask)==expected,
                "$expected: screenshot glyph missing or confused with another digit")
        }
        val known=VerifiedCardRankSamples.labeledRankMasks()
        assertThat(known.size==VerifiedCardRankSamples.sampleCount,
            "Must check all known owner-verified rank masks")
        known.forEachIndexed { index, (rank,mask) ->
            val actual=RoundDigitTopology.classifyMask(mask)
            assertThat(actual==null || actual==rank,
                "False positive on known sample #$index: expected $rank, topology $actual")
        }
        assertThat(RoundDigitTopology.classifyMask(IntArray(24))==null,
            "Blank corner must stay unknown")
        assertThat(RoundDigitTopology.classifyMask(IntArray(24){0xffffff})==null,
            "Solid black crop must stay unknown")
        println("RoundDigitTopologyScenarios: $checks checks PASS")
    }
}
