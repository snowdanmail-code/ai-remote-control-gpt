package com.example.airemote

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Independent card-row locator measured against TWO owner-supplied screen
 * examples: a 3-card flop and a 4-card turn. Finds actual near-white card
 * rectangles in the central table region without assuming 540x1170 pixels.
 * Visual candidates are NOT verified hand/table/turn IDs or tap permission.
 */
object CenteredTableBoardReader {
    private fun nearWhite(p: Int): Boolean {
        val r = (p ushr 16) and 255
        val g = (p ushr 8) and 255
        val b = p and 255
        return min(r, min(g, b)) >= 210 && max(r, max(g, b)) - min(r, min(g, b)) < 48
    }

    fun locate(r: VisionRaster): List<VisionCardBox> {
        if (r.width < 320 || r.height < 700 || r.width.toDouble() / r.height !in 0.32..0.80)
            return emptyList()
        val x0 = (r.width * 0.08).toInt()
        val x1 = (r.width * 0.93).toInt()
        val y0 = (r.height * 0.35).toInt()
        val y1 = (r.height * 0.69).toInt()
        val w = x1 - x0
        val h = y1 - y0
        if (w <= 0 || h <= 0) return emptyList()
        // One bounded 4-connected pass. Transparent/occluded cards cannot
        // accidentally be reconstructed from their expected slot locations.
        val visited = BooleanArray(w * h)
        val queue = IntArray(w * h)
        val candidates = mutableListOf<VisionCardBox>()
        for (ly in 0 until h) for (lx in 0 until w) {
            val first = ly * w + lx
            if (visited[first]) continue
            visited[first] = true
            if (!nearWhite(r.rgb(lx + x0, ly + y0))) continue
            var head = 0
            var tail = 1
            queue[0] = first
            var minX = lx
            var maxX = lx
            var minY = ly
            var maxY = ly
            var area = 0
            while (head < tail) {
                val k = queue[head++]
                val px = k % w
                val py = k / w
                area++
                minX = min(minX, px)
                maxX = max(maxX, px)
                minY = min(minY, py)
                maxY = max(maxY, py)
                // Four-neighbor flood fill; no diagonal card-to-card merging.
                fun visit(nx: Int, ny: Int) {
                    if (nx !in 0 until w || ny !in 0 until h) return
                    val i = ny * w + nx
                    if (visited[i]) return
                    visited[i] = true
                    if (nearWhite(r.rgb(x0 + nx, y0 + ny))) queue[tail++] = i
                }
                visit(px - 1, py); visit(px + 1, py)
                visit(px, py - 1); visit(px, py + 1)
            }
            val bw = maxX - minX + 1
            val bh = maxY - minY + 1
            val ratio = bw.toDouble() / bh
            if (bw.toDouble() / r.width !in 0.075..0.165 ||
                bh.toDouble() / r.height !in 0.052..0.145 ||
                ratio !in 0.54..0.90 ||
                area.toDouble() / (bw * bh) < 0.55) continue
            val box = VisionCardBox(x0 + minX, y0 + minY,
                x0 + maxX + 1, y0 + maxY + 1)
            if (box.top.toDouble() / r.height in 0.38..0.63)
                candidates.add(box)
        }
        if (candidates.size !in 3..16) return emptyList()
        val rows = mutableListOf<List<VisionCardBox>>()
        for (start in candidates.sortedWith(compareBy({ it.top }, { it.left }))) {
            val same = candidates.filter { c ->
                abs(c.top - start.top) <= max(5, (r.height * 0.011).toInt()) &&
                    abs(c.height - start.height) <= max(5, (start.height * 0.17).toInt()) &&
                    abs(c.width - start.width) <= max(5, (start.width * 0.20).toInt())
            }.sortedBy { it.left }
            // Five visible slots are left-aligned around the middle of the
            // table. Short flops are NOT centered independently.
            if (same.size !in 3..5 || same.first().left.toDouble() / r.width !in 0.09..0.37)
                continue
            val stepOk = same.zipWithNext().all { (a, b) ->
                val dx = b.left - a.left
                dx >= a.width * 0.92 && dx <= a.width * 1.42 &&
                    b.left >= a.right - max(2, (a.width * 0.09).toInt())
            }
            if (stepOk) rows.add(same)
        }
        // Reject multiple plausible rows rather than guessing which is table.
        val distinct = rows.distinctBy { row ->
            row.joinToString("|") { it.left.toString() + ":" + it.top }
        }
        return distinct.singleOrNull() ?: emptyList()
    }

    fun read(r: VisionRaster): VisibleCommunityReading {
        val boxes = locate(r)
        if (boxes.size !in 3..5) {
            return VisibleCommunityReading(emptyList(), false,
                "CENTERED_BOARD_ROW_ABSENT_OR_AMBIGUOUS")
        }
        val cards = boxes.map { box ->
            val rank = CardVisionCore.classifyRank(r, box)
            val suit = CardVisionCore.classifySuit(r, box)
            if (rank == null || suit == null) null else
                rank.rank.toString() + suit.suit
        }
        if (cards.any { it == null }) return VisibleCommunityReading(cards, false,
            "CENTERED_BOARD_GLYPHS_UNREADABLE")
        val known = cards.filterNotNull()
        if (known.size != cards.size || known.toSet().size != cards.size) {
            return VisibleCommunityReading(cards, false, "CENTERED_BOARD_DUPLICATE")
        }
        return VisibleCommunityReading(cards, true, "CENTERED_BOARD_UNVERIFIED")
    }
}

/** Both engines remain independent. Conflicting known cards are rejected. */
object TableBoardCandidateSelector {
    fun choose(legacy: VisibleCommunityReading,
               centered: VisibleCommunityReading): VisibleCommunityReading {
        if (legacy.complete && centered.complete)
            return if (legacy.cards == centered.cards) centered
                else VisibleCommunityReading(emptyList(), false,
                    "CENTERED_LEGACY_CONFLICT")
        if (legacy.complete) return legacy
        if (!centered.complete) return if (centered.cards.size > legacy.cards.size)
            centered else legacy
        if (legacy.cards.isNotEmpty()) {
            if (legacy.cards.size != centered.cards.size ||
                legacy.cards.zip(centered.cards).any { (a, b) -> a != null && a != b })
                return VisibleCommunityReading(emptyList(), false,
                    "CENTERED_PARTIAL_CONFLICT")
        }
        return centered
    }
}

/**
 * A secondary bank-position anchor extracted from the OCR of the SAME
 * captured JPEG. It is a diagnostic cross-check, not a table ID. Amounts
 * containing a decimal point remain text and are never rounded into chips.
 */
data class CenterTableAnchorEvidence(
    val bankLabelAbove: Boolean,
    val chipAmountBelow: Boolean
)
object CenterTableAnchorText {
    private val bank = Regex("(?iu)^(?:банк|pot)\\s*[:：]?.*")
    private val amount = Regex("^[0-9]+[.,][0-9]{1,2}\\s*[$＄]?$")
    fun inspect(lines: List<PositionedOcrLine>): CenterTableAnchorEvidence {
        val valid = lines.take(100).filter { it.box.valid() && it.text.length <= 80 }
        val above = valid.any {
            it.box.centerX() in 280..740 && it.box.centerY() in 360..470 &&
                bank.matches(it.text.trim())
        }
        val below = valid.any {
            it.box.centerX() in 280..740 && it.box.centerY() in 540..690 &&
                amount.matches(it.text.trim())
        }
        return CenterTableAnchorEvidence(above, below)
    }
}
