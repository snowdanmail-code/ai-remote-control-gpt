package com.example.airemote

import android.graphics.Bitmap

/**
 * No HTTP or LLM queries. A bounded pass over 540x1170 RGBA pixels, running
 * on the existing capture worker thread (never on the Android UI thread).
 */
data class OnDeviceTableReading(
    val cards: CardReading, val turn: TurnVisualReading,
    val miniRanks: List<Char?> = emptyList(),
    val visibleBoardSlots: Int = 0
)

object OnDeviceCardReader {
    /** Preserve phone aspect ratio for the new centered-card detector. */
    private fun centeredRaster(source: Bitmap, legacy: VisionRaster): VisionRaster {
        val targetWidth = (source.width.toLong() * 1170L / source.height)
            .toInt().coerceIn(320, 900)
        if (kotlin.math.abs(targetWidth - legacy.width) <= 2) return legacy
        val scaled = Bitmap.createScaledBitmap(source, targetWidth, 1170, true)
        try {
            val pixels = IntArray(targetWidth * 1170)
            scaled.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, 1170)
            return VisionRaster(targetWidth, 1170, pixels)
        } finally { if (scaled !== source) scaled.recycle() }
    }
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
            val legacyRaster = VisionRaster(reference.width, reference.height, pixels)
            val legacyBoard = CardVisionCore.readVisibleCommunity(legacyRaster)
            // Locate the 3-5 real white card rectangles between the central
            // upper "Банк" label and the lower chips/amount display.
            // An absent/ambiguous row cannot produce a guessed board.
            val centeredBoard = CenteredTableBoardReader.read(
                centeredRaster(source, legacyRaster))
            var board = TableBoardCandidateSelector.choose(legacyBoard, centeredBoard)
            if (!board.complete && source.width >= 810 && source.height >= 1755 &&
                source.width.toDouble() / source.height in 0.45..0.48) {
                val detail = Bitmap.createScaledBitmap(source, 810, 1755, true)
                try {
                    val morePixels = IntArray(detail.width * detail.height)
                    detail.getPixels(morePixels, 0, detail.width, 0, 0,
                        detail.width, detail.height)
                    val detailRaster = VisionRaster(detail.width, detail.height, morePixels)
                    val alternate = TableBoardCandidateSelector.choose(
                        CardVisionCore.readVisibleCommunity(detailRaster),
                        CenteredTableBoardReader.read(detailRaster))
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
            val original = CardVisionCore.readCards(raster)
            val adaptiveRaster = centeredRaster(source, raster)
            val centered = CenteredTableBoardReader.read(adaptiveRaster)
            // A complete hero hand from the old detector stays preferred, but
            // cannot claim preflop when independent visible board rectangles
            // directly contradict it.
            val base = CenteredPlayerBoardFusion.merge(original, centered)
            val adaptive = if (base.complete) null else AdaptivePlayerCardReader.read(adaptiveRaster)
            // Code85: cards may be TWO SEPARATE rectangles, not one merged pair.
            val separate = if (base.complete) null else SeparateHeroCardReader.read(adaptiveRaster)
            val legacyFallback = AdaptiveCardCandidateResolver.choose(base, adaptive)
            val selected = if (adaptive?.complete == true && separate?.complete == true &&
                (adaptive.hole != separate.hole || adaptive.community != separate.community)) {
                // Two independently plausible visual candidates disagree: no guessing.
                base
            } else AdaptiveCardCandidateResolver.choose(legacyFallback, separate)
            // A partial "В ?" or "? ♦" is evidence for the operator, NOT
            // a complete card for strategy or the two-frame action gate.
            val forScreen = if (selected.holeGlyphs.isEmpty() &&
                (selected.hole.size != 2 || selected.hole.any { it == null })) {
                selected.copy(holeGlyphs=SeparateHeroCardReader.preview(adaptiveRaster))
            } else selected
            val minis = MiniHeroRankReader.read(adaptiveRaster)
            return OnDeviceTableReading(forScreen.copy(miniRankHints=minis),
                TurnControlsVision.read(raster), minis,
                maxOf(original.community.size, centered.cards.size,
                    adaptive?.community?.size ?: 0, separate?.community?.size ?: 0))
        } finally {
            if (scaled !== source) scaled.recycle()
        }
    }
}
