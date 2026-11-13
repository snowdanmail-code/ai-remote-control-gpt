package com.example.airemote

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import kotlin.math.abs

enum class OneWinTableExitPhase { OFF, WATCHING, OPEN_PENDING, WAITING_MENU,
    LEAVE_PENDING, WAITING_CONFIRMATION, CONFIRM_PENDING, WAITING_LOBBY,
    RETURNED, BLOCKED }
enum class OneWinTableExitTap { OPEN_MENU, LEAVE_GAME, CONFIRM_YES }
data class OneWinTableExitTicket(val action: OneWinTableExitTap, val sourcePackage: String,
    val epoch: Long, val window: Long, val observedAtMs: Long,
    val target: NormalizedTextBox?, val id: Long)
data class OneWinTableExitStatus(val phase: OneWinTableExitPhase,
    val message: String, val occupied: Int? = null)

/**
 * Opt-in, non-wager short-handed exit. Nothing is inferred from missing OCR.
 * Requires all measured seat labels, positively identified hero and a
 * separately VERIFIED WAITING/FINISHED stage. No device verifier is bundled.
 */
object OnDeviceOneWinTableAutoExit {
    private val gate = OneWinTableExitEvidenceGate()
    private val recheck = OneWinExitRecognitionRetryPolicy()
    private var phase = OneWinTableExitPhase.OFF
    private var heroSeat = -1
    private var token = 0L
    private var pending: OneWinTableExitTicket? = null
    private var sinceMs = 0L
    private var lastProbe = -1_300L
    private var probing = false
    private var menuCandidate: Pair<Long, NormalizedTextBox>? = null
    private var menuCandidateWindow = -1L
    private var confirmCandidate: Pair<Long, OneWinExitConfirmationDialog>? = null
    private var confirmCandidateWindow = -1L
    private var lobbyCandidateAtMs = -1L
    private var lobbyCandidateWindow = -1L
    private var confirmedLeaveBox: NormalizedTextBox? = null
    private var confirmedYesBox: NormalizedTextBox? = null
    private var source = ""
    private var epoch = -1L
    private var window = -1L
    private var lastOccupied: Int? = null
    @Volatile private var state = OneWinTableExitStatus(
        OneWinTableExitPhase.OFF, "Контроль выхода отключён")

    @Synchronized fun status(): OneWinTableExitStatus = state
    @Synchronized fun active(): Boolean = phase !in setOf(
        OneWinTableExitPhase.OFF, OneWinTableExitPhase.BLOCKED,
        OneWinTableExitPhase.RETURNED)
    @Synchronized fun needsScreenProbe(): Boolean =
        phase == OneWinTableExitPhase.WAITING_MENU ||
        phase == OneWinTableExitPhase.WAITING_CONFIRMATION ||
        phase == OneWinTableExitPhase.WAITING_LOBBY

    private fun update(next: OneWinTableExitPhase, reason: String) {
        phase = next
        state = OneWinTableExitStatus(next, reason, lastOccupied)
    }

    @Synchronized fun stop(reason: String = "Отключено оператором") {
        token++
        pending = null
        heroSeat = -1
        sinceMs = 0L
        lastProbe = -1_300L
        probing = false
        menuCandidate = null
        menuCandidateWindow = -1L
        confirmCandidate = null
        confirmCandidateWindow = -1L
        lobbyCandidateAtMs = -1L
        lobbyCandidateWindow = -1L
        recheck.reset()
        confirmedLeaveBox = null
        confirmedYesBox = null
        gate.reset()
        lastOccupied = null
        source = ""
        epoch = -1L
        window = -1L
        update(OneWinTableExitPhase.OFF, reason)
    }

