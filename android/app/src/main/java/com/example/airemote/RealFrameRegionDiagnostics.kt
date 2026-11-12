package com.example.airemote

/**
 * Geometry for OCR observations on an ORIGINAL captured Android screen.
 * Coordinates are per-JPEG normalized [0,1000], NEVER tap coordinates.
 * The operator must measure these on an authentic frame; no baked-in 1win layout.
 */
enum class RealFieldRole { SEAT_ACTION, SEAT_STACK, SEAT_CONTRIBUTION, SEAT_TURN, SEAT_AVATAR,
    PROFILE_DISMISS, POT, TIMER, HERO_FOLD, HERO_CHECK_CALL, HERO_BET_RAISE }

data class RealFrameZone(
    val role: RealFieldRole,
    val box: NormalizedTextBox,
    val seatIndex: Int? = null
)

data class RealFrameCalibration(
    val layoutKey: String,
    val expectedPackage: String,
    val frameWidth: Int,
    val frameHeight: Int,
    val zones: List<RealFrameZone>
) {
    fun valid(): Boolean {
        if (layoutKey.isBlank() || layoutKey.length > 64 || layoutKey.any { it.isISOControl() } ||
            !Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+").matches(expectedPackage) ||
            frameWidth !in 320..5000 || frameHeight !in 320..9000 ||
            zones.size !in 2..52) return false
        if (zones.any { !it.box.valid() || (it.role.name.startsWith("SEAT_") && it.seatIndex !in 0..9) ||
            (!it.role.name.startsWith("SEAT_") && it.seatIndex != null) }) return false
        val actionSeats = zones.filter { it.role == RealFieldRole.SEAT_ACTION }.mapNotNull { it.seatIndex }
        if (actionSeats.size !in 2..10 || actionSeats.distinct().size != actionSeats.size) return false
        if (zones.groupBy { it.role to it.seatIndex }.any { it.value.size != 1 }) return false
        // Optional non-gameplay navigation targets, imported only from a
        // manually measured original frame. A seat action rectangle is NOT
        // an avatar target; an empty-table dismiss target is not a bet button.
        val avatars = zones.filter { it.role == RealFieldRole.SEAT_AVATAR }
        val dismiss = zones.filter { it.role == RealFieldRole.PROFILE_DISMISS }
        if (avatars.any { a ->
                a.seatIndex !in actionSeats ||
                a.box.centerY() !in 100..820 ||
                a.box.centerX() !in 35..950 ||
                a.box.right - a.box.left !in 20..170 ||
                a.box.bottom - a.box.top !in 20..170
            }) return false
        if (dismiss.size > 1 || dismiss.any { d ->
                d.box.centerX() !in 30..285 || d.box.centerY() !in 170..820 ||
                d.box.right - d.box.left !in 25..210 ||
                d.box.bottom - d.box.top !in 25..210 ||
                avatars.any { a ->
                    d.box.left < a.box.right && a.box.left < d.box.right &&
                    d.box.top < a.box.bottom && a.box.top < d.box.bottom
                }
            }) return false
        val hero = zones.filter { it.role.name.startsWith("HERO_") }
        if (hero.size != 0 && (hero.size != 3 || hero.map { it.role }.distinct().size != 3)) return false
        if (hero.any { z ->
                val b = z.box
                val x = b.centerX()
                z.seatIndex != null || b.top < 900 || b.bottom > 990 ||
                    b.right - b.left !in 80..340 || b.bottom - b.top !in 18..90 ||
                    b.left < 15 || b.right > 985 || when (z.role) {
                        RealFieldRole.HERO_FOLD -> x !in 50..325
                        RealFieldRole.HERO_CHECK_CALL -> x !in 335..660
                        RealFieldRole.HERO_BET_RAISE -> x !in 670..950
                        else -> true
                    }
            }) return false
        for (a in hero.indices) for (b in a + 1 until hero.size) {
            val x = hero[a].box; val y = hero[b].box
            if (x.left < y.right && y.left < x.right &&
                x.top < y.bottom && y.top < x.bottom) return false
        }
        // Zones with the SAME semantic role may not overlap, otherwise labels are assigned twice.
        for (i in zones.indices) for (j in i+1 until zones.size) {
            val a = zones[i]; val b = zones[j]
            if (a.role != b.role) continue
            if (a.box.left < b.box.right && b.box.left < a.box.right &&
                a.box.top < b.box.bottom && b.box.top < a.box.bottom) return false
        }
        // Seat action boxes must obey the existing conservative seat OCR contract.
        return zones.filter { it.role == RealFieldRole.SEAT_ACTION }.all {
            it.box.top >= 40 && it.box.bottom <= 900
        }
    }

    fun seatVisualCalibration(): SeatLayoutCalibration = SeatLayoutCalibration(
        layoutKey, expectedPackage, frameWidth, frameHeight,
        zones.filter { it.role == RealFieldRole.SEAT_ACTION }
            .map { SeatRegion(requireNotNull(it.seatIndex), it.box) }
    )
}

