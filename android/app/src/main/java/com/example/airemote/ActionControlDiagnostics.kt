package com.example.airemote

/** Three measured rectangles from the ORIGINAL game's action strip. They are
 * coordinates in a captured JPEG, not coordinates for Android dispatchGesture.
 * They do not establish a table/hand/turn identity.
 */
enum class HeroControlLane { FOLD, CHECK_CALL, BET_RAISE }

data class ActionStripCue(
    val lane: HeroControlLane,
    val action: ExactActionCandidate,
    val measuredButton: NormalizedTextBox,
    val observedText: String
)

enum class ActionStripStatus { NOT_CALIBRATED, REJECTED, COLLECTING, STABLE_DIAGNOSTIC }

data class ActionStripReport(
    val status: ActionStripStatus,
    val reason: String,
    val frameSequence: Long = -1L,
    val candidates: List<ActionStripCue> = emptyList(),
    val stable: List<ActionStripCue> = emptyList()
) {
    // Stable visual cues are NEVER live commands or verified tap areas.
    val authorizesTap: Boolean get() = false
    val confirmsHandOrTurn: Boolean get() = false
    fun summary(): String = "Полоса действий: $status; найдено ${candidates.size}; " +
        "повторно наблюдалось ${stable.size}. Не подтверждает ход и не отправляет касания. $reason"
}

/** The same operator-imported layout contains seat regions and, optionally,
 * all three hero action-button rectangles. No hard-coded touch coordinates.
 */
