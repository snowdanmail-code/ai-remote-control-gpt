package com.example.airemote

/**
 * Read-only classifier of the bottom poker action strip from the 540x1170
 * portrait reference layout. This is a VISUAL CANDIDATE, not evidence that
 * the user is allowed to act: the originating Android package, current hand,
 * labels on buttons and time remaining are not independently verified.
 *
 * It deliberately distinguishes muted pre-action selectors from the large
 * coloured buttons shown on the user's actual turn, and the separate
 * bet-sizing/confirmation mode. No clicks and no network calls.
 */
enum class TurnDisplayState { UNKNOWN, WAITING, PREACTION, READY, BET_SIZING }
enum class VisibleButtonRole { RED_FOLD, GREEN_CHECK_OR_CALL, GOLD_BET_OR_RAISE, GOLD_CONFIRM_SIZE }

data class TurnVisualReading(
    val state: TurnDisplayState,
    val roles: Set<VisibleButtonRole>,
    val reason: String,
    val redCoverage: Double,
    val greenCoverage: Double,
    val goldCoverage: Double
)

object TurnControlsVision {
    // Calibrated using user-supplied 1win recordings (1080x2340 => 540x1170).
    // A different orientation/layout is deliberately rejected.
    private const val WIDTH = 540
    private const val HEIGHT = 1170
    private const val Y_TOP = 1108
    private const val Y_BOTTOM = 1138

    private data class StripStats(val red: Double, val green: Double, val gold: Double,
                                  val darkButton: Double, val mean: Double)

    private fun strip(pixels: VisionRaster, x0: Int, x1: Int): StripStats {
        var redCount = 0
        var greenCount = 0
        var goldCount = 0
        var darkCount = 0
        var sum = 0L
        var count = 0
        // Bounded sampling: ~1,600 pixels per control, not the full screenshot.
        for (y in Y_TOP until Y_BOTTOM step 2) {
            for (x in x0 until x1 step 2) {
                val argb = pixels.rgb(x, y)
                val r = argb ushr 16 and 255
                val g = argb ushr 8 and 255
                val b = argb and 255
                val mean = (r + g + b) / 3
                sum += mean
                count++
                if (r > 60 && r > g * 1.18 && r > b * 1.35 && r - g > 14) redCount++
                if (g > 50 && g > r * 1.17 && g > b * 1.14 && g - r > 10) greenCount++
                if (r > 65 && g > 55 && r > g * 1.13 && g > b * 1.25) goldCount++
                if (mean in 36..62 && kotlin.math.abs(r-g) < 17 &&
                    kotlin.math.abs(g-b) < 17) darkCount++
            }
        }
        return StripStats(redCount.toDouble()/count, greenCount.toDouble()/count,
            goldCount.toDouble()/count, darkCount.toDouble()/count, sum.toDouble()/count)
    }

    fun read(pixels: VisionRaster): TurnVisualReading {
        fun unknown(reason: String) = TurnVisualReading(TurnDisplayState.UNKNOWN,
            emptySet(), reason, 0.0, 0.0, 0.0)
        if (pixels.width != WIDTH || pixels.height != HEIGHT) return unknown("Неподдерживаемая компоновка экрана")
        val left = strip(pixels, 10, 173)
        val middle = strip(pixels, 184, 352)
        val right = strip(pixels, 368, 530)
        val red = left.red > 0.40
        val green = middle.green > 0.35
        val gold = right.gold > 0.35
        val unwanted = middle.red > 0.25 || left.green > 0.25 ||
            left.gold > 0.35 || right.green > 0.35
        val (state, roles, reason) = when {
            unwanted -> Triple(TurnDisplayState.UNKNOWN, emptySet(), "Необычные цвета кнопок")
            red && green -> Triple(TurnDisplayState.READY,
                buildSet {
                    add(VisibleButtonRole.RED_FOLD)
                    add(VisibleButtonRole.GREEN_CHECK_OR_CALL)
                    if (gold) add(VisibleButtonRole.GOLD_BET_OR_RAISE)
                }, "Яркие игровые кнопки (только визуальный признак хода)")
            !red && !green && gold && left.darkButton > 0.35 -> Triple(
                TurnDisplayState.BET_SIZING, setOf(VisibleButtonRole.GOLD_CONFIRM_SIZE),
                "Выбор размера ставки, подтверждение отдельно")
            !red && !green && !gold && listOf(left, middle, right).all { it.darkButton > 0.45 } ->
                Triple(TurnDisplayState.PREACTION, emptySet(), "Тёмные кнопки предварительного выбора")
            !red && !green && !gold && listOf(left, middle, right).all { it.mean < 36.0 } ->
                Triple(TurnDisplayState.WAITING, emptySet(), "Игровая панель действий отсутствует")
            else -> Triple(TurnDisplayState.UNKNOWN, emptySet(), "Смешанная или неполная панель")
        }
        return TurnVisualReading(state, roles, reason, left.red, middle.green, right.gold)
    }
}
