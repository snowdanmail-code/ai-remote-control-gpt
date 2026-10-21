package com.example.airemote

import android.graphics.Bitmap

/**
 * No HTTP or LLM queries. A bounded pass over 540x1170 RGBA pixels, running
 * on the existing capture worker thread (never on the Android UI thread).
 */
data class OnDeviceTableReading(
    val cards: CardReading,
    val turn: TurnVisualReading,
    val spectatorBoard: VisibleCommunityReading
)

object OnDeviceCardReader {
    fun inspect(source: Bitmap): CardReading = inspectAll(source).cards

    /** One downscale and one pixel copy for cards AND the bottom action strip. */
    fun inspectAll(source: Bitmap): OnDeviceTableReading {
        val scaled = if (source.width == 540 && source.height == 1170) source
            else Bitmap.createScaledBitmap(source, 540, 1170, true)
        try {
            val pixels = IntArray(scaled.width * scaled.height)
            scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
            val raster = VisionRaster(scaled.width, scaled.height, pixels)
            var cards = CardVisionCore.readCards(raster)
            val turn = TurnControlsVision.read(raster)
            var spectatorBoard = CardVisionCore.readVisibleCommunity(raster)
            // Keep the calibrated first pass; a larger independent pass is
            // diagnostic fallback only if the first reading was incomplete.
            if (!cards.complete && source.width >= 810 && source.height >= 1755 &&
                source.width.toDouble() / source.height in 0.45..0.48) {
                val larger = Bitmap.createScaledBitmap(source, 810, 1755, true)
                try {
                    val pixelsLarge = IntArray(larger.width * larger.height)
                    larger.getPixels(pixelsLarge, 0, larger.width, 0, 0,
                        larger.width, larger.height)
                    val highResolutionRaster = VisionRaster(larger.width, larger.height, pixelsLarge)
                    val candidate = CardVisionCore.readCards(highResolutionRaster)
                    // Spectator board recognition is completely independent of
                    // the two absent hero cards and of gesture authorization.
                    if (!spectatorBoard.complete) {
                        val highResolutionBoard =
                            CardVisionCore.readVisibleCommunity(highResolutionRaster)
                        if (highResolutionBoard.complete) spectatorBoard = highResolutionBoard
                    }
                    if (candidate.complete) cards = candidate
                } finally { if (larger !== source) larger.recycle() }
            }
            return OnDeviceTableReading(cards, turn, spectatorBoard)
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }
}