    @Synchronized fun arm(context: Context, selectedHeroSeat: Int): Boolean {
        if (active() || selectedHeroSeat !in 0..9 || !FrameStore.isCapturing ||
            OperatorStopStore.isStopped(context) ||
            CaptureObservationMode.isSpectator() ||
            !LiveGamePermissionGate.permitted() ||
            LocalPlayerCheckPilot.isActive() ||
            OnDeviceLiveActionControl.guard.hasUnconfirmedAction() ||
            OnDeviceCashLobbySelection.scanning()) return false
        val layout = OnDeviceRealFrameRegions.activeProfile() ?: return false
        val positions = layout.zones.filter { it.role == RealFieldRole.SEAT_ACTION }
            .mapNotNull { it.seatIndex }.toSet()
        val stacks = layout.zones.filter { it.role == RealFieldRole.SEAT_STACK }
            .mapNotNull { it.seatIndex }.toSet()
        val measuredBottomSeat = OneWinPortraitHeroSeat.inferBottomSeat(layout)
        if (!layout.valid() || positions != stacks || selectedHeroSeat !in positions ||
            // The real six-seat interface rotates chairs to show HERO at
            // screen bottom. Never trust a manually entered mismatched seat.
            selectedHeroSeat != measuredBottomSeat ||
            positions.size !in 2..10 ||
            LiveGamePermissionGate.packageName() != layout.expectedPackage) return false
        stop("Новая сессия")
        heroSeat = selectedHeroSeat
        update(OneWinTableExitPhase.WATCHING, "Ожидаем достоверное число игроков и окончание раздачи")
        return true
    }

    private fun seats(report: RegionObservationReport,
                      layout: RealFrameCalibration): Map<Int, OneWinSeatPresence>? {
        if (report.status != RegionObservationStatus.OBSERVED) return null
        val ids = layout.zones.filter { it.role == RealFieldRole.SEAT_ACTION }
            .mapNotNull { it.seatIndex }.toSet()
        if (ids.size !in 2..10 ||
            layout.zones.filter { it.role == RealFieldRole.SEAT_STACK }
                .mapNotNull { it.seatIndex }.toSet() != ids) return null
        val result = mutableMapOf<Int, OneWinSeatPresence>()
        for (seat in ids) {
            val stack = report.fields.filter {
                it.role == RealFieldRole.SEAT_STACK && it.seatIndex == seat
            }
            val action = report.fields.filter {
                it.role == RealFieldRole.SEAT_ACTION && it.seatIndex == seat
            }
            val occupied = stack.size == 1 &&
                ((stack.single().amountMinorUnits ?: 0L) > 0L ||
                 (stack.single().numericValue ?: 0L) > 0L)
            val empty = action.size == 1 && action.single().text.trim().lowercase() in
                setOf("сесть", "занять место", "свободно", "пусто",
                    "sit", "sit here", "empty")
            if (occupied == empty) return null
            result[seat] = if (occupied) OneWinSeatPresence.OCCUPIED
                else OneWinSeatPresence.EMPTY
        }
        return result
    }

