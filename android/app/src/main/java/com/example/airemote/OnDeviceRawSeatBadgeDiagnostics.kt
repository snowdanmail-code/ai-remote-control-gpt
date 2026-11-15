package com.example.airemote

import android.graphics.Bitmap

/**
 * Runs on the existing ~300ms MediaProjection capture worker, without
 * creating a new JPEG or starting a full-screen OCR request.
 * It only detects a colored shape INSIDE a measured SEAT_ACTION rectangle.
 * Later OCR must still read the actual temporary word independently.
 */
object OnDeviceRawSeatBadgeDiagnostics {
    private val tracker = T050TransientBadgePulseTracker()
    @Volatile var lastStatus: String = "Быстрые плашки: ожидается измеренная калибровка"
        private set

    @Synchronized fun reset() {
        tracker.reset()
        lastStatus = "Быстрые плашки: наблюдение сброшено"
    }

    @Synchronized fun inspect(
        bitmap: Bitmap, origin: WindowOriginAssessment,
        atMs: Long, stopped: Boolean
    ): List<T050TransientBadgePulse> {
        val calibration = OnDeviceRealFrameRegions.activeProfile()
        val pkg = origin.observedPackage
        val win = origin.windowGeneration
        if (stopped || !origin.safeForLocalDiagnostics ||
            pkg == null || win == null ||
            calibration == null || !calibration.valid() ||
            calibration.expectedPackage != pkg ||
            // Same screen proportions even if JPEG is downscaled.
            kotlin.math.abs(
                bitmap.width.toLong() * calibration.frameHeight -
                    bitmap.height.toLong() * calibration.frameWidth
            ) > bitmap.width.toLong() * calibration.frameHeight / 100L ||
            origin.localFrameSequence <= 0L ||
            bitmap.width < 320 || bitmap.height < 640) {
            reset()
            return emptyList()
        }

        val zones = calibration.zones.filter {
            it.role == RealFieldRole.SEAT_ACTION && it.seatIndex != null
        }
        val tones = zones.mapNotNull { zone ->
            val b = zone.box
            val x0 = (b.left.toLong() * bitmap.width / 1000L).toInt()
            val x1 = (b.right.toLong() * bitmap.width / 1000L).toInt()
            val y0 = (b.top.toLong() * bitmap.height / 1000L).toInt()
            val y1 = (b.bottom.toLong() * bitmap.height / 1000L).toInt()
            if (x0 !in 0 until bitmap.width ||
                y0 !in 0 until bitmap.height ||
                x1 !in 1..bitmap.width || y1 !in 1..bitmap.height ||
                x1 - x0 < 12 || y1 - y0 < 9) return@mapNotNull null
            val stride = maxOf(2, (x1 - x0)/24, (y1 - y0)/16)
            var green = 0
            var light = 0
            var red = 0
            var total = 0
            for (y in y0 until y1 step stride)
                for (x in x0 until x1 step stride) {
                    val argb = bitmap.getPixel(x,y)
                    val type = T050BadgePixelTone.tone(
                        (argb shr 16) and 255,
                        (argb shr 8) and 255,
                        argb and 255)
                    when(type) {
                        T050BadgeTone.GREEN -> green++
                        T050BadgeTone.LIGHT -> light++
                        T050BadgeTone.RED -> red++
                        T050BadgeTone.NONE -> Unit
                    }
                    total++
                }
            val classified = T050BadgePixelTone.classifyCounts(
                green,light,red,total)
            val count = when(classified) {
                T050BadgeTone.GREEN -> green
                T050BadgeTone.LIGHT -> light
                T050BadgeTone.RED -> red
                T050BadgeTone.NONE -> 0
            }
            T050SeatTone(requireNotNull(zone.seatIndex), classified,
                if (total > 0) count.toDouble()/total else 0.0)
        }
        if (tones.size != zones.size) {
            reset()
            return emptyList()
        }
        val pulse = tracker.observe(T050BadgeFrame(
            pkg, origin.captureEpoch, win, origin.localFrameSequence,atMs,tones),
            atMs)
        if (pulse.isNotEmpty())
            lastStatus = "Кандидаты быстрых цветных плашек: " +
                pulse.joinToString { "seat_${it.seat}:${it.tone}" } +
                " (OCR и действие НЕ подтверждены)"
        return pulse
    }
}
