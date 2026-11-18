package com.example.airemote

import kotlin.math.max
import kotlin.math.min

/**
 * An independent POSITION DETECTOR for two face-up hero cards, separate from
 * the fixed 540x1170 legacy ROI. Scans a bounded lower-table zone for
 * near-white connected card surfaces; rank/suit still require strict template
 * classification. This produces visual candidates only, never table identity
 * or action permission.
 *
 * This is a candidate fallback for the PLAYER capture worker, not for the
 * spectator-only board, and not a gesture controller.
 */
object AdaptivePlayerCardReader {
    private const val REF_W = 540
    private const val REF_H = 1170

    private fun white(argb: Int): Boolean {
        val red = (argb ushr 16) and 255
        val green = (argb ushr 8) and 255
        val blue = argb and 255
        return min(red, min(green, blue)) >= 210 &&
            max(red, max(green, blue)) - min(red, min(green, blue)) < 48
    }

    private fun groups(positions: List<Int>, maxGap: Int): List<IntRange> {
        if (positions.isEmpty()) return emptyList()
        val answer = mutableListOf<IntRange>()
        var start = positions.first()
        var end = start
        for (v in positions.drop(1)) {
            if (v - end > maxGap) {
                answer.add(start..end)
                start = v
            }
            end = v
        }
        answer.add(start..end)
        return answer
    }

    /** Independently locates the *pair surface*, splitting it into 2 card boxes.
     * An absent or ambiguous silhouette returns no boxes (no guessed cards).
     */
    fun locateHeroBoxes(r: VisionRaster): List<List<VisionCardBox>> {
        val ratio = r.width.toDouble() / r.height
        if (r.height < 780 || ratio !in 0.32..0.80) return emptyList()
        fun sx(ref: Int): Int = ref * r.width / REF_W
        fun sy(ref: Int): Int = ref * r.height / REF_H
        val left = sx(150); val right = sx(480).coerceAtMost(r.width)
        val top = sy(715); val bottom = sy(1075).coerceAtMost(r.height)
        val goodRows = (top until bottom).filter { y ->
            var whiteCount = 0
            for (x in left until right) if (white(r.rgb(x, y))) whiteCount++
            whiteCount in sx(80)..sx(175)
        }
        // Candidate lower-table rows have a near-white vertical extent of 66..150
        val rowBands = groups(goodRows, max(1, sy(2)))
            .filter { it.last - it.first + 1 in sy(66)..sy(150) }
            .take(8)
        val result = mutableListOf<List<VisionCardBox>>()
        for (row in rowBands) {
            val height = row.last - row.first + 1
            val cols = (left until right).filter { x ->
                var n = 0
                for (y in row.first..row.last) if (white(r.rgb(x, y))) n++
                n * 100 >= height * 55
            }
            val candidates = groups(cols, max(1, sx(10)))
                .filter { it.last - it.first + 1 in sx(96)..sx(139) }
                .filter { (it.first + it.last) / 2 in sx(190)..sx(440) }
                .take(6)
            for (group in candidates) {
                val mid = (group.first + group.last + 1) / 2
                val a = VisionCardBox(group.first, row.first, mid, row.last + 1)
                val b = VisionCardBox(mid, row.first, group.last + 1, row.last + 1)
                if (a.width >= sx(45) && b.width >= sx(45))
                    result.add(listOf(a, b))
            }
        }
        return result.take(12)
    }

    /** Only unique, complete, high-template-match candidate rows are emitted.
     * If two rows plausibly encode different hands, refuse to select one.
     */
    fun read(r: VisionRaster): CardReading? {
        val boxes = locateHeroBoxes(r)
        if (boxes.isEmpty()) return null
        val publicBoard = TableBoardCandidateSelector.choose(
            CardVisionCore.readVisibleCommunity(r), CenteredTableBoardReader.read(r))
        // An explicitly PARTIAL board cannot be silently called preflop.
        if (!publicBoard.complete && publicBoard.cards.isNotEmpty()) return null
        val community = if (publicBoard.complete) publicBoard.cards.filterNotNull()
            else emptyList()
        val candidateHands = boxes.mapNotNull { pair ->
            val symbols = pair.map { CardGlyphChain.read(r, it) }
            if (symbols.any { it.code == null }) null else {
                val hand = symbols.mapNotNull { it.code }
                val similarity = symbols.minOf { it.quality }
                if (similarity < 0.78 ||
                    !CardVisionCore.checkUnique(hand, community)) null
                else CardReading(hand, community, true, similarity,
                    "ADAPTIVE_HERO_PAIR_UNVERIFIED: нет подтверждения стола, раздачи и хода",
                    symbols.map { it.preview }, publicBoard.glyphs)
            }
        }
        return if (candidateHands.size == 1) candidateHands.single() else null
    }
}

/**
 * The legacy full read has priority. A complete adaptive reading may repair
 * a missing/partial legacy row only when it does NOT contradict any known card.
 * The existing two-frame CardReadConsensus, stage and permission gates remain.
 */
object AdaptiveCardCandidateResolver {
    fun choose(legacy: CardReading, adaptive: CardReading?): CardReading {
        if (legacy.complete || adaptive?.complete != true) return legacy
        if (legacy.hole.isNotEmpty()) {
            if (legacy.hole.size != 2 || adaptive.hole.size != 2 ||
                legacy.hole.zip(adaptive.hole).any { (a, b) -> a != null && a != b })
                return legacy
        }
        if (legacy.community.isNotEmpty()) {
            if (legacy.community.size != adaptive.community.size ||
                legacy.community.zip(adaptive.community).any { (a,b) -> a != null && a != b })
                return legacy
        }
        return adaptive
    }
}