enum class RegionObservationStatus { NOT_CALIBRATED, REJECTED, OBSERVED }
data class RegionTextObservation(
    val role: RealFieldRole,
    val seatIndex: Int?,
    val text: String,
    /** Null means not an unambiguous, plain integer. No synthetic zero. */
    val numericValue: Long?,
    /** Exact decimal amount, in hundredths of the currency unit, if explicitly marked $. */
    val amountMinorUnits: Long? = null
)
data class RegionObservationReport(
    val status: RegionObservationStatus,
    val reason: String,
    val frameSequence: Long = -1L,
    val fields: List<RegionTextObservation> = emptyList()
) {
    /** A rectangle and an OCR value are never identity, action proof or tap permission. */
    val authorizesAction: Boolean get() = false
    val canCommitOpponentHistory: Boolean get() = false
}

/** No screenshot storage, no implicit trust promotion, no tap coordinates. */
class RealFrameRegionDiagnostics(private val maxAgeMs: Long = 1500L) {
    private var calibration: RealFrameCalibration? = null
    private var last: OcrFrameTicket? = null

    @Synchronized fun configure(profile: RealFrameCalibration): Boolean {
        calibration = null
        last = null
        if (!profile.valid() || !profile.seatVisualCalibration().isValid()) return false
        calibration = profile
        return true
    }
    @Synchronized fun configured(): Boolean = calibration != null
    @Synchronized fun activeProfile(): RealFrameCalibration? = calibration
    @Synchronized fun resetSession() { last = null }
    @Synchronized fun clear() { last = null; calibration = null }

    @Synchronized fun inspect(ticket: OcrFrameTicket, frameWidth: Int, frameHeight: Int,
        nowMs: Long, lines: List<PositionedOcrLine>, operatorStopped: Boolean = false
    ): RegionObservationReport {
        val p = calibration ?: return RegionObservationReport(RegionObservationStatus.NOT_CALIBRATED, "no measured real-frame layout")
        val prior = last
        fun reject(reason: String): RegionObservationReport {
            last = null
            return RegionObservationReport(RegionObservationStatus.REJECTED, reason)
        }
        if (operatorStopped || frameWidth != p.frameWidth || frameHeight != p.frameHeight ||
            ticket.sourcePackage != p.expectedPackage || ticket.captureEpoch < 0 || ticket.windowGeneration < 0 ||
            ticket.frameSequence <= 0 || ticket.capturedAtMs < 0 ||
            nowMs < ticket.capturedAtMs || nowMs - ticket.capturedAtMs > maxAgeMs) return reject("STOP, frame shape, source or age mismatch")
        if (prior != null && (prior.captureEpoch != ticket.captureEpoch ||
            prior.windowGeneration != ticket.windowGeneration || prior.sourcePackage != ticket.sourcePackage ||
            ticket.frameSequence <= prior.frameSequence || ticket.capturedAtMs <= prior.capturedAtMs))
            return reject("window, capture epoch or frame ordering changed")
        if (lines.size > 100) return reject("too many OCR lines")
        val observed = mutableListOf<RegionTextObservation>()
        for (zone in p.zones) {
            val inside = lines.filter { l ->
                l.box.valid() && l.text.isNotBlank() && l.text.length <= 50 &&
                    l.box.left > zone.box.left && l.box.right < zone.box.right &&
                    l.box.top > zone.box.top && l.box.bottom < zone.box.bottom
            }
            // Multi-line and duplicate readings are ambiguous, even when text happens to agree.
            if (inside.size != 1) continue
            val text = inside.single().text.trim()
            val numeric = when (zone.role) {
                RealFieldRole.TIMER -> parseTimerSeconds(text)
                RealFieldRole.POT, RealFieldRole.SEAT_STACK,
                RealFieldRole.SEAT_CONTRIBUTION -> parseNumber(text)
                else -> null
            }
            val minor = if (zone.role in setOf(RealFieldRole.POT,
                    RealFieldRole.SEAT_STACK, RealFieldRole.SEAT_CONTRIBUTION))
                parseMoneyMinorUnits(text) else null
            observed.add(RegionTextObservation(zone.role, zone.seatIndex, text, numeric, minor))
        }
        last = ticket
        return RegionObservationReport(RegionObservationStatus.OBSERVED,
            "raw observations only, not independent state verification", ticket.frameSequence, observed)
    }

