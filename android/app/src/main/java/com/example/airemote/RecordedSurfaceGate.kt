package com.example.airemote

import kotlin.math.abs

/**
 * Read-only, conservative screen-surface check derived from previously supplied
 * ORIGINAL 1080x2340 recordings. This is a visual *blocker*, never proof of a
 * game/table/hand, permission to tap, or a substitute for window provenance.
 *
 * The sampled locations are away from names/cards in the recorded 1win layout.
 * A new layout, theme, recording scale or atypical animation may be UNKNOWN and
 * must be treated as blocked, not silently promoted to TABLE_LIKE.
 */
enum class RecordedSurfaceStatus { TABLE_LIKE, PANEL_VISIBLE, UNKNOWN }

data class RecordedSurfaceReading(
    val status: RecordedSurfaceStatus,
    val panelVotes: Int,
    val neutralVotes: Int
) {
    val authorizesGameAction: Boolean get() = false
}

object RecordedSurfaceGate {
    private val samplePoints = listOf(
        680 to 380, 750 to 500, 650 to 570,
        750 to 720, 840 to 440, 700 to 290
    )

    fun inspect(width: Int, height: Int, pixelAt: (Int, Int) -> Int): RecordedSurfaceReading {
        if (width < 320 || height < 640 ||
            abs(width.toDouble() / height - 6.0 / 13.0) > 0.008) {
            return RecordedSurfaceReading(RecordedSurfaceStatus.UNKNOWN, 0, 0)
        }
        var panel = 0
        var neutral = 0
        for ((xN, yN) in samplePoints) {
            val rgb = pixelAt((xN.toLong() * (width - 1) / 1000).toInt(),
                (yN.toLong() * (height - 1) / 1000).toInt())
            val red = (rgb ushr 16) and 255
            val green = (rgb ushr 8) and 255
            val blue = rgb and 255
            if (blue - red >= 11 && blue - green >= 8 && red <= 75 && green <= 90) panel++
            if (abs(red - green) <= 10 && abs(green - blue) <= 12 &&
                red + green + blue in 105..510) neutral++
        }
        val status = when {
            panel >= 4 -> RecordedSurfaceStatus.PANEL_VISIBLE
            panel == 0 && neutral >= 4 -> RecordedSurfaceStatus.TABLE_LIKE
            else -> RecordedSurfaceStatus.UNKNOWN
        }
        return RecordedSurfaceReading(status, panel, neutral)
    }
}
