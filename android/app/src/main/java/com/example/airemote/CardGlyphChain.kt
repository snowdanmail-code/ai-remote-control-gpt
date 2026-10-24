package com.example.airemote

import kotlin.math.max
import kotlin.math.min

/** Human-readable evidence for one REAL cropped card, never a game action. */
data class CardGlyphReading(
    val rank: Char?,
    val suit: Char?,
    val color: String?,
    val code: String?,
    val preview: String,
    /** Relative agreement with stored visual glyphs, not a calibrated probability. */
    val quality: Double
)

/**
 * The single assembly point:
 * 1) read the rank, 2) read the suit, 3) check ink color family,
 * 4) combine ONLY two independently supported parts.
 * Keep partial observations for the visual overlay; no invented cards.
 */
object CardGlyphChain {
    private val names = mapOf(
        'A' to "Т", 'K' to "К", 'Q' to "Д", 'J' to "В", 'T' to "10"
    )
    private val glyphs = mapOf('h' to "♥", 'd' to "♦", 's' to "♠", 'c' to "♣")
    private val validRanks = "23456789TJQKA"

    fun combine(rank: RankCandidate?, suit: SuitCandidate?, inkRed: Boolean? = null): CardGlyphReading {
        val r = rank?.rank?.takeIf { it in validRanks }
        val rawSuit = suit?.suit?.takeIf { it in glyphs }
        val compatible = rawSuit == null || inkRed == null ||
            (rawSuit in "hd") == inkRed
        val s = if (compatible) rawSuit else null
        val displayRank = r?.let { names[it] ?: it.toString() } ?: "?"
        val displaySuit = s?.let { glyphs[it] } ?: "?"
        val code = if (r != null && s != null) "$r$s" else null
        return CardGlyphReading(r, s, inkRed?.let { if (it) "red" else "black" },
            code, "$displayRank $displaySuit",
            if (code != null && rank != null && suit != null)
                min(rank.similarity, suit.confidence) else 0.0)
    }

    /** A color check, not suit recognition: red = ♥/♦, black = ♠/♣. */
    fun inkRed(r: VisionRaster, card: VisionCardBox): Boolean? {
        if (card.width < 24 || card.height < 40 ||
            card.left < 0 || card.top < 0 ||
            card.right > r.width || card.bottom > r.height) return null
        val left = card.left + max(1, card.width * 5 / 100)
        val right = card.left + card.width * 39 / 100
        val top = card.top + max(1, card.height * 6 / 100)
        val bottom = card.top + card.height * 55 / 100
        if (left >= right || top >= bottom) return null
        var reds=0; var darks=0
        for (y in top until bottom step 2) for (x in left until right step 2) {
            val p=r.rgb(x,y)
            val red=(p ushr 16) and 255
            val green=(p ushr 8) and 255
            val blue=p and 255
            if (red > 100 && red > green*1.45 && red > blue*1.3) reds++
            if (max(red,max(green,blue)) < 140) darks++
        }
        return when {
            reds >= 3 && reds > darks*3 -> true
            darks >= 3 && darks > reds*3 -> false
            else -> null // a noisy/occluded corner must not become a guess
        }
    }

    fun read(r: VisionRaster, card: VisionCardBox): CardGlyphReading {
        val rank = CardVisionCore.classifyRank(r, card)
        val suit = CardVisionCore.classifySuit(r, card)
        val ink = inkRed(r, card)
        val baseline = combine(rank, suit, ink)
        // The visually similar 6, 8 and 9 are structurally distinguishable.
        // This independent check is used ONLY as a provisional overlay label.
        // It never manufactures a CardReading code for autonomous gameplay.
        val topology = RoundDigitTopology.hint(r, card)
        val conflict = rank?.rank?.let { it in "689" } == true &&
            topology != null && rank.rank != topology
        val unknown = rank == null
        if (!conflict && !unknown) return baseline
        if (topology == null) return baseline
        val provisional = combine(null, suit, ink)
        if (provisional.suit == null) return baseline
        val label = topology.toString() + " " +
            (when (provisional.suit) {
                'h' -> "♥"; 'd' -> "♦"; 's' -> "♠"; 'c' -> "♣"
                else -> "?"
            }) + "*"
        // rank/code remain null; the trailing * flags an unverified hypothesis.
        return provisional.copy(preview=label)
    }
}
