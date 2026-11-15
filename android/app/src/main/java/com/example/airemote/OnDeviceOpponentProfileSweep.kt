package com.example.airemote

import android.content.Context
import android.os.SystemClock

/**
 * The independently calibrated AVATAR -> POPUP -> TABLE-DISMISS navigation.
 * This is a separate UI-observation workflow, never a poker betting action.
 * It does not guess player identities from seat numbers or merge the resulting
 * popup into the verified-hand SQLite learner.
 */
enum class OpponentProfileSweepPhase {
    OFF, WAITING_TABLE, OPEN_SUBMITTED, WAITING_POPUP, READING_POPUP,
    DISMISS_SUBMITTED, WAITING_TABLE_RETURN, COMPLETE, BLOCKED
}
enum class OpponentProfileNavPurpose { OPEN_AVATAR, DISMISS_ON_TABLE }

data class OpponentProfileTapTicket(
    val purpose: OpponentProfileNavPurpose,
    val expectedPackage: String,
    val captureEpoch: Long,
    val windowGeneration: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val capturedAtMs: Long,
    val seatIndex: Int,
    val area: NormalizedTextBox,
    val sequence: Long
)

data class ObservedOpponentProfile(
    val seatIndex: Int,
    val displayedNickname: String?,
    val metrics: List<OpponentPopupMetricValue>,
    val source: String = "READ_ONLY_UNLINKED_SEAT"
)

data class OpponentProfileSweepStatus(
    val phase: OpponentProfileSweepPhase,
    val description: String,
    val results: List<ObservedOpponentProfile>
)

/** No generic gesture API, no hard-coded or screenshot-derived tappable points. */
object OnDeviceOpponentProfileSweep {
    private var plan: RealFrameCalibration? = null
    private var pendingSeats: List<RealFrameZone> = emptyList()
    private var current: RealFrameZone? = null
    private var pendingTicket: OpponentProfileTapTicket? = null
    private var epoch: Long = -1L
    private var window: Long = -1L
    private var sequence = 0L
    private var sinceMs = 0L
    private var surfaceAtMs = 0L
    private var surfaceState = RecordedSurfaceStatus.UNKNOWN
    private var results = mutableListOf<ObservedOpponentProfile>()
    private var phase = OpponentProfileSweepPhase.OFF
    @Volatile private var report = OpponentProfileSweepStatus(
        OpponentProfileSweepPhase.OFF, "Обход аватаров не запущен", emptyList())

    @Synchronized fun status(): OpponentProfileSweepStatus = report

    private fun status(phaseNext: OpponentProfileSweepPhase, message: String) {
        phase = phaseNext
        report = OpponentProfileSweepStatus(phase, message, results.toList())
    }

    @Synchronized fun reset(reason: String = "STOP") {
        plan = null
        pendingSeats = emptyList()
        current = null
        pendingTicket = null
        epoch = -1
        window = -1
        sequence++
        sinceMs = 0L
        surfaceAtMs = 0L
        surfaceState = RecordedSurfaceStatus.UNKNOWN
        results.clear()
        status(OpponentProfileSweepPhase.OFF, reason)
    }

    /**
     * Exact one-shot proof for the service-side gesture path. A newly read
     * PANEL_VISIBLE frame suffices for a safe-dismiss tap even if text OCR
     * failed; the popup must not remain stuck behind an unreadable label.
     */
    @Synchronized fun navigationSurfaceMatches(
        ticket: OpponentProfileTapTicket, nowMs: Long
    ): Boolean {
        val expectedState = if (ticket.purpose == OpponentProfileNavPurpose.OPEN_AVATAR)
            RecordedSurfaceStatus.TABLE_LIKE else RecordedSurfaceStatus.PANEL_VISIBLE
        val expectedPhase = if (ticket.purpose == OpponentProfileNavPurpose.OPEN_AVATAR)
            OpponentProfileSweepPhase.OPEN_SUBMITTED else OpponentProfileSweepPhase.DISMISS_SUBMITTED
        return phase == expectedPhase && pendingTicket == ticket &&
            current?.seatIndex == ticket.seatIndex && epoch == ticket.captureEpoch &&
            window == ticket.windowGeneration && surfaceState == expectedState &&
            surfaceAtMs >= ticket.capturedAtMs &&
            nowMs >= surfaceAtMs && nowMs - surfaceAtMs <= 900L
    }

