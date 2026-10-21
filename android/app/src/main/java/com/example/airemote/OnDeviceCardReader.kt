package com.example.airemote

import android.graphics.Bitmap

/**
 * No HTTP or LLM queries. A bounded pass over 540x1170 RGBA pixels, running
 * on the existing capture worker thread (never on the Android UI thread).
 */
data class OnDeviceTableReading(val cards: CardReading, val turn: TurnVisualReading)

object OnDeviceCardReader {
    /**
     * Separate spectator-only reader. Never invoked from inspectAll() or hero strategy.
     * It only reads cards visibly dealt face-up in the shared board row.
     */
    fun inspectSpectator(source: Bitmap): VisibleCommunityReading {
        val reference = if (source.width == 540 && source.height == 1170) source
            else Bitmap.createScaledBitmap(source, 540, 1170, true)
        try {
            val pixels = IntArray(reference.width * reference.height)
            reference.getPixels(pixels, 0, reference.width, 0, 0,
                reference.width, reference.height)
            var board = CardVisionCore.readVisibleCommunity(
                VisionRaster(reference.width, reference.height, pixels))
            if (!board.complete && source.width >= 810 && source.height >= 1755 &&
                source.width.toDouble() / source.height in 0.45..0.48) {
                val detail = Bitmap.createScaledBitmap(source, 810, 1755, true)
                try {
                    val morePixels = IntArray(detail.width * detail.height)
                    detail.getPixels(morePixels, 0, detail.width, 0, 0,
                        detail.width, detail.height)
                    val alternate = CardVisionCore.readVisibleCommunity(
                        VisionRaster(detail.width, detail.height, morePixels))
                    if (alternate.complete) board = alternate
                } finally { if (detail !== source) detail.recycle() }
            }
            return board
        } finally { if (reference !== source) reference.recycle() }
    }

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
                    if (candidate.complete) cards = candidate
                } finally { if (larger !== source) larger.recycle() }
            }
            return OnDeviceTableReading(cards, turn)
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }
}