    /** Called after exact source/epoch-checked ML Kit OCR publication. */
    @Synchronized fun observeSeats(context: Context, ticket: OcrFrameTicket,
                                   report: RegionObservationReport, nowMs: Long) {
        if (phase != OneWinTableExitPhase.WATCHING) return
        val layout = OnDeviceRealFrameRegions.activeProfile() ?: return
        val origin = FrameStore.windowOrigin ?: return
        if (!origin.safeForLocalDiagnostics ||
            origin.observedPackage != layout.expectedPackage ||
            origin.windowGeneration != ticket.windowGeneration ||
            ticket.sourcePackage != layout.expectedPackage ||
            ticket.captureEpoch != FrameStore.captureEpoch ||
            layout.frameWidth != context.resources.displayMetrics.widthPixels ||
            layout.frameHeight != context.resources.displayMetrics.heightPixels) return
        val observation = seats(report, layout)?.let {
            OneWinExitSeatObservation(ticket.sourcePackage, ticket.captureEpoch,
                ticket.windowGeneration, ticket.frameSequence, ticket.capturedAtMs,
                heroSeat, it, layout.zones.count { zone -> zone.role == RealFieldRole.SEAT_ACTION })
        }
        lastOccupied = observation?.occupied
        val ready = gate.inspect(observation, OnDeviceHandState.snapshot(nowMs), nowMs,
            OperatorStopStore.isStopped(context) || CaptureObservationMode.isSpectator(),
            OnDeviceLiveActionControl.guard.hasUnconfirmedAction() ||
                LocalPlayerCheckPilot.isActive() ||
                OnDeviceAutonomousRuntime.status().state == AutoLoopState.WAITING_FOR_RESULT)
        if (!ready) {
            state = OneWinTableExitStatus(phase,
                if (observation == null) "Неполная разметка мест; выход запрещён"
                else "Занято: " + observation.occupied + "; ждём завершения раздачи",
                lastOccupied)
            return
        }
        source = ticket.sourcePackage
        epoch = ticket.captureEpoch
        window = ticket.windowGeneration
        submit(context, OneWinTableExitTap.OPEN_MENU, nowMs, null)
    }

    private fun submit(context: Context, action: OneWinTableExitTap,
                       observedAt: Long, area: NormalizedTextBox?) {
        val svc = LiveForegroundProbe.activeService()
        if (svc == null) {
            update(OneWinTableExitPhase.BLOCKED, "Нет службы Accessibility")
            return
        }
        token++
        val t = OneWinTableExitTicket(action, source, epoch, window,
            observedAt, area, token)
        pending = t
        sinceMs = SystemClock.elapsedRealtime()
        update(when (action) {
            OneWinTableExitTap.OPEN_MENU -> OneWinTableExitPhase.OPEN_PENDING
            OneWinTableExitTap.LEAVE_GAME -> OneWinTableExitPhase.LEAVE_PENDING
            OneWinTableExitTap.CONFIRM_YES -> OneWinTableExitPhase.CONFIRM_PENDING
        }, "Ожидаем касание: " + action.name)
        svc.offerOneWinTableExitNavigation(t) { complete ->
            synchronized(this) {
                if (pending != t) return@synchronized
                pending = null
                menuCandidate = null
                menuCandidateWindow = -1L
                confirmCandidate = null
                confirmCandidateWindow = -1L
                lobbyCandidateAtMs = -1L
                lobbyCandidateWindow = -1L
                recheck.reset()
                sinceMs = SystemClock.elapsedRealtime()
                if (!complete) update(OneWinTableExitPhase.BLOCKED,
                    "Касание отклонено; автоматический повтор запрещён")
                else update(when (action) {
                    OneWinTableExitTap.OPEN_MENU -> OneWinTableExitPhase.WAITING_MENU
                    OneWinTableExitTap.LEAVE_GAME -> OneWinTableExitPhase.WAITING_CONFIRMATION
                    OneWinTableExitTap.CONFIRM_YES -> OneWinTableExitPhase.WAITING_LOBBY
                }, when (action) {
                    OneWinTableExitTap.OPEN_MENU ->
                        "Проверяем «Покинуть игру» на двух кадрах"
                    OneWinTableExitTap.LEAVE_GAME ->
                        "Ожидаем окно «Вы уверены, что хотите закрыть стол?» и кнопку «Да»"
                    OneWinTableExitTap.CONFIRM_YES ->
                        "Подтверждение отправлено; проверяем лобби на двух кадрах"
                })
            }
        }
    }