    /** One explicit session start: all subsequently measured seats are visited automatically. */
    @Synchronized fun start(context: Context): Boolean {
        if (phase !in setOf(OpponentProfileSweepPhase.OFF,
                OpponentProfileSweepPhase.COMPLETE, OpponentProfileSweepPhase.BLOCKED) ||
            OperatorStopStore.isStopped(context) || !FrameStore.isCapturing ||
            CaptureObservationMode.isSpectator() || !LiveGamePermissionGate.permitted() ||
            LocalPlayerCheckPilot.isActive() ||
            OnDeviceOneWinTableAutoExit.active() ||
            OnDeviceLiveActionControl.guard.hasUnconfirmedAction() ||
            OnDeviceAutonomousRuntime.status().state == AutoLoopState.WAITING_FOR_RESULT)
            return false
        // A readable table is not necessarily OUR seated table. Require
        // the independently verified post-buy-in HERO identity first.
        val origin = FrameStore.windowOrigin ?: return false
        if (!OnDeviceOneWinPreSeatScanner.verifiedSeatedIn(origin)) return false
        val calibration = OnDeviceRealFrameRegions.activeProfile() ?: return false
        // Six-seat 1win rotates the view: HERO is the bottom screen slot,
        // NOT a fixed physical chair index. Never open our own avatar.
        val heroBottomSeat = OneWinPortraitHeroSeat.inferBottomSeat(calibration)
            ?: return false
        val avatars = calibration.zones.filter {
            it.role == RealFieldRole.SEAT_AVATAR &&
                it.seatIndex != null && it.seatIndex != heroBottomSeat
        }.sortedBy { it.seatIndex }
        if (!calibration.valid() || avatars.isEmpty() ||
            calibration.zones.count { it.role == RealFieldRole.PROFILE_DISMISS } != 1 ||
            LiveGamePermissionGate.packageName() != calibration.expectedPackage) return false
        reset("Начат новый сеанс чтения профилей")
        plan = calibration
        pendingSeats = avatars
        status(OpponentProfileSweepPhase.WAITING_TABLE,
            "Ждём стол 1win и безопасный момент без активного хода")
        return true
    }

    /** No avatar tap while our game turn, modal, ambiguous window or STOP is present. */
    private fun safeToOpen(context: Context, nowMs: Long): Boolean {
        if (OperatorStopStore.isStopped(context) ||
            OnDeviceLiveActionControl.guard.hasUnconfirmedAction() ||
            LocalPlayerCheckPilot.isActive() ||
            OnDeviceAutonomousRuntime.status().state == AutoLoopState.WAITING_FOR_RESULT)
            return false
        val turn = FrameStore.turnConsensus.current(nowMs) ?: return false
        // PREACTION controls may trigger as soon as the turn rotates.
        // Only a positively observed WAITING state is safe for profile UI.
        return turn.state == TurnDisplayState.WAITING
    }

