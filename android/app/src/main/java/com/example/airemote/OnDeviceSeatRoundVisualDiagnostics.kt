package com.example.airemote

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * T048 Android adapter for multi-cue video observations. No gestures, no
 * game-event or memory authorization. Each measured region is tied to the
 * exact captured JPEG; window, epoch and original dimensions must match.
 */
object OnDeviceSeatRoundVisualDiagnostics {
    private val fusion = T048SeatSignalFusion()

    @Synchronized fun reset() = fusion.reset()

    @Synchronized fun inspect(
        frame: FrameInfo, ticket: OcrFrameTicket, nowMs: Long,
        regions: RegionObservationReport?, badges: SeatVisualReport?,
        stopped: Boolean
    ): T048Reading? {
        val profile = OnDeviceRealFrameRegions.activeProfile() ?: run {
            fusion.reset()
            return null
        }
        val regionReport = regions ?: run { fusion.reset(); return null }
        val badgeReport = badges ?: run { fusion.reset(); return null }
        if (stopped || !profile.valid() || profile.expectedPackage != ticket.sourcePackage ||
            profile.frameWidth != frame.width || profile.frameHeight != frame.height ||
            frame.captureEpoch != ticket.captureEpoch ||
            frame.seq != ticket.frameSequence ||
            frame.capturedAtMs != ticket.capturedAtMs ||
            frame.windowGeneration != ticket.windowGeneration ||
            regionReport.status != RegionObservationStatus.OBSERVED ||
            regionReport.frameSequence != ticket.frameSequence ||
            badgeReport.frameSequence != ticket.frameSequence ||
            nowMs - ticket.capturedAtMs !in 0L..1_800L) {
            fusion.reset()
            return null
        }

        fun money(role: RealFieldRole): Map<Int, Long> =
            regionReport.fields.filter { it.role == role &&
                it.seatIndex != null && it.amountMinorUnits != null }
                .groupBy { requireNotNull(it.seatIndex) }
                .mapNotNull { (seat, values) ->
                    values.singleOrNull()?.amountMinorUnits?.let { seat to it }
                }.toMap()

        val pots = regionReport.fields.filter {
            it.role == RealFieldRole.POT && it.amountMinorUnits != null
        }
        val pot = pots.singleOrNull()?.amountMinorUnits

        val actions = badgeReport.candidates.groupBy { it.seatIndex }
            .mapNotNull { (seat, entries) ->
                entries.singleOrNull()?.action?.let { seat to it }
            }.toMap()

        val backZones = profile.zones.filter {
            it.role == RealFieldRole.SEAT_CARD_BACK &&
                it.seatIndex != null
        }
        val backs = linkedMapOf<Int, Boolean>()
        if (backZones.isNotEmpty()) {
            val bitmap = try {
                BitmapFactory.decodeByteArray(
                    frame.jpeg, 0, frame.jpeg.size,
                    BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    })
            } catch (_: Exception) { null }
            if (bitmap != null) {
                try {
                    if (bitmap.width == frame.width &&
                        bitmap.height == frame.height) {
                        backZones.forEach { zone ->
                            val seat = requireNotNull(zone.seatIndex)
                            val b = zone.box
                            val l = (b.left.toLong() * bitmap.width / 1000L).toInt()
                            val t = (b.top.toLong() * bitmap.height / 1000L).toInt()
                            val r = (b.right.toLong() * bitmap.width / 1000L).toInt()
                            val bot = (b.bottom.toLong() * bitmap.height / 1000L).toInt()
                            if (l >= 0 && t >= 0 && r <= bitmap.width &&
                                bot <= bitmap.height && r > l && bot > t) {
                                var sampled = 0
                                var blue = 0
                                for (y in t until bot step 2) {
                                    for (x in l until r step 2) {
                                        if (T048CardBackColor.isBluePixel(
                                                bitmap.getPixel(x, y))) blue++
                                        sampled++
                                    }
                                }
                                T048CardBackColor.evidence(blue, sampled)?.let {
                                    backs[seat] = it
                                }
                            }
                        }
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        }
        val atCapture = frame.playerCardsAtCapture?.takeIf {
            it.captureEpoch == ticket.captureEpoch &&
                it.capturedAtMs in (ticket.capturedAtMs - 1_100L)..ticket.capturedAtMs
        }
        return fusion.observe(T048SeatFrame(
            ticket, frame.width, frame.height,
            money(RealFieldRole.SEAT_CONTRIBUTION),
            money(RealFieldRole.SEAT_STACK),
            backs, actions, pot, atCapture?.community?.size
        ), nowMs)
    }
}