    @Synchronized fun ticketMatches(t: OneWinTableExitTicket, nowMs: Long): Boolean {
        if (pending != t || t.sourcePackage != source ||
            t.epoch != epoch || t.window != window ||
            nowMs < t.observedAtMs || nowMs - t.observedAtMs > 1_100L)
            return false
        return when (t.action) {
            OneWinTableExitTap.OPEN_MENU ->
                phase == OneWinTableExitPhase.OPEN_PENDING &&
                    OnDeviceHandState.snapshot(nowMs).let {
                        it.trustworthyNow &&
                            it.stage in setOf(HandStage.WAITING, HandStage.FINISHED)
                    }
            OneWinTableExitTap.LEAVE_GAME ->
                phase == OneWinTableExitPhase.LEAVE_PENDING &&
                    t.target != null && t.target == confirmedLeaveBox
            OneWinTableExitTap.CONFIRM_YES ->
                phase == OneWinTableExitPhase.CONFIRM_PENDING &&
                    t.target != null && t.target == confirmedYesBox
        }
    }

    @Synchronized fun onCaptureTick(context: Context, nowMs: Long) {
        if (!active()) return
        if (OperatorStopStore.isStopped(context) || !FrameStore.isCapturing ||
            CaptureObservationMode.isSpectator() || !LiveGamePermissionGate.permitted()) {
            stop("STOP / нет захвата или разрешения")
            return
        }
        // Never abandon a screen merely because its text is temporarily
        // unreadable: continue on fresh frames with adaptive OCR backoff.
        // Only an Android touch still awaiting a callback has a hard timeout.
        if (phase in setOf(OneWinTableExitPhase.OPEN_PENDING,
                OneWinTableExitPhase.LEAVE_PENDING,
                OneWinTableExitPhase.CONFIRM_PENDING) &&
            sinceMs > 0 && nowMs - sinceMs > 12_000L) {
            pending = null
            update(OneWinTableExitPhase.BLOCKED,
                "Нет подтверждения жеста Android; касание не повторяем вслепую")
        }
    }

    private fun inconclusive(atMs: Long, what: String) {
        recheck.observe(atMs, false)
        state = OneWinTableExitStatus(phase,
            what + "; повторно читаем новый кадр (попытка #" +
                recheck.inconclusiveFrames + ")", lastOccupied)
    }

    private fun firstMatch(atMs: Long, what: String) {
        recheck.observe(atMs, true)
        state = OneWinTableExitStatus(phase,
            what + "; ожидаем ещё одно совпадающее наблюдение", lastOccupied)
    }

