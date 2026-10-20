package com.example.airemote

/**
 * Untrusted seat-adjacent OCR observations. A calibrated rectangle is NOT a
 * player identity, action history, evidence of a turn, or permission to tap.
 * No default layout exists: every real screen resolution/layout needs a
 * measured calibration and independent validation before use.
 */
data class SeatRegion(val seatIndex: Int, val bounds: NormalizedTextBox)

data class SeatLayoutCalibration(
    val layoutKey: String,
    val expectedPackage: String,
    val frameWidth: Int,
    val frameHeight: Int,
    val regions: List<SeatRegion>
) {
    fun isValid(): Boolean {
        if (layoutKey.isBlank() || layoutKey.length > 64 ||
            layoutKey.any { it.isISOControl() } ||
            expectedPackage.isBlank() || expectedPackage.length > 160 ||
            frameWidth !in 320..5000 || frameHeight !in 320..9000 ||
            regions.size !in 2..10 || regions.map { it.seatIndex }.distinct().size != regions.size ||
            regions.any { it.seatIndex !in 0..9 || !it.bounds.valid() ||
                it.bounds.bottom > 900 || it.bounds.top < 40 }) return false
        // Touching regions are allowed; overlapping interiors are not.
        return regions.indices.all { a -> ((a + 1) until regions.size).all { b ->
            val x = regions[a].bounds; val y = regions[b].bounds
            x.right <= y.left || y.right <= x.left || x.bottom <= y.top || y.bottom <= x.top
        } }
    }
}

enum class SeatCueAction { FOLD, CHECK, CALL, BET, RAISE, ALL_IN }
data class SeatCue(val seatIndex: Int, val action: SeatCueAction)
enum class SeatDiagnosticStatus {
    NOT_CALIBRATED, INVALIDATED, REJECTED, COLLECTING, STABLE_DIAGNOSTIC
}
data class SeatVisualReport(
    val status: SeatDiagnosticStatus,
    /** Per-frame OCR cues, potentially false positives. */
    val candidates: List<SeatCue> = emptyList(),
    /** Two-frame agreements, STILL UNTRUSTED and not gameplay events. */
    val stableCues: List<SeatCue> = emptyList(),
    val frameSequence: Long = -1L
) {
    fun summary(): String = "Места игроков: $status; кандидатов ${candidates.size}; " +
        "двухкадровых совпадений ${stableCues.size}; личности и действия НЕ подтверждены"
}

/**
 * Does not consume the OCR *button* parser: seat overlays need separate
 * regions. Never converts a stable cue to ConfirmedOpponentAction or a gesture.
 */
class SeatVisualDiagnostics(
    private val maxAgeMs: Long = 1500,
    private val minimumGapMs: Long = 100,
    private val maximumGapMs: Long = 2500
) {
    private var calibration: SeatLayoutCalibration? = null
    private var lastTicket: OcrFrameTicket? = null
    private var previousCues: List<SeatCue> = emptyList()

    @Synchronized fun configured(): Boolean = calibration != null

    @Synchronized fun reset(clearCalibration: Boolean = true) {
        lastTicket = null
        previousCues = emptyList()
        if (clearCalibration) calibration = null
    }

    /** Configuration does not imply that the calibration is correct. */
    @Synchronized fun configure(value: SeatLayoutCalibration): Boolean {
        reset()
        if (!value.isValid()) return false
        calibration = value
        return true
    }

    private val labels = listOf(
        SeatCueAction.FOLD to setOf("fold", "сброс", "сбросил", "пас"),
        SeatCueAction.CHECK to setOf("check", "чек"),
        SeatCueAction.CALL to setOf("call", "колл"),
        SeatCueAction.BET to setOf("bet", "ставка"),
        SeatCueAction.RAISE to setOf("raise", "рейз", "повысил"),
        SeatCueAction.ALL_IN to setOf("all-in", "all in", "олл-ин")
    )

    @Synchronized fun inspect(ticket: OcrFrameTicket, lines: List<PositionedOcrLine>,
                               frameWidth: Int, frameHeight: Int, nowMs: Long): SeatVisualReport {
        val layout = calibration ?: return SeatVisualReport(SeatDiagnosticStatus.NOT_CALIBRATED)
        val old = lastTicket
        if (ticket.captureEpoch < 0 || ticket.windowGeneration < 0 ||
            ticket.frameSequence <= 0 || ticket.capturedAtMs < 0 ||
            ticket.sourcePackage != layout.expectedPackage ||
            frameWidth != layout.frameWidth || frameHeight != layout.frameHeight ||
            nowMs < ticket.capturedAtMs || nowMs - ticket.capturedAtMs > maxAgeMs ||
            (old != null && (old.captureEpoch != ticket.captureEpoch ||
                old.windowGeneration != ticket.windowGeneration ||
                old.sourcePackage != ticket.sourcePackage))) {
            reset()
            return SeatVisualReport(SeatDiagnosticStatus.INVALIDATED)
        }
        if (old != null && (ticket.frameSequence <= old.frameSequence ||
            ticket.capturedAtMs <= old.capturedAtMs)) {
            reset()
            return SeatVisualReport(SeatDiagnosticStatus.INVALIDATED)
        }
        if (lines.size > 100) {
            previousCues = emptyList()
            lastTicket = ticket
            return SeatVisualReport(SeatDiagnosticStatus.REJECTED, frameSequence = ticket.frameSequence)
        }
        val extracted = mutableListOf<SeatCue>()
        for (line in lines) {
            if (!line.box.valid() || line.text.length > 40) continue
            val token = line.text.trim().lowercase().replace(Regex("\\s+"), " ")
            val action = labels.singleOrNull { token in it.second }?.first ?: continue
            // The entire text rectangle must fit strictly INSIDE one seat region.
            val matches = layout.regions.filter {
                line.box.left > it.bounds.left && line.box.top > it.bounds.top &&
                    line.box.right < it.bounds.right && line.box.bottom < it.bounds.bottom
            }
            if (matches.size == 1) extracted.add(SeatCue(matches.single().seatIndex, action))
        }
        // More than one cue in one seat is ambiguous, even if OCR repeated one label.
        val ambiguous = extracted.groupBy { it.seatIndex }.filterValues { it.size > 1 }.keys
        val clean = extracted.filterNot { it.seatIndex in ambiguous }.sortedBy { it.seatIndex }
        val stable = if (old != null && ticket.capturedAtMs - old.capturedAtMs in minimumGapMs..maximumGapMs)
            clean.filter { it in previousCues && it.seatIndex !in ambiguous } else emptyList()
        previousCues = clean
        lastTicket = ticket
        return SeatVisualReport(
            if (stable.isNotEmpty()) SeatDiagnosticStatus.STABLE_DIAGNOSTIC
            else if (ambiguous.isNotEmpty()) SeatDiagnosticStatus.REJECTED
            else SeatDiagnosticStatus.COLLECTING,
            clean, stable, ticket.frameSequence
        )
    }
}

/** No default calibration and no implicit promotion to opponent history. */
object OnDeviceSeatVisualDiagnostics {
    private val tracker = SeatVisualDiagnostics()
    @Synchronized fun configure(value: SeatLayoutCalibration): Boolean = tracker.configure(value)
    @Synchronized fun reset() = tracker.reset()
    @Synchronized fun configured(): Boolean = tracker.configured()
    @Synchronized fun inspect(ticket: OcrFrameTicket, lines: List<PositionedOcrLine>,
        width: Int, height: Int, nowMs: Long): SeatVisualReport =
        tracker.inspect(ticket, lines, width, height, nowMs)
}