class ActionControlDiagnostics(
    private val maxFrameAgeMs: Long = 1_500L,
    private val maxGapMs: Long = 2_800L
) {
    private var profile: RealFrameCalibration? = null
    private var lastTicket: OcrFrameTicket? = null
    private var previous: List<ActionStripCue> = emptyList()

    @Synchronized fun configure(calibration: RealFrameCalibration): Boolean {
        clear()
        if (!calibration.valid()) return false
        val controls = calibration.zones.filter { it.role.name.startsWith("HERO_") }
        if (controls.size != 3 || controls.map { it.role }.toSet().size != 3) return false
        if (controls.any { !controlValid(it) }) return false
        // Any intersection is unsafe even when roles differ: this is one physical strip.
        for (i in controls.indices) for (j in i + 1 until controls.size) {
            val a = controls[i].box; val b = controls[j].box
            if (a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom) return false
        }
        profile = calibration
        return true
    }

    private fun controlValid(zone: RealFrameZone): Boolean {
        val b = zone.box
        if (zone.seatIndex != null || !b.valid() || b.top < 900 || b.bottom > 990 ||
            b.right - b.left !in 80..340 || b.bottom - b.top !in 18..90 ||
            b.left < 15 || b.right > 985) return false
        return when (zone.role) {
            RealFieldRole.HERO_FOLD -> b.centerX() in 50..325
            RealFieldRole.HERO_CHECK_CALL -> b.centerX() in 335..660
            RealFieldRole.HERO_BET_RAISE -> b.centerX() in 670..950
            else -> false
        }
    }

    @Synchronized fun clear() { profile = null; resetSession() }
    @Synchronized fun resetSession() { lastTicket = null; previous = emptyList() }
    @Synchronized fun configured(): Boolean = profile != null

    private fun lane(role: RealFieldRole): HeroControlLane? = when (role) {
        RealFieldRole.HERO_FOLD -> HeroControlLane.FOLD
        RealFieldRole.HERO_CHECK_CALL -> HeroControlLane.CHECK_CALL
        RealFieldRole.HERO_BET_RAISE -> HeroControlLane.BET_RAISE
        else -> null
    }

    private fun label(lane: HeroControlLane, raw: String): ExactActionCandidate? {
        val text = raw.trim().lowercase().replace(Regex("\\s+"), " ")
        return when (lane) {
            HeroControlLane.FOLD -> when (text) {
                "fold", "сброс", "пас", "сбросить" -> ExactActionCandidate.FOLD
                else -> null
            }
            HeroControlLane.CHECK_CALL -> when (text) {
                "check", "чек", "пропустить" -> ExactActionCandidate.CHECK
                "call", "колл", "уравнять", "ответить" -> ExactActionCandidate.CALL
                else -> VirtualChipDisplayParser.parseLabel(raw)
                    ?.takeIf { it.kind == NumericCandidateKind.CALL_AMOUNT &&
                        (raw.contains('
            }
            HeroControlLane.BET_RAISE -> when (text) {
                "bet", "ставка", "поставить" -> ExactActionCandidate.BET
                "raise", "рейз", "повысить" -> ExactActionCandidate.RAISE
                else -> null
            }
        }
    }

    @Synchronized fun inspect(ticket: OcrFrameTicket, frameWidth: Int, frameHeight: Int,
        nowMs: Long, lines: List<PositionedOcrLine>, turn: ConfirmedTurnDisplay?, stopped: Boolean
    ): ActionStripReport {
        val p = profile ?: return ActionStripReport(ActionStripStatus.NOT_CALIBRATED, "Нет измеренных областей кнопок")
        fun reject(reason: String): ActionStripReport {
            resetSession()
            return ActionStripReport(ActionStripStatus.REJECTED, reason)
        }
        if (stopped || ticket.sourcePackage != p.expectedPackage || frameWidth != p.frameWidth ||
            frameHeight != p.frameHeight || ticket.captureEpoch < 0 || ticket.windowGeneration < 0 ||
            ticket.frameSequence <= 0 || ticket.capturedAtMs < 0 || nowMs < ticket.capturedAtMs ||
            nowMs - ticket.capturedAtMs > maxFrameAgeMs || lines.size > 100)
            return reject("STOP, окно, размеры, время или OCR недостоверны")
        val old = lastTicket
        if (old != null && (old.captureEpoch != ticket.captureEpoch ||
            old.windowGeneration != ticket.windowGeneration || old.sourcePackage != ticket.sourcePackage ||
            ticket.frameSequence <= old.frameSequence || ticket.capturedAtMs <= old.capturedAtMs))
            return reject("Сменился сеанс/окно или повторился кадр")
        // The visual cue is intentionally *not* independent proof of a player's turn.
        if (turn == null || turn.state != TurnDisplayState.READY ||
            turn.captureEpoch != ticket.captureEpoch || turn.capturedAtMs > ticket.capturedAtMs ||
            ticket.capturedAtMs - turn.capturedAtMs > 1_600L)
            return reject("Нет свежего визуального признака активных кнопок")
        val controls = p.zones.filter { it.role.name.startsWith("HERO_") }
        val cues = mutableListOf<ActionStripCue>()
        for (zone in controls) {
            val lane = lane(zone.role) ?: continue
            val found = lines.filter { it.box.valid() && it.text.length <= 80 &&
                it.box.left > zone.box.left && it.box.right < zone.box.right &&
                it.box.top > zone.box.top && it.box.bottom < zone.box.bottom }
            // Multiple OCR lines in one control could include an amount or obscured label:
            // do not guess which one the recognizer meant.
            if (found.size > 1) return reject("Несколько надписей внутри одной кнопки")
            val line = found.singleOrNull() ?: continue
            val action = label(lane, line.text) ?: return reject("Неподтверждённая надпись игровой кнопки")
            val expectedRole = when (lane) {
                HeroControlLane.FOLD -> VisibleButtonRole.RED_FOLD
                HeroControlLane.CHECK_CALL -> VisibleButtonRole.GREEN_CHECK_OR_CALL
                HeroControlLane.BET_RAISE -> VisibleButtonRole.GOLD_BET_OR_RAISE
            }
            if (expectedRole !in turn.roles) return reject("OCR противоречит цвету/состоянию кнопки")
            cues.add(ActionStripCue(lane, action, zone.box, line.text))
        }
        val ordered = cues.sortedBy { it.lane.ordinal }
        // A single word is not reliable enough to identify the control strip.
        if (ordered.size < 2 || ordered.none { it.lane == HeroControlLane.FOLD } ||
            ordered.none { it.lane == HeroControlLane.CHECK_CALL }) {
            previous = ordered
            lastTicket = ticket
            return ActionStripReport(ActionStripStatus.COLLECTING, "Недостаточно отдельных кнопок", ticket.frameSequence, ordered)
        }
        val equal = old != null &&
            ticket.capturedAtMs - old.capturedAtMs in 100L..maxGapMs &&
            ordered.map { it.lane to it.action } == previous.map { it.lane to it.action }
        lastTicket = ticket
        previous = ordered
        return ActionStripReport(if (equal) ActionStripStatus.STABLE_DIAGNOSTIC else ActionStripStatus.COLLECTING,
            if (equal) "Надписи совпали в двух свежих кадрах; идентификатор раздачи не установлен"
            else "Ожидается независимый повторный кадр", ticket.frameSequence,
            ordered, if (equal) ordered else emptyList())
    }
}

object OnDeviceActionStripDiagnostics {
    private val observer = ActionControlDiagnostics()
    @Synchronized fun configure(p: RealFrameCalibration): Boolean = observer.configure(p)
    @Synchronized fun clear() = observer.clear()
    @Synchronized fun resetSession() = observer.resetSession()
    @Synchronized fun configured(): Boolean = observer.configured()
    @Synchronized fun inspect(ticket: OcrFrameTicket, w: Int, h: Int, nowMs: Long,
        lines: List<PositionedOcrLine>, turn: ConfirmedTurnDisplay?, stopped: Boolean): ActionStripReport =
        observer.inspect(ticket, w, h, nowMs, lines, turn, stopped)
}
) ||
                            Regex("[0-9][.,][0-9]").containsMatchIn(raw)) }
                    ?.let { ExactActionCandidate.CALL }
            }
            HeroControlLane.BET_RAISE -> when (text) {
                "bet", "ставка", "поставить" -> ExactActionCandidate.BET
                "raise", "рейз", "повысить" -> ExactActionCandidate.RAISE
                else -> null
            }
        }
    }

    @Synchronized fun inspect(ticket: OcrFrameTicket, frameWidth: Int, frameHeight: Int,
        nowMs: Long, lines: List<PositionedOcrLine>, turn: ConfirmedTurnDisplay?, stopped: Boolean
    ): ActionStripReport {
        val p = profile ?: return ActionStripReport(ActionStripStatus.NOT_CALIBRATED, "Нет измеренных областей кнопок")
        fun reject(reason: String): ActionStripReport {
            resetSession()
            return ActionStripReport(ActionStripStatus.REJECTED, reason)
        }
        if (stopped || ticket.sourcePackage != p.expectedPackage || frameWidth != p.frameWidth ||
            frameHeight != p.frameHeight || ticket.captureEpoch < 0 || ticket.windowGeneration < 0 ||
            ticket.frameSequence <= 0 || ticket.capturedAtMs < 0 || nowMs < ticket.capturedAtMs ||
            nowMs - ticket.capturedAtMs > maxFrameAgeMs || lines.size > 100)
            return reject("STOP, окно, размеры, время или OCR недостоверны")
        val old = lastTicket
        if (old != null && (old.captureEpoch != ticket.captureEpoch ||
            old.windowGeneration != ticket.windowGeneration || old.sourcePackage != ticket.sourcePackage ||
            ticket.frameSequence <= old.frameSequence || ticket.capturedAtMs <= old.capturedAtMs))
            return reject("Сменился сеанс/окно или повторился кадр")
        // The visual cue is intentionally *not* independent proof of a player's turn.
        if (turn == null || turn.state != TurnDisplayState.READY ||
            turn.captureEpoch != ticket.captureEpoch || turn.capturedAtMs > ticket.capturedAtMs ||
            ticket.capturedAtMs - turn.capturedAtMs > 1_600L)
            return reject("Нет свежего визуального признака активных кнопок")
        val controls = p.zones.filter { it.role.name.startsWith("HERO_") }
        val cues = mutableListOf<ActionStripCue>()
        for (zone in controls) {
            val lane = lane(zone.role) ?: continue
            val found = lines.filter { it.box.valid() && it.text.length <= 80 &&
                it.box.left > zone.box.left && it.box.right < zone.box.right &&
                it.box.top > zone.box.top && it.box.bottom < zone.box.bottom }
            // Multiple OCR lines in one control could include an amount or obscured label:
            // do not guess which one the recognizer meant.
            if (found.size > 1) return reject("Несколько надписей внутри одной кнопки")
            val line = found.singleOrNull() ?: continue
            val action = label(lane, line.text) ?: return reject("Неподтверждённая надпись игровой кнопки")
            val expectedRole = when (lane) {
                HeroControlLane.FOLD -> VisibleButtonRole.RED_FOLD
                HeroControlLane.CHECK_CALL -> VisibleButtonRole.GREEN_CHECK_OR_CALL
                HeroControlLane.BET_RAISE -> VisibleButtonRole.GOLD_BET_OR_RAISE
            }
            if (expectedRole !in turn.roles) return reject("OCR противоречит цвету/состоянию кнопки")
            cues.add(ActionStripCue(lane, action, zone.box, line.text))
        }
        val ordered = cues.sortedBy { it.lane.ordinal }
        // A single word is not reliable enough to identify the control strip.
        if (ordered.size < 2 || ordered.none { it.lane == HeroControlLane.FOLD } ||
            ordered.none { it.lane == HeroControlLane.CHECK_CALL }) {
            previous = ordered
            lastTicket = ticket
            return ActionStripReport(ActionStripStatus.COLLECTING, "Недостаточно отдельных кнопок", ticket.frameSequence, ordered)
        }
        val equal = old != null &&
            ticket.capturedAtMs - old.capturedAtMs in 100L..maxGapMs &&
            ordered.map { it.lane to it.action } == previous.map { it.lane to it.action }
        lastTicket = ticket
        previous = ordered
        return ActionStripReport(if (equal) ActionStripStatus.STABLE_DIAGNOSTIC else ActionStripStatus.COLLECTING,
            if (equal) "Надписи совпали в двух свежих кадрах; идентификатор раздачи не установлен"
            else "Ожидается независимый повторный кадр", ticket.frameSequence,
            ordered, if (equal) ordered else emptyList())
    }
}

object OnDeviceActionStripDiagnostics {
    private val observer = ActionControlDiagnostics()
    @Synchronized fun configure(p: RealFrameCalibration): Boolean = observer.configure(p)
    @Synchronized fun clear() = observer.clear()
    @Synchronized fun resetSession() = observer.resetSession()
    @Synchronized fun configured(): Boolean = observer.configured()
    @Synchronized fun inspect(ticket: OcrFrameTicket, w: Int, h: Int, nowMs: Long,
        lines: List<PositionedOcrLine>, turn: ConfirmedTurnDisplay?, stopped: Boolean): ActionStripReport =
        observer.inspect(ticket, w, h, nowMs, lines, turn, stopped)
}
