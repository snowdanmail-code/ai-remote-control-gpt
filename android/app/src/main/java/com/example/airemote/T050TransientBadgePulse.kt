package com.example.airemote

/**
 * T050 checkpoint 04: cheap 300ms per-seat badge-color pulse analysis.
 * A visual pulse is NOT the word "FOLD", player identity, hand evidence,
 * or a gesture. Use it to prioritize subsequent frame/ROI OCR.
 */
enum class T050BadgeTone { NONE, GREEN, LIGHT, RED }
data class T050SeatTone(
    val seat: Int,
    val tone: T050BadgeTone,
    /** Fraction of sampled pixels matching the selected tone, 0..1. */
    val strength: Double = 0.0
)
data class T050BadgeFrame(
    val sourcePackage: String,
    val captureEpoch: Long,
    val windowGeneration: Long,
    val sequence: Long,
    val capturedAtMs: Long,
    val tones: List<T050SeatTone>
)
data class T050TransientBadgePulse(
    val seat: Int,
    val tone: T050BadgeTone,
    val captureEpoch: Long,
    val windowGeneration: Long,
    val frameSequence: Long,
    val capturedAtMs: Long
) {
    val confirmsAction: Boolean get() = false
    val authorizesTouch: Boolean get() = false
}

/** Cheap sampling of calibrated SEAT_ACTION only, never the avatar. */
object T050BadgePixelTone {
    fun tone(r: Int, g: Int, b: Int): T050BadgeTone {
        val max = maxOf(r,g,b)
        val min = minOf(r,g,b)
        if (max < 110) return T050BadgeTone.NONE
        if (g >= 90 && g >= r * 1.35 && g >= b * 1.12 &&
            g-min > 40) return T050BadgeTone.GREEN
        if (r >= 140 && r >= g * 1.35 && r >= b * 1.26 &&
            r-min > 38) return T050BadgeTone.RED
        if (min >= 166 && max-min < 60) return T050BadgeTone.LIGHT
        return T050BadgeTone.NONE
    }
    fun classifyCounts(green: Int, light: Int, red: Int,
                       samples: Int): T050BadgeTone {
        if (samples < 60 || green < 0 || light < 0 || red < 0 ||
            green+light+red > samples) return T050BadgeTone.NONE
        return when {
            green.toDouble()/samples >= 0.11 -> T050BadgeTone.GREEN
            red.toDouble()/samples >= 0.11 -> T050BadgeTone.RED
            light.toDouble()/samples >= 0.16 -> T050BadgeTone.LIGHT
            else -> T050BadgeTone.NONE
        }
    }
}

/**
 * Per-seat state machine: two independent 300ms observations of the same
 * color (250..800ms apart), then ONE signal until color disappears for
 * two snapshots. Never carry badges across windows/epochs/table re-entry.
 */
class T050TransientBadgePulseTracker {
    private var last: T050BadgeFrame? = null
    private val prior = mutableMapOf<Int,T050BadgeTone>()
    private val priorStrength = mutableMapOf<Int,Double>()
    private val pending = mutableMapOf<Int,Pair<T050BadgeTone,Double>>()
    private val emitted = mutableSetOf<Int>()
    private val absentCounts = mutableMapOf<Int,Int>()

    @Synchronized fun reset() {
        last = null
        prior.clear()
        priorStrength.clear()
        pending.clear()
        emitted.clear()
        absentCounts.clear()
    }

    @Synchronized fun observe(frame: T050BadgeFrame, nowMs: Long):
        List<T050TransientBadgePulse> {
        if (frame.sourcePackage.isBlank() ||
            frame.captureEpoch < 0L || frame.windowGeneration < 0L ||
            frame.sequence <= 0 || nowMs - frame.capturedAtMs !in 0L..600L ||
            frame.tones.size > 10 || frame.tones.map { it.seat }.distinct().size != frame.tones.size ||
            frame.tones.any {
                it.seat !in 0..9 || !it.strength.isFinite() ||
                    it.strength !in 0.0..1.0
            }) {
            reset()
            return emptyList()
        }
        val previous = last
        if (previous == null) {
            last = frame
            prior.putAll(frame.tones.associate { it.seat to it.tone })
            priorStrength.putAll(frame.tones.associate { it.seat to it.strength })
            // If the capture began DURING a prominent temporary label,
            // confirm it on one additional independent raw frame. A static
            // white avatar fraction below 0.2 is never an initial pulse.
            frame.tones.filter {
                it.tone != T050BadgeTone.NONE && it.strength >= 0.20
            }.forEach { pending[it.seat] = it.tone to it.strength }
            return emptyList()
        }
        if (frame.sourcePackage != previous.sourcePackage ||
            frame.captureEpoch != previous.captureEpoch ||
            frame.windowGeneration != previous.windowGeneration ||
            frame.sequence <= previous.sequence ||
            frame.capturedAtMs - previous.capturedAtMs !in 250L..800L) {
            reset()
            last = frame
            prior.putAll(frame.tones.associate { it.seat to it.tone })
            priorStrength.putAll(frame.tones.associate { it.seat to it.strength })
            // If the capture began DURING a prominent temporary label,
            // confirm it on one additional independent raw frame. A static
            // white avatar fraction below 0.2 is never an initial pulse.
            frame.tones.filter {
                it.tone != T050BadgeTone.NONE && it.strength >= 0.20
            }.forEach { pending[it.seat] = it.tone to it.strength }
            return emptyList()
        }
        val current = frame.tones.associate { it.seat to it.tone }
        val strength = frame.tones.associate { it.seat to it.strength }
        val output = mutableListOf<T050TransientBadgePulse>()
        val seats = (prior.keys + current.keys).toSet()
        for (seat in seats) {
            val old = prior[seat] ?: T050BadgeTone.NONE
            val new = current[seat] ?: T050BadgeTone.NONE
            val previousFraction = priorStrength[seat] ?: 0.0
            val fraction = strength[seat] ?: 0.0
            if (new == T050BadgeTone.NONE) {
                val gaps = (absentCounts[seat] ?: 0) + 1
                absentCounts[seat] = gaps
                pending.remove(seat)
                if (gaps >= 2) emitted.remove(seat)
            } else {
                absentCounts.remove(seat)
                val jump = fraction - previousFraction >= 0.18
                if (jump && fraction >= 0.24) {
                    // Example: a static white BOTS avatar occupies 17% of
                    // a region; when a white FOLD label appears this may
                    // jump above 50%. Treat the edge, not the static color.
                    emitted.remove(seat)
                    pending[seat] = new to fraction
                } else if (pending[seat]?.first == new &&
                    fraction >= (pending[seat]!!.second * 0.65) &&
                    seat !in emitted) {
                    output.add(T050TransientBadgePulse(seat,new,
                        frame.captureEpoch,frame.windowGeneration,
                        frame.sequence,frame.capturedAtMs))
                    emitted.add(seat)
                    pending.remove(seat)
                } else if ((old == T050BadgeTone.NONE || new != old) &&
                    fraction >= 0.20 && seat !in emitted) {
                    pending[seat] = new to fraction
                }
            }
        }
        last = frame
        prior.clear()
        prior.putAll(current)
        priorStrength.clear()
        priorStrength.putAll(strength)
        return output
    }
}