    @Synchronized fun observe(
        context: Context, surface: RecordedSurfaceStatus,
        origin: WindowOriginAssessment, width: Int, height: Int, nowMs: Long
    ) {
        val profile = plan ?: return
        if (phase in setOf(OpponentProfileSweepPhase.COMPLETE,
                OpponentProfileSweepPhase.OFF, OpponentProfileSweepPhase.BLOCKED)) return
        if (OperatorStopStore.isStopped(context) || !FrameStore.isCapturing ||
            !LiveGamePermissionGate.permitted() ||
            origin.observedPackage != profile.expectedPackage ||
            origin.status != WindowOriginStatus.EXTERNAL_PACKAGE_STABLE ||
            origin.windowGeneration == null ||
            width != profile.frameWidth || height != profile.frameHeight) {
            // Unknown/transition frames may be transient while the operator
            // returns from the app. Never issue a gesture during uncertainty.
            return
        }
        if (epoch < 0) {
            epoch = origin.captureEpoch
            window = origin.windowGeneration
        }
        if (epoch != origin.captureEpoch || window != origin.windowGeneration) {
            status(OpponentProfileSweepPhase.BLOCKED, "Сменилось окно или сеанс захвата")
            return
        }
        surfaceState = surface
        surfaceAtMs = nowMs
        if (sinceMs > 0 && nowMs - sinceMs > 8_000L &&
            phase !in setOf(OpponentProfileSweepPhase.WAITING_TABLE,
                OpponentProfileSweepPhase.COMPLETE)) {
            if (phase in setOf(OpponentProfileSweepPhase.WAITING_POPUP,
                    OpponentProfileSweepPhase.READING_POPUP,
                    OpponentProfileSweepPhase.WAITING_TABLE_RETURN)) {
                // An unreadable popup is not evidence that it failed to open.
                // Continue to observe fresh OCR/surface frames. Never dismiss
                // unseen statistics, re-tap the avatar or promote unknown data.
                sinceMs = nowMs
                status(phase, "Распознавание окна пока не подтвердилось; " +
                    "повторно проверяем свежие кадры без новых касаний")
            } else {
                // Dispatch callback itself never returned; repeating a gesture
                // would be unsafe. This is NOT an OCR recognition timeout.
                status(OpponentProfileSweepPhase.BLOCKED,
                    "Нет подтверждения навигационного жеста Android")
                return
            }
        }
        when (phase) {
            OpponentProfileSweepPhase.WAITING_TABLE -> {
                if (surface != RecordedSurfaceStatus.TABLE_LIKE || !safeToOpen(context, nowMs)) return
                val avatar = pendingSeats.firstOrNull() ?: run {
                    status(OpponentProfileSweepPhase.COMPLETE, "Обход измеренных аватаров завершён")
                    return
                }
                current = avatar
                send(context, OpponentProfileNavPurpose.OPEN_AVATAR, avatar.box, origin, profile, nowMs)
            }
            OpponentProfileSweepPhase.WAITING_POPUP -> {
                if (surface == RecordedSurfaceStatus.PANEL_VISIBLE) {
                    sinceMs = nowMs
                    status(OpponentProfileSweepPhase.READING_POPUP,
                        "Открыта панель; ждём два совпадающих OCR-кадра")
                }
            }
            OpponentProfileSweepPhase.READING_POPUP -> {
                if (surface != RecordedSurfaceStatus.PANEL_VISIBLE) {
                    // Transient animation/occlusion can hide a known panel for
                    // one frame. Do not abort the OCR stage on a single miss.
                    status(phase, "Панель временно не видна; повторно проверяем экран")
                    return
                }
                val snapshot = OnDeviceOpponentPopupOcr.latest()
                if (snapshot != null && snapshot.captureEpoch == epoch &&
                    snapshot.windowGeneration == window &&
                    snapshot.sourcePackage == profile.expectedPackage &&
                    snapshot.confirmedAtMs >= sinceMs &&
                    current != null) {
                    val seat = requireNotNull(current).seatIndex
                    results.add(ObservedOpponentProfile(
                        requireNotNull(seat), snapshot.displayedNickname, snapshot.metrics))
                    val dismiss = profile.zones.single { it.role == RealFieldRole.PROFILE_DISMISS }
                    send(context, OpponentProfileNavPurpose.DISMISS_ON_TABLE,
                        dismiss.box, origin, profile, nowMs)
                }
            }
            OpponentProfileSweepPhase.WAITING_TABLE_RETURN -> {
                if (surface == RecordedSurfaceStatus.TABLE_LIKE) {
                    val seat = current?.seatIndex
                    pendingSeats = pendingSeats.filterNot { it.seatIndex == seat }
                    current = null
                    sinceMs = 0L
                    status(OpponentProfileSweepPhase.WAITING_TABLE,
                        "Профиль закрыт; ожидаем безопасный стол для следующего соперника")
                }
            }
            else -> Unit
        }
    }

    private fun send(context: Context, purpose: OpponentProfileNavPurpose,
                     box: NormalizedTextBox, origin: WindowOriginAssessment,
                     profile: RealFrameCalibration, nowMs: Long) {
        val seat = current?.seatIndex ?: return
        val svc = LiveForegroundProbe.activeService() ?: run {
            status(OpponentProfileSweepPhase.BLOCKED, "Accessibility отключена")
            return
        }
        sequence++
        val ticket = OpponentProfileTapTicket(purpose, profile.expectedPackage,
            origin.captureEpoch, requireNotNull(origin.windowGeneration),
            profile.frameWidth, profile.frameHeight, nowMs, seat, box, sequence)
        pendingTicket = ticket
        sinceMs = nowMs
        status(if (purpose == OpponentProfileNavPurpose.OPEN_AVATAR)
            OpponentProfileSweepPhase.OPEN_SUBMITTED else OpponentProfileSweepPhase.DISMISS_SUBMITTED,
            "Передаём только навигационное нажатие ${purpose.name}, место $seat")
        svc.offerOpponentProfileNavigationTap(ticket) { submitted ->
            acknowledge(ticket, submitted)
        }
    }

    @Synchronized private fun acknowledge(ticket: OpponentProfileTapTicket, accepted: Boolean) {
        if (ticket != pendingTicket || ticket.sequence != sequence) return
        pendingTicket = null
        if (!accepted) {
            status(OpponentProfileSweepPhase.BLOCKED,
                "Android отклонил навигационное касание; автоматического повтора нет")
            return
        }
        if (ticket.purpose == OpponentProfileNavPurpose.OPEN_AVATAR)
            status(OpponentProfileSweepPhase.WAITING_POPUP,
                "Аватар нажат, ждём подтверждения открытия окна")
        else status(OpponentProfileSweepPhase.WAITING_TABLE_RETURN,
                "Область стола нажата, ждём подтверждения закрытия панели")
    }
}