    fun offerScreen(context: Context, image: Bitmap,
                    origin: WindowOriginAssessment, atMs: Long,
                    rootStillSame: () -> Boolean) {
        val session: Long
        synchronized(this) {
            if (!needsScreenProbe() || probing || atMs - lastProbe < 1_300L ||
                !recheck.mayRead(atMs) ||
                !origin.safeForLocalDiagnostics || origin.observedPackage != source ||
                origin.captureEpoch != epoch || origin.windowGeneration == null) return
            session = token
            probing = true
            lastProbe = atMs
        }
        val data = runCatching {
            ByteArrayOutputStream().use {
                check(image.compress(Bitmap.CompressFormat.JPEG, 88, it))
                it.toByteArray()
            }
        }.getOrNull()
        if (data == null) {
            synchronized(this) {
                if (session == token) {
                    probing = false
                    recheck.observe(atMs, false)
                    state = OneWinTableExitStatus(phase,
                        "Кадр не удалось подготовить, повторная проверка #" +
                            recheck.inconclusiveFrames, lastOccupied)
                }
            }
            return
        }
        OnDeviceCashLobbyTextRecognition.inspectStructured(context, data) { outcome ->
            val now = SystemClock.elapsedRealtime()
            synchronized(this) {
                if (session != token) return@inspectStructured
                probing = false
                if (!rootStillSame() || !FrameStore.isCapturing ||
                    OperatorStopStore.isStopped(context) ||
                    now - atMs !in 0..6_000L) {
                    menuCandidate = null
                    confirmCandidate = null
                    lobbyCandidateAtMs = -1L
                    return@inspectStructured
                }
                val lines = outcome.getOrNull()?.lines
                if (lines == null) {
                    inconclusive(atMs, "Ошибка OCR")
                    return@inspectStructured
                }
                val observedWindow = requireNotNull(origin.windowGeneration)
                if (phase == OneWinTableExitPhase.WAITING_MENU) {
                    val box = OneWinTableExitMenuParser.leaveButton(lines)
                    if (box == null) {
                        if (atMs - (menuCandidate?.first ?: atMs) > 6_000L) {
                            menuCandidate = null
                            menuCandidateWindow = -1L
                        }
                        inconclusive(atMs, "Меню ещё не распознано")
                        return@inspectStructured
                    }
                    val old = menuCandidate
                    if (old != null && menuCandidateWindow == observedWindow &&
                        atMs - old.first in 300L..6_000L &&
                        abs(old.second.centerX() - box.centerX()) <= 15 &&
                        abs(old.second.centerY() - box.centerY()) <= 15) {
                        recheck.observe(atMs, true)
                        confirmedLeaveBox = box
                        menuCandidate = null
                        menuCandidateWindow = -1L
                        window = observedWindow
                        submit(context, OneWinTableExitTap.LEAVE_GAME, now, box)
                    } else {
                        menuCandidate = atMs to box
                        menuCandidateWindow = observedWindow
                        firstMatch(atMs, "Обнаружено меню выхода")
                    }
                } else if (phase == OneWinTableExitPhase.WAITING_CONFIRMATION) {
                    val dialog = OneWinTableExitMenuParser.confirmationDialog(lines)
                    if (dialog == null) {
                        if (atMs - (confirmCandidate?.first ?: atMs) > 6_000L) {
                            confirmCandidate = null
                            confirmCandidateWindow = -1L
                        }
                        inconclusive(atMs, "Диалог подтверждения ещё не распознан")
                        return@inspectStructured
                    }
                    val old = confirmCandidate
                    if (old != null && confirmCandidateWindow == observedWindow &&
                        atMs - old.first in 300L..6_000L &&
                        abs(old.second.yesBox.centerX() - dialog.yesBox.centerX()) <= 15 &&
                        abs(old.second.yesBox.centerY() - dialog.yesBox.centerY()) <= 15 &&
                        abs(old.second.noBox.centerX() - dialog.noBox.centerX()) <= 15 &&
                        abs(old.second.noBox.centerY() - dialog.noBox.centerY()) <= 15) {
                        recheck.observe(atMs, true)
                        confirmedYesBox = dialog.yesBox
                        confirmCandidate = null
                        confirmCandidateWindow = -1L
                        window = observedWindow
                        submit(context, OneWinTableExitTap.CONFIRM_YES, now, dialog.yesBox)
                    } else {
                        confirmCandidate = atMs to dialog
                        confirmCandidateWindow = observedWindow
                        firstMatch(atMs, "Обнаружено окно подтверждения выхода")
                    }
                } else if (phase == OneWinTableExitPhase.WAITING_LOBBY) {
                    if (OneWinTableExitMenuParser.lobbyVisible(lines)) {
                        if (lobbyCandidateAtMs >= 0L &&
                            lobbyCandidateWindow == observedWindow &&
                            atMs - lobbyCandidateAtMs in 300L..6_000L) {
                            recheck.observe(atMs, true)
                            update(OneWinTableExitPhase.RETURNED,
                                "Экран лобби подтверждён двумя OCR-кадрами")
                            gate.reset()
                        } else {
                            lobbyCandidateAtMs = atMs
                            lobbyCandidateWindow = observedWindow
                            firstMatch(atMs, "Найден экран лобби")
                        }
                    } else {
                        if (atMs - lobbyCandidateAtMs > 6_000L) {
                            lobbyCandidateAtMs = -1L
                            lobbyCandidateWindow = -1L
                        }
                        inconclusive(atMs, "Возврат в лобби пока не подтверждён")
                    }
                }
            }
        }
    }
}
