package com.example.airemote

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pure on-device pixel and card-layout recognizer for the 540x1170 portrait
 * reference layout observed in user-provided 1win recordings.
 *
 * Unlike OCR text elsewhere on the screen, only cards inside two designated
 * table ROIs can be considered. Other screen sizes / different table layouts
 * are rejected, not guessed. This class does not authorize a game action and
 * does not assert the foreground Android package.
 */
data class VisionRaster(val width: Int, val height: Int, val argb: IntArray) {
    init { require(width > 0 && height > 0 && argb.size == width * height) }
    fun rgb(x: Int, y: Int): Int = argb[y * width + x]
}

data class VisionCardBox(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

data class SuitCandidate(val suit: Char, val confidence: Double)
data class RankCandidate(val rank: Char, val similarity: Double)

data class CardReading(
    val hole: List<String?>,
    val community: List<String?>,
    val complete: Boolean,
    /** Template similarity only, not a calibrated probability. */
    val minimumSimilarity: Double,
    val reason: String,
    /** Display only: independently seen rank and suit; NEVER authorizes action. */
    val holeGlyphs: List<String> = emptyList(),
    val communityGlyphs: List<String> = emptyList(),
    val holeMinimumSimilarity: Double = 0.0,
    val communityMinimumSimilarity: Double = 0.0
)

data class VisionCardZones(
    val hole: List<VisionCardBox>,
    val community: List<VisionCardBox>,
    val reason: String,
    val usable: Boolean
)

object CardVisionCore {
    private const val REF_WIDTH = 540
    private const val REF_HEIGHT = 1170
    private const val MASK_SIZE = 24

    // Each source silhouette was extracted from a user-supplied 1win video,
    // and stored as a 24x24 binary bitmap (not a general card-font model).
    private data class SuitTemplate(val suit: Char, val mask: IntArray)
    private val templates = listOf(
        // Populated from fixtures using exact run-length bitmaps; see generator.
        SuitTemplate('s', intArrayOf(
            0x000800,
            0x001c00,
            0x003e00,
            0x00ff00,
            0x00ff80,
            0x03ffe0,
            0x07fff0,
            0x0ffff0,
            0x1ffffc,
            0x3ffffe,
            0x7fffff,
            0xffffff,
            0xffffff,
            0xffffff,
            0xffffff,
            0xffffff,
            0xffffff,
            0x3ffffe,
            0x1ffffc,
            0x0f9cf8,
            0x003e00,
            0x003e00,
            0x00ff00,
            0x00ff80
        )),
        SuitTemplate('s', intArrayOf(
            0x000800,
            0x001800,
            0x003c00,
            0x00ff00,
            0x00ff00,
            0x03ffe0,
            0x07fff0,
            0x0ffff0,
            0x1ffffc,
            0x3ffffe,
            0x7fffff,
            0xffffff,
            0xffffff,
            0xffffff,
            0xffffff,
            0xffffff,
            0xffffff,
            0x3ffffe,
            0x3ffffc,
            0x0f9cf8,
            0x003e00,
            0x003e00,
            0x00ff00,
            0x00ff80
        )),
        SuitTemplate('h', intArrayOf(
            0x0f00f0,
            0x1fc3fc,
            0x3fe7fc,
            0x7ffffe,
            0xffffff,
            0xffffff,
            0xffffff,
            0xffffff,
            0xffffff,
            0xffffff,
            0xffffff,
            0xffffff,
            0x7ffffe,
            0x3ffffc,
            0x1ffffc,
            0x1ffff8,
            0x0ffff0,
            0x07fff0,
            0x03ffe0,
            0x01ff80,
            0x00ff80,
            0x007f00,
            0x003e00,
            0x001c00
        )),
        SuitTemplate('h', intArrayOf(
            0x0fc1f8,
            0x1fe3fc,
            0x3ffffe,
            0x3fffff,
            0x7fffff,
            0x7fffff,
            0x7fffff,
            0xffffff,
            0x7fffff,
            0x7fffff,
            0x7fffff,
            0x7fffff,
            0x3ffffe,
            0x1ffffe,
            0x1ffffc,
            0x0ffffc,
            0x07fff8,
            0x07fff0,
            0x01ffe0,
            0x01ffc0,
            0x00ff80,
            0x003f00,
            0x003e00,
            0x003e00
        )),
        SuitTemplate('h', intArrayOf(
            0x0fc0fe,
            0x1fe1ff,
            0x3fffff,
            0x7fffff,
            0x7fffff,
            0xffffff,
            0xffffff,
            0xffffff,
            0xffffff,
            0xffffff,
            0xffffff,
            0x7fffff,
            0x3fffff,
            0x3fffff,
            0x1fffff,
            0x0ffffe,
            0x0ffffc,
            0x07fff8,
            0x01fff0,
            0x00ffe0,
            0x00ffc0,
            0x007f80,
            0x003f00,
            0x001f00
        )),
        SuitTemplate('c', intArrayOf(
            0x001f00,
            0x003f00,
            0x007f80,
            0x00ff80,
            0x00ff80,
            0x00ff80,
            0x007f80,
            0x007f00,
            0x001f00,
            0x1f1e3f,
            0x3f1e7f,
            0x7fdeff,
            0xffffff,
            0xffffff,
            0xffffff,
            0x7f9eff,
            0x1f1e1e,
            0x001e00,
            0x001e00,
            0x001e00,
            0x003f00,
            0x003f00,
            0x007f00,
            0x00ffc0
        )),
        SuitTemplate('d', intArrayOf(
            0x000c00,
            0x001e00,
            0x003f00,
            0x007f00,
            0x01ffc0,
            0x03ffe0,
            0x07fff0,
            0x07fff8,
            0x1ffffe,
            0x3ffffe,
            0x7fffff,
            0x7fffff,
            0xffffff,
            0x7fffff,
            0x3fffff,
            0x3ffffe,
            0x0ffffc,
            0x07fff8,
            0x03fff0,
            0x03ffe0,
            0x00ff80,
            0x007f00,
            0x003e00,
            0x001e00
        ))
    )

    private data class RankTemplate(val rank: Char, val mask: IntArray)
    private val ranks = listOf(
        RankTemplate('7', intArrayOf(
            0x000000,
            0x03ffc0,
            0x03ffc0,
            0x0303c0,
            0x030380,
            0x030700,
            0x000e00,
            0x000e00,
            0x000c00,
            0x000c00,
            0x001c00,
            0x001800,
            0x001800,
            0x003000,
            0x003000,
            0x003000,
            0x003000,
            0x007000,
            0x000000,
            0x000000,
            0x000800,
            0x001c00,
            0x003e00,
            0x000000
        )),
        RankTemplate('A', intArrayOf(
            0x000000,
            0x003000,
            0x003800,
            0x003800,
            0x007800,
            0x007800,
            0x006c00,
            0x00ec00,
            0x00ec00,
            0x00ec00,
            0x00ce00,
            0x00ce00,
            0x01fe00,
            0x01fe00,
            0x01c600,
            0x018700,
            0x03cf80,
            0x03cf80,
            0x000000,
            0x000000,
            0x001000,
            0x003800,
            0x003c00,
            0x000000
        )),
        RankTemplate('J', intArrayOf(
            0x000000,
            0x001fc0,
            0x001fc0,
            0x000700,
            0x000700,
            0x000700,
            0x000700,
            0x000700,
            0x000700,
            0x000700,
            0x000700,
            0x000700,
            0x070700,
            0x070700,
            0x078700,
            0x03cf00,
            0x03fe00,
            0x003000,
            0x000000,
            0x000000,
            0x001800,
            0x001c00,
            0x003e00,
            0x000000
        )),
        RankTemplate('Q', intArrayOf(
            0x000000,
            0x007c00,
            0x00ff00,
            0x01c700,
            0x01c300,
            0x018380,
            0x018380,
            0x018380,
            0x018380,
            0x018380,
            0x03c380,
            0x07f380,
            0x03f380,
            0x019f80,
            0x018f80,
            0x01cf80,
            0x00ffc0,
            0x003800,
            0x000000,
            0x000000,
            0x000000,
            0x000000,
            0x03f7e0,
            0x000000
        )),
        RankTemplate('5', intArrayOf(
            0x000000,
            0x01ff00,
            0x01ff00,
            0x01c000,
            0x018000,
            0x01fc00,
            0x01ef80,
            0x018380,
            0x0001c0,
            0x0001c0,
            0x0001c0,
            0x0001c0,
            0x0001c0,
            0x0101c0,
            0x0383c0,
            0x03c780,
            0x01ff00,
            0x003800,
            0x000000,
            0x000000,
            0x000800,
            0x001c00,
            0x003e00,
            0x000000
        )),
        RankTemplate('4', intArrayOf(
            0x000000,
            0x000e00,
            0x001e00,
            0x001e00,
            0x001e00,
            0x003e00,
            0x006e00,
            0x00ee00,
            0x01ee00,
            0x01ce00,
            0x038e00,
            0x078e00,
            0x07ffc0,
            0x07ffc0,
            0x000e00,
            0x000e00,
            0x001f00,
            0x001f00,
            0x000000,
            0x000000,
            0x000000,
            0x000000,
            0x03e7c0,
            0x000000
        )),
        RankTemplate('9', intArrayOf(
            0x000000,
            0x007e00,
            0x00ff00,
            0x01e700,
            0x01c380,
            0x018180,
            0x018180,
            0x018180,
            0x018380,
            0x01c780,
            0x01ff80,
            0x00ff80,
            0x003f80,
            0x000380,
            0x008300,
            0x01c700,
            0x00fe00,
            0x003800,
            0x000000,
            0x000000,
            0x000c00,
            0x001e00,
            0x001f00,
            0x000000
        )),
        RankTemplate('K', intArrayOf(
            0x000000,
            0x03efc0,
            0x03efc0,
            0x018f00,
            0x018e00,
            0x018c00,
            0x019800,
            0x01f000,
            0x01f000,
            0x01f800,
            0x01f800,
            0x01cc00,
            0x01cc00,
            0x01ce00,
            0x01ce00,
            0x01c700,
            0x03ef80,
            0x03efc0,
            0x000000,
            0x000000,
            0x000000,
            0x000000,
            0x03e7c0,
            0x000000
        )),
        RankTemplate('8', intArrayOf(
            0x000000,
            0x007e00,
            0x00ff00,
            0x01e780,
            0x01c1c0,
            0x0381c0,
            0x01c380,
            0x01ff80,
            0x00ff00,
            0x01ff80,
            0x03c3c0,
            0x0381c0,
            0x0300c0,
            0x0300c0,
            0x0381c0,
            0x03c3c0,
            0x01ff80,
            0x001800,
            0x000000,
            0x000000,
            0x000800,
            0x001c00,
            0x003e00,
            0x000000
        )),
        RankTemplate('T', intArrayOf(
            0x000000,
            0x061f00,
            0x063f80,
            0x0661c0,
            0x0660c0,
            0x0660c0,
            0x0660c0,
            0x0660c0,
            0x0660c0,
            0x0660c0,
            0x0660c0,
            0x0660c0,
            0x0660c0,
            0x0660c0,
            0x0661c0,
            0x0661c0,
            0x067fc0,
            0x061e00,
            0x000000,
            0x000000,
            0x002000,
            0x003000,
            0x007800,
            0x000000
        )),
        RankTemplate('6', intArrayOf(
            0x000000,
            0x007e00,
            0x00ff00,
            0x01c380,
            0x018100,
            0x038000,
            0x03ff00,
            0x03c380,
            0x0381c0,
            0x0381c0,
            0x0300c0,
            0x0300c0,
            0x0380c0,
            0x0380c0,
            0x0381c0,
            0x01c380,
            0x00ff80,
            0x001800,
            0x000000,
            0x000000,
            0x000800,
            0x001c00,
            0x003e00,
            0x000000
        )),
        RankTemplate('2', intArrayOf(
            0x000000,
            0x00fc00,
            0x03fe00,
            0x038700,
            0x030300,
            0x030300,
            0x000700,
            0x000e00,
            0x001c00,
            0x003800,
            0x007000,
            0x00e000,
            0x01c000,
            0x038000,
            0x038380,
            0x030380,
            0x03ff80,
            0x03ff00,
            0x000000,
            0x000000,
            0x001800,
            0x001c00,
            0x003e00,
            0x000000
        )),
        RankTemplate('3', intArrayOf(
            0x000000,
            0x07ffc0,
            0x07ff80,
            0x070f00,
            0x071e00,
            0x021c00,
            0x007e00,
            0x00ff00,
            0x004780,
            0x000380,
            0x0001c0,
            0x0001c0,
            0x0001c0,
            0x0301c0,
            0x070380,
            0x038780,
            0x01ff00,
            0x003000,
            0x000000,
            0x000000,
            0x001800,
            0x003c00,
            0x007e00,
            0x000000
        ))
        ,        RankTemplate('T', intArrayOf(
            0x000000,0x060600,0x0e3f80,0x0e39c0,0x0e70e0,0x0670e0,
            0x0670e0,0x0670e0,0x0670e0,0x0670e0,0x0660e0,0x0660e0,
            0x0670e0,0x0670e0,0x0670e0,0x067bc0,0x063fc0,0x061f00,
            0x000000,0x000000,0x000000,0x038380,0x07efc0,0x000000
        ))
,
        RankTemplate('2', intArrayOf(
            0x000000,
            0x006000,
            0x01f800,
            0x078e00,
            0x060700,
            0x060700,
            0x060700,
            0x000e00,
            0x001c00,
            0x003800,
            0x00f000,
            0x01c000,
            0x01c000,
            0x038000,
            0x070700,
            0x060700,
            0x07ff00,
            0x07ff00,
            0x000000,
            0x000000,
            0x000000,
            0x018700,
            0x03c7c0,
            0x000000
        )),
        RankTemplate('5', intArrayOf(
            0x000000,
            0x01ff00,
            0x03ff00,
            0x030000,
            0x030000,
            0x03f800,
            0x03ff00,
            0x038700,
            0x000380,
            0x000180,
            0x0001c0,
            0x0001c0,
            0x0001c0,
            0x000180,
            0x070380,
            0x038f00,
            0x01ff00,
            0x007800,
            0x000000,
            0x000000,
            0x001000,
            0x003c00,
            0x007e00,
            0x000000
        )),
        RankTemplate('Q', intArrayOf(
            0x000000,
            0x003000,
            0x00fc00,
            0x018700,
            0x010300,
            0x030300,
            0x030300,
            0x030300,
            0x030300,
            0x030300,
            0x038300,
            0x07e300,
            0x07f300,
            0x037b00,
            0x039f00,
            0x01cf00,
            0x01ffc0,
            0x007800,
            0x000000,
            0x000000,
            0x001c00,
            0x007e00,
            0x007f00,
            0x000000
        )),
        RankTemplate('6', intArrayOf(
            0x000000,
            0x003000,
            0x01fc00,
            0x03c700,
            0x038700,
            0x070000,
            0x07fc00,
            0x07fe00,
            0x078700,
            0x070700,
            0x060380,
            0x060180,
            0x060180,
            0x060380,
            0x030700,
            0x038700,
            0x01fe00,
            0x003800,
            0x000000,
            0x000000,
            0x000000,
            0x018700,
            0x07c7c0,
            0x000000
        )),
        RankTemplate('3', intArrayOf(
            0x000000,
            0x07ff80,
            0x07ff80,
            0x060f00,
            0x061e00,
            0x061c00,
            0x00fc00,
            0x01ff00,
            0x01ff00,
            0x000380,
            0x000180,
            0x0000c0,
            0x0000c0,
            0x020180,
            0x070380,
            0x03cf00,
            0x01ff00,
            0x007800,
            0x000000,
            0x000000,
            0x001c00,
            0x007e00,
            0x007e00,
            0x000000
        ))
    )

    private fun white(p: Int): Boolean {
        val r = (p ushr 16) and 255
        val g = (p ushr 8) and 255
        val b = p and 255
        return min(r, min(g, b)) >= 210 && max(r, max(g, b)) - min(r, min(g, b)) < 48
    }

    private fun ink(p: Int): Boolean {
        val r = (p ushr 16) and 255
        val g = (p ushr 8) and 255
        val b = p and 255
        val dark = r < 145 && g < 145 && b < 145
        val red = r > 95 && r > g * 1.5 && r > b * 1.45
        return dark || red
    }

    private fun red(p: Int): Boolean {
        val r = (p ushr 16) and 255
        val g = (p ushr 8) and 255
        val b = p and 255
        return r > 100 && r > g * 1.5 && r > b * 1.45
    }

    private fun scaleX(x: Int, r: VisionRaster): Int = x * r.width / REF_WIDTH
    private fun scaleY(y: Int, r: VisionRaster): Int = y * r.height / REF_HEIGHT

    private fun contiguous(values: List<Int>, maxGap: Int): List<IntRange> {
        if (values.isEmpty()) return emptyList()
        val groups = mutableListOf<IntRange>()
        var start = values.first()
        var last = start
        for (v in values.drop(1)) {
            if (v - last > maxGap) { groups.add(start..last); start = v }
            last = v
        }
        groups.add(start..last)
        return groups
    }

    private fun cardRow(r: VisionRaster, ref: IntArray, board: Boolean): List<VisionCardBox>? {
        val x0 = scaleX(ref[0], r)
        val x1 = scaleX(ref[1], r)
        val y0 = scaleY(ref[2], r)
        val y1 = scaleY(ref[3], r)
        if (x1 <= x0 || y1 <= y0) return null
        val threshold = scaleX(47, r).coerceAtLeast(20)
        val selectedRows = (y0 until y1).filter { y ->
            var n = 0
            for (x in x0 until x1) if (white(r.rgb(x, y))) n++
            n >= threshold
        }
        val intervals = contiguous(selectedRows, max(1, scaleY(2, r)))
            .filter { it.last - it.first + 1 >= scaleY(66, r) }
            .sortedByDescending { it.last - it.first }
        val interval = intervals.firstOrNull() ?: return if (board) emptyList() else null
        val cardHeight = interval.last - interval.first + 1
        val selectedColumns = (x0 until x1).filter { x ->
            var n = 0
            for (y in interval.first..interval.last) if (white(r.rgb(x, y))) n++
            n >= cardHeight * 55 / 100
        }
        val spanGroups = contiguous(selectedColumns, max(1, scaleX(if (board) 3 else 10, r)))
        val cardGroups = if (board) {
            spanGroups.filter { (it.last - it.first + 1) in scaleX(49, r)..scaleX(91, r) }
        } else spanGroups.filter { (it.last - it.first + 1) in scaleX(96, r)..scaleX(139, r) }
        if (board) {
            if (cardGroups.size !in 3..5) return emptyList()
            val xStarts = cardGroups.map { it.first }
            if (xStarts.zipWithNext().any { (a, b) -> (b - a) !in scaleX(48, r)..scaleX(93, r) }) return emptyList()
            return cardGroups.map { VisionCardBox(it.first, interval.first, it.last + 1, interval.last + 1) }
        }
        if (cardGroups.size != 1) return null
        val g = cardGroups.single()
        val mid = (g.first + g.last + 1) / 2
        return listOf(
            VisionCardBox(g.first, interval.first, mid, interval.last + 1),
            VisionCardBox(mid, interval.first, g.last + 1, interval.last + 1)
        )
    }

    fun detect(r: VisionRaster): VisionCardZones {
        val ratio = r.width.toDouble() / r.height
        if (r.height < 780 || ratio !in 0.45..0.48) {
            return VisionCardZones(emptyList(), emptyList(), "Неподдерживаемая ориентация/геометрия экрана", false)
        }
        val hole = cardRow(r, intArrayOf(240, 420, 852, 998), false)
            ?: return VisionCardZones(emptyList(), emptyList(), "Не найдены 2 открытые карты игрока", false)
        val board = cardRow(r, intArrayOf(65, 477, 502, 664), true) ?: emptyList()
        val all = hole + board
        if (all.any { it.width < 20 || it.height < 35 }) {
            return VisionCardZones(emptyList(), emptyList(), "Неполная область карты", false)
        }
        return VisionCardZones(hole, board, "Области найдены; номиналы ещё не проверены", true)
    }

    /** Differentiate silhouettes, not just black/red color. Low or ambiguous scores return null. */
    fun classifySuit(r: VisionRaster, card: VisionCardBox): SuitCandidate? {
        val x0 = card.left + card.width * 38 / 100
        val x1 = card.left + card.width * 98 / 100
        val y0 = card.top + card.height * 53 / 100
        val y1 = card.top + card.height * 99 / 100
        val w = x1 - x0; val h = y1 - y0
        if (w < 15 || h < 15 || x1 > r.width || y1 > r.height) return null
        val bitmap = BooleanArray(w * h) { i -> ink(r.rgb(x0 + i % w, y0 + i / w)) }
        // 8-connected component flood-fill; choose largest recognizable central suit.
        val visited = BooleanArray(bitmap.size)
        var largest = emptyList<Int>()
        for (i in bitmap.indices) {
            if (!bitmap[i] || visited[i]) continue
            val queue = java.util.ArrayDeque<Int>(); val pixels = ArrayList<Int>()
            queue.addLast(i); visited[i] = true
            while (queue.isNotEmpty()) {
                val cur = queue.removeFirst(); pixels.add(cur)
                val cy = cur / w; val cx = cur % w
                for (dy in -1..1) for (dx in -1..1) {
                    if (dx == 0 && dy == 0) continue
                    val nx = cx + dx; val ny = cy + dy
                    if (nx !in 0 until w || ny !in 0 until h) continue
                    val ni = ny * w + nx
                    if (bitmap[ni] && !visited[ni]) { visited[ni] = true; queue.addLast(ni) }
                }
            }
            if (pixels.size > largest.size) largest = pixels
        }
        if (largest.size < max(28, w * h / 12)) return null
        val minX = largest.minOf { it % w }; val maxX = largest.maxOf { it % w }
        val minY = largest.minOf { it / w }; val maxY = largest.maxOf { it / w }
        if (maxX - minX < 5 || maxY - minY < 7) return null
        val suitMask = BooleanArray(w * h)
        for (i in largest) suitMask[i] = true
        var redPixels = 0
        for (i in largest) if (red(r.rgb(x0 + i % w, y0 + i / w))) redPixels++
        val inkIsRed = redPixels > largest.size * 45 / 100
        val normalized = IntArray(MASK_SIZE)
        for (yy in 0 until MASK_SIZE) for (xx in 0 until MASK_SIZE) {
            val px = minX + xx * (maxX - minX + 1) / MASK_SIZE
            val py = minY + yy * (maxY - minY + 1) / MASK_SIZE
            if (suitMask[py * w + px]) normalized[yy] = normalized[yy] or (1 shl (23 - xx))
        }
        val results = templates.filter { (it.suit == 'h' || it.suit == 'd') == inkIsRed }
            .map { template ->
                var changed = 0
                for (row in 0 until MASK_SIZE)
                    changed += Integer.bitCount(normalized[row] xor template.mask[row])
                template.suit to 1.0 - changed.toDouble() / (MASK_SIZE * MASK_SIZE)
            }
        if (results.isEmpty()) return null
        val bySuit = results.groupBy({ it.first }, { it.second }).mapValues { it.value.maxOrNull()!! }
        val ranked = bySuit.entries.sortedByDescending { it.value }
        val winner = ranked.firstOrNull() ?: return null
        val second = ranked.drop(1).firstOrNull()?.value ?: 0.0
        val legacy = if (winner.value >= 0.78 && winner.value - second >= 0.055)
            SuitCandidate(winner.key, winner.value) else null
        // Independent small corner glyph templates. If the large suit and
        // the corner disagree, refuse to publish a falsely confident card.
        val small = VerifiedCardCornerSuitSamples.classify(r, card)
        return when {
            legacy != null && small != null && legacy.suit != small.suit -> null
            legacy != null -> legacy
            else -> small
        }
    }

    /**
     * Deterministic recognition of the upper-left rank glyph, without OCR, network,
     * or repeated model requests. Confidence is template similarity, NOT a
     * statistically calibrated probability. Unknown glyphs must be rejected.
     */
    internal fun normalizedRankMask(r: VisionRaster, card: VisionCardBox): IntArray? {
        val x0 = card.left + max(2, card.width * 3 / 100)
        val x1 = card.left + card.width * 55 / 100
        val y0 = card.top + max(2, card.height * 2 / 100)
        val y1 = card.top + card.height * 34 / 100
        val w = x1 - x0; val h = y1 - y0
        if (w < 9 || h < 12 || x1 > r.width || y1 > r.height) return null
        var minX = w; var maxX = -1; var minY = h; var maxY = -1
        val glyph = BooleanArray(w * h)
        for (i in glyph.indices) {
            val x = i % w; val y = i / w
            val active = ink(r.rgb(x0 + x, y0 + y))
            glyph[i] = active
            if (active) {
                minX = min(minX, x); maxX = max(maxX, x)
                minY = min(minY, y); maxY = max(maxY, y)
            }
        }
        if (maxX - minX < 4 || maxY - minY < 8) return null
        val tw = maxX - minX + 1; val th = maxY - minY + 1
        val scale = min(22.0 / tw, 22.0 / th)
        val nw = (tw * scale).roundToInt().coerceIn(1, 22)
        val nh = (th * scale).roundToInt().coerceIn(1, 22)
        val ox = (MASK_SIZE - nw) / 2; val oy = (MASK_SIZE - nh) / 2
        val normalized = IntArray(MASK_SIZE)
        for (yy in 0 until nh) for (xx in 0 until nw) {
            val px = minX + (((xx + 0.5) * tw / nw) - 0.5).roundToInt().coerceIn(0, tw - 1)
            val py = minY + (((yy + 0.5) * th / nh) - 0.5).roundToInt().coerceIn(0, th - 1)
            if (glyph[py * w + px]) normalized[yy + oy] =
                normalized[yy + oy] or (1 shl (23 - xx - ox))
        }
        return normalized
    }

    internal fun rankScores(r: VisionRaster, card: VisionCardBox): List<Pair<Char, Double>> {
        val normalized = normalizedRankMask(r, card) ?: return emptyList()
        return ranks.map { template ->
            var bestScore = 0.0
            for (dy in -2..2) for (dx in -2..2) {
                var differing = 0
                for (row in 0 until MASK_SIZE) {
                    val sourceRow = row - dy
                    val bits = if (sourceRow !in 0 until MASK_SIZE) 0 else {
                        val value = normalized[sourceRow]
                        if (dx < 0) value shl -dx else value ushr dx
                    } and 0xFFFFFF
                    differing += Integer.bitCount(template.mask[row] xor bits)
                }
                val score = 1.0 - differing.toDouble() / (MASK_SIZE * MASK_SIZE)
                if (score > bestScore) bestScore = score
            }
            template.rank to bestScore
        }.groupBy({ it.first }, { it.second })
            .map { (rank, scores) -> rank to (scores.maxOrNull() ?: 0.0) }
            .sortedByDescending { it.second }
    }

    fun classifyRank(r: VisionRaster, card: VisionCardBox): RankCandidate? {
        val matches = rankScores(r, card)
        val first = matches.firstOrNull()
        val second = matches.getOrNull(1)?.second ?: 0.0
        val legacy = if (first != null && first.second >= 0.93 &&
            first.second - second >= 0.045)
            RankCandidate(first.first, first.second) else null
        // The second reader has a corrected glyph-only crop, and is trained
        // ONLY from individually checked screenshots. A contradictory verdict
        // is explicitly unknown rather than arbitrarily preferring a model.
        val verified = VerifiedCardRankSamples.classify(r, card)
        return when {
            legacy != null && verified != null && legacy.rank != verified.rank -> null
            legacy != null -> legacy
            else -> verified
        }
    }

    /**
     * Spectator mode: ONLY the visible community-card row. No hole-card requirement.
     * Before the flop, absence of cards is normal and is not an OCR failure.
     * This return value cannot feed the authoritative hero-card/gesture pipeline.
     */
    fun readVisibleCommunity(r: VisionRaster): VisibleCommunityReading {
        val ratio = r.width.toDouble() / r.height
        if (r.height < 780 || ratio !in 0.45..0.48) {
            return VisibleCommunityReading(emptyList(), false,
                "Неподдерживаемая геометрия кадра наблюдателя")
        }
        val boxes = cardRow(r, intArrayOf(65, 477, 502, 664), true)
            ?: emptyList()
        if (boxes.size !in 3..5) {
            return VisibleCommunityReading(emptyList(), false,
                "Общие карты ещё не появились (до флопа) или область стола не обнаружена")
        }
        if (boxes.any { it.width < 20 || it.height < 35 }) {
            return VisibleCommunityReading(emptyList(), false,
                "Область общих карт обрезана")
        }
        val observed = boxes.map { CardGlyphChain.read(r, it) }
        val values = observed.map { it.code }
        val previews = observed.map { it.preview }
        val boardMin = if (observed.all { it.code != null })
            observed.minOf { it.quality } else 0.0
        if (values.any { it == null }) {
            return VisibleCommunityReading(values, false,
                "Общие карты видны, но часть номиналов или мастей не подтверждена", previews, boardMin)
        }
        val complete = values.filterNotNull()
        if (complete.toSet().size != complete.size) {
            return VisibleCommunityReading(values, false,
                "Обнаружены повторяющиеся карты: отбрасываем результат", previews, 0.0)
        }
        return VisibleCommunityReading(values, true,
            "Общие карты распознаны локально; раздача и приложение не подтверждены", previews, boardMin)
    }

    fun readCards(r: VisionRaster): CardReading {
        val zones = detect(r)
        if (!zones.usable) return CardReading(emptyList(), emptyList(), false, 0.0, zones.reason)
        val heroGlyphs = zones.hole.map { CardGlyphChain.read(r, it) }
        val boardGlyphs = zones.community.map { CardGlyphChain.read(r, it) }
        val hole = heroGlyphs.map { it.code }
        val community = boardGlyphs.map { it.code }
        val minimum = (heroGlyphs + boardGlyphs).minOfOrNull { it.quality } ?: 0.0
        val heroDisplay = heroGlyphs.map { it.preview }
        val boardDisplay = boardGlyphs.map { it.preview }
        val heroMin = if (heroGlyphs.size == 2 && heroGlyphs.all { it.code != null })
            heroGlyphs.minOf { it.quality } else 0.0
        val boardMin = if (boardGlyphs.all { it.code != null })
            boardGlyphs.minOfOrNull { it.quality } ?: 1.0 else 0.0
        if ((hole + community).any { it == null }) {
            return CardReading(hole, community, false, 0.0,
                "Неизвестный номинал/масть: запрещено угадывать", heroDisplay, boardDisplay,
                heroMin, boardMin)
        }
        val verifiedHole = hole.filterNotNull()
        val verifiedBoard = community.filterNotNull()
        if (!checkUnique(verifiedHole, verifiedBoard)) {
            return CardReading(hole, community, false, 0.0,
                "Повтор карты или недопустимое число общих карт", heroDisplay, boardDisplay,
                heroMin, 0.0)
        }
        return CardReading(hole, community, true, minimum,
            "Шаблоны совпали; имя приложения и фаза раздачи отдельно не подтверждены",
            heroDisplay, boardDisplay, heroMin, boardMin)
    }

    /** OCR returns a rank string for one cropped card, never a full-hand string. */
    fun parseRank(text: String): String? {
        val candidate = text.trim().uppercase().replace(" ", "")
            .replace("O", "0") // OCR often reads the zero in 10 as O.
        return when (candidate) {
            "10", "T" -> "T"
            "A", "K", "Q", "J", "2", "3", "4", "5", "6", "7", "8", "9" -> candidate
            else -> null
        }
    }

    fun checkUnique(hole: List<String>, board: List<String>): Boolean {
        val all = hole + board
        return hole.size == 2 && board.size in listOf(0, 3, 4, 5) &&
            all.all { Regex("^[2-9TJQKA][cdhs]$").matches(it) } && all.toSet().size == all.size
    }
}