    private fun parseTimerSeconds(raw: String): Long? {
        // Real-recording countdown labels contain "60 с" (Cyrillic small es).
        // Only a strict timer-region reading, never an action deadline on its own.
        val cleaned = raw.trim()
        val match = Regex("""^([0-9]{1,3})(?:\s*(?:с|c|s|сек))?$""",
            RegexOption.IGNORE_CASE).matchEntire(cleaned) ?: return null
        return match.groupValues[1].toLongOrNull()?.takeIf { it in 0L..120L }
    }

    private fun parseMoneyMinorUnits(raw: String): Long? {
        // The authentic videos display 0.17 $ and 3.16 $. No inferred units,
        // hidden decimal precision, currency conversion, or rounding.
        val match = Regex("""^([0-9]{1,7})[.,]([0-9]{2})\s*\$""")
            .matchEntire(raw.trim()) ?: return null
        val major = match.groupValues[1].toLongOrNull() ?: return null
        val fractional = match.groupValues[2].toLongOrNull() ?: return null
        return major * 100L + fractional
    }

    private fun parseNumber(raw: String): Long? {
        // No currencies, decimals, abbreviations (K/M) or OCR guesswork.
        if (!Regex("(?:[0-9]{1,12}|[0-9]{1,3}(?:[ \\u00a0\\u202f][0-9]{3}){1,3})").matches(raw)) return null
        val parsed = raw.replace(" ", "").replace("\u00a0", "").replace("\u202f", "").toLongOrNull()
        return parsed?.takeIf { it in 0..999_999_999_999L }
    }
}

/** Shared read-only diagnostic router; STOP clears its entire in-memory calibration. */
object OnDeviceRealFrameRegions {
    private val reader = RealFrameRegionDiagnostics()
    @Synchronized fun configure(profile: RealFrameCalibration): Boolean {
        val ok = reader.configure(profile)
        if (!ok) {
            OnDeviceSeatVisualDiagnostics.reset()
            OnDeviceActionStripDiagnostics.clear()
        } else {
            OnDeviceSeatVisualDiagnostics.configure(profile.seatVisualCalibration())
            // Optional: without all three measured button rectangles the live
            // action-strip diagnostic remains disabled and never guesses them.
            OnDeviceActionStripDiagnostics.configure(profile)
        }
        return ok
    }
    @Synchronized fun clear() { reader.clear(); OnDeviceSeatVisualDiagnostics.reset(); OnDeviceActionStripDiagnostics.clear() }
    @Synchronized fun resetSession() { reader.resetSession(); OnDeviceActionStripDiagnostics.resetSession() }
    @Synchronized fun configured(): Boolean = reader.configured()
    @Synchronized fun activeProfile(): RealFrameCalibration? = reader.activeProfile()
    @Synchronized fun inspect(ticket: OcrFrameTicket, w: Int, h: Int, now: Long,
        lines: List<PositionedOcrLine>, stopped: Boolean): RegionObservationReport {
        // A capture reset cleared the two-frame tracker. Restore only the
        // operator-imported, read-only layout, never old evidence or action rights.
        val layout = reader.activeProfile()
        if (layout != null && !OnDeviceSeatVisualDiagnostics.configured())
            OnDeviceSeatVisualDiagnostics.configure(layout.seatVisualCalibration())
        return reader.inspect(ticket, w, h, now, lines, stopped)
    }
}
