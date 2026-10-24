package com.example.airemote

/**
 * A strictly VISUAL-ONLY cross-check for the commonly confused 6, 8 and 9.
 *
 * We do not loosen any rank-template or suit threshold. Four-connected
 * background regions entirely enclosed by black/red ink are inspected:
 * - 8: two substantial separate enclosed regions;
 * - 6: one substantial enclosed region in the LOWER half;
 * - 9: one substantial enclosed region in the UPPER half.
 *
 * This is a conservative topology hint, NOT a verified poker card; the
 * strategy and two-frame card-consensus must never accept it as authority.
 */
object RoundDigitTopology {
    data class Hole(val size: Int, val centerY: Double)

    internal fun holes(mask: IntArray): List<Hole> {
        if (mask.size != 24 || mask.any { it and 0xFFFFFF != it }) return emptyList()
        val visited = BooleanArray(24 * 24)
        val queue = IntArray(24 * 24)
        val found = mutableListOf<Hole>()
        for (start in 0 until 24 * 24) {
            if (visited[start]) continue
            val sx = start % 24
            val sy = start / 24
            if ((mask[sy] and (1 shl (23 - sx))) != 0) continue
            var front = 0
            var end = 1
            queue[0] = start
            visited[start] = true
            var touchesBorder = false
            var pixelCount = 0
            var sumY = 0L
            while (front < end) {
                val at = queue[front++]
                val x = at % 24
                val y = at / 24
                pixelCount++
                sumY += y
                if (x == 0 || x == 23 || y == 0 || y == 23) touchesBorder = true
                fun add(nx: Int, ny: Int) {
                    if (nx !in 0..23 || ny !in 0..23) return
                    val index = ny * 24 + nx
                    if (visited[index] || (mask[ny] and (1 shl (23 - nx))) != 0) return
                    visited[index] = true
                    queue[end++] = index
                }
                add(x - 1, y)
                add(x + 1, y)
                add(x, y - 1)
                add(x, y + 1)
            }
            if (!touchesBorder && pixelCount > 0)
                found.add(Hole(pixelCount, sumY.toDouble() / pixelCount))
        }
        return found.sortedBy { it.centerY }
    }

    /** Null is an intentional "unknown", never a request to guess. */
    internal fun classifyMask(mask: IntArray): Char? {
        if (mask.size != 24 || mask.all { it == 0 } ||
            mask.all { it == 0xFFFFFF }) return null
        val inner = holes(mask)
        return when {
            inner.size == 2 &&
                inner.all { it.size in 20..110 } &&
                inner[0].centerY in 3.0..10.0 &&
                inner[1].centerY in 12.0..20.0 &&
                inner[1].centerY - inner[0].centerY >= 5.0 -> '8'
            inner.size == 1 && inner[0].size in 28..120 &&
                inner[0].centerY in 12.0..18.5 -> '6'
            inner.size == 1 && inner[0].size in 28..120 &&
                inner[0].centerY in 4.0..10.5 -> '9'
            else -> null
        }
    }

    /**
     * Read three slightly different crops of the SAME already located card.
     * At least two must agree; any different accepted digit invalidates it.
     * The offset is bounded to 2 normalized pixels; no neighboring card or
     * other screen area may be used to manufacture the missing rank.
     */
    fun hint(r: VisionRaster, card: VisionCardBox): Char? {
        if (card.left < 3 || card.right + 3 > r.width ||
            card.top < 0 || card.bottom > r.height ||
            card.width < 38 || card.height < 51) return null
        val variants = listOf(-2, 0, 2).mapNotNull { dx ->
            val shifted = VisionCardBox(card.left + dx, card.top,
                card.right + dx, card.bottom)
            val mask = VerifiedCardRankSamples.rankMask(r, shifted)
                ?: return@mapNotNull null
            classifyMask(mask)
        }
        return variants.groupingBy { it }.eachCount().entries
            .singleOrNull { it.value >= 2 && variants.all { v -> v == it.key } }
            ?.key
    }
}
