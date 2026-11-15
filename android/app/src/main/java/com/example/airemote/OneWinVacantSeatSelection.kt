package com.example.airemote

import kotlin.math.abs

/**
 * Six *screen* locations before sitting at a 1win cash table.
 * This is NOT the physical chair number and NEVER identifies the hero.
 * Positions are normalized from the owner's 354x768 pre-seat screens.
 */
enum class OneWinPreSeatSlot {
    TOP, LEFT_UPPER, RIGHT_UPPER, LEFT_LOWER, RIGHT_LOWER, BOTTOM
}

data class OneWinVacancyChoice(
    val slot: OneWinPreSeatSlot,
    val marker: OneWinVacantSeatMarker
)

/** A second visual constraint: never mistake toolbar icons for vacant seats. */
object OneWinVacantSeatGeometry {
    private data class Expected(val slot: OneWinPreSeatSlot, val x: Int, val y: Int)
    private val expected = listOf(
        Expected(OneWinPreSeatSlot.TOP, 500, 180),
        Expected(OneWinPreSeatSlot.LEFT_UPPER, 133, 380),
        Expected(OneWinPreSeatSlot.RIGHT_UPPER, 850, 380),
        Expected(OneWinPreSeatSlot.LEFT_LOWER, 133, 645),
        Expected(OneWinPreSeatSlot.RIGHT_LOWER, 850, 645),
        Expected(OneWinPreSeatSlot.BOTTOM, 500, 800)
    )

    fun slotFor(marker: OneWinVacantSeatMarker): OneWinPreSeatSlot? {
        if (!marker.bounds.valid() || !marker.confidence.isFinite() ||
            marker.confidence < 0.87 || marker.confidence > 1.0) return null
        val x = marker.bounds.centerX()
        val y = marker.bounds.centerY()
        val hits = expected.filter { abs(x - it.x) <= 120 && abs(y - it.y) <= 105 }
        return hits.singleOrNull()?.slot
    }

    /** Prefer the strongest confirmed arrow; ties prefer top-to-bottom. */
    fun choose(markers: List<OneWinVacantSeatMarker>): OneWinVacancyChoice? {
        if (markers.isEmpty() || markers.size > 6) return null
        val mapped = markers.mapNotNull { marker ->
            slotFor(marker)?.let { OneWinVacancyChoice(it, marker) }
        }
        // One unknown icon or two icons in the same slot invalidate the
        // whole navigation candidate, not just that particular icon.
        if (mapped.size != markers.size ||
            mapped.map { it.slot }.distinct().size != mapped.size) return null
        return mapped.sortedWith(compareByDescending<OneWinVacancyChoice> {
            it.marker.confidence
        }.thenBy { it.slot.ordinal }).firstOrNull()
    }
}

/** Evidence ticket only for a single, non-financial seat-arrow tap. */
data class OneWinVacantSeatTapTicket(
    val requestId: Long,
    val sourcePackage: String,
    val captureEpoch: Long,
    val windowGeneration: Long,
    val frameSequence: Long,
    val observedAtMs: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val selected: OneWinVacancyChoice
)

/** A tap event cannot imply a buy-in was accepted or that hero is seated. */
enum class OneWinSeatEntryGestureOutcome {
    NEVER_DISPATCHED, REQUESTED, ACCEPTED_BY_ANDROID, REJECTED_BY_ANDROID
}
