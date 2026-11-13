package com.example.airemote

import android.content.Context

/** Stage T045: profiles before verified GPT turn advice. */
enum class OneWinPostSeatStudyPhase {
    OFF, WAITING_CALIBRATION, WAITING_SAFE_TABLE, SCANNING,
    COMPLETE, BLOCKED
}
data class OneWinPostSeatStudyStatus(
    val phase: OneWinPostSeatStudyPhase,
    val description: String,
    val observedProfiles: Int = 0,
    val identifiedHeroSeat: Int? = null
)

/**
 * Entry from verified SEATED only. Starts the EXISTING avatar->popup
 * observer once, with operator-armed table identity. It cannot tap by itself
 * and cannot grant a GPT decision or start gameplay. Popup statistics are
 * session-scoped; durable opponent identity still requires a verified
 * hand completion and a stable identity proof.
 */
object OnDeviceOneWinPostSeatStudy {
    private var packageName = ""
    private var captureEpoch = -1L
    private var windowGeneration = -1L
    private var lastTryAtMs = -3_000L
    private var started = false
    private var current = OneWinPostSeatStudyStatus(
        OneWinPostSeatStudyPhase.OFF, "Изучение соперников не запускалось")
    private var sessionProfiles: List<ObservedOpponentProfile> = emptyList()

    @Synchronized fun status(): OneWinPostSeatStudyStatus = current

    /** Display-only session memory; NOT a stable cross-table identity. */
    @Synchronized fun observedThisSession(): List<ObservedOpponentProfile> =
        sessionProfiles.toList()

    /**
     * Small advisory context for ChatGPT, strictly this same captured table
     * and window. No nicknames or cross-table identity. An OCR popup can be
     * wrong, so ALL these metrics remain explicitly unverified.
     */
    @Synchronized fun advisoryFor(observation: AutonomousObservation): String {
        val origin = FrameStore.windowOrigin
        if (current.phase != OneWinPostSeatStudyPhase.COMPLETE ||
            origin?.observedPackage != packageName ||
            origin.captureEpoch != captureEpoch ||
            origin.windowGeneration != windowGeneration ||
            observation.view.captureEpoch != captureEpoch ||
            observation.windowGeneration != windowGeneration ||
            !observation.tableHandIndependentlyVerified ||
            !observation.windowIndependentlyVerified) return ""
        return sessionProfiles.sortedBy { it.seatIndex }.take(5).joinToString(";") { profile ->
            val stat = profile.metrics
                .filter { it.amount.isFinite() && it.amount >= 0.0 &&
                    it.amount <= if (it.metric.percent) 100.0 else 100_000.0 &&
                    it.evidence == "POPUP_OCR_ONLY" }
                .distinctBy { it.metric }
                .take(4)
                .joinToString(",") { metric ->
                    metric.metric.name + "=" +
                        if (metric.amount % 1.0 == 0.0)
                            metric.amount.toLong().toString()
                        else (metric.amount * 10.0).toLong().let {
                            (it / 10).toString() + "." + (it % 10).toString()
                        }
                }
            "seat_" + profile.seatIndex + ":" + stat
        }.take(280)
    }

    @Synchronized fun reset(reason: String = "Сеанс завершён") {
        packageName = ""
        captureEpoch = -1L
        windowGeneration = -1L
        lastTryAtMs = -3_000L
        started = false
        sessionProfiles = emptyList()
        current = OneWinPostSeatStudyStatus(OneWinPostSeatStudyPhase.OFF, reason)
    }

    @Synchronized fun observe(
        context: Context,
        origin: WindowOriginAssessment,
        surface: RecordedSurfaceStatus,
        nowMs: Long
    ) {
        if (!OnDeviceOneWinPreSeatScanner.verifiedSeatedIn(origin)) {
            // A changed package/epoch/window is not a continuation of
            // old table profiles, even if the screen looks very similar.
            if (started && (origin.captureEpoch != captureEpoch ||
                    origin.windowGeneration != windowGeneration ||
                    origin.observedPackage != packageName))
                reset("Изменились источник или окно; старые профили исключены")
            return
        }
        if (!FrameStore.isCapturing || OperatorStopStore.isStopped(context) ||
            CaptureObservationMode.isSpectator() ||
            !LiveGamePermissionGate.permitted() ||
            LiveGamePermissionGate.packageName() != origin.observedPackage) {
            reset("STOP / нет разрешения или захвата")
            return
        }
        val win = origin.windowGeneration ?: return
        if (packageName != origin.observedPackage ||
            captureEpoch != origin.captureEpoch || windowGeneration != win) {
            // If this object existed from another table, forget its memory
            // first. Reading a second table requires a new verified SEATED.
            reset("Новая подтверждённая посадка")
            packageName = requireNotNull(origin.observedPackage)
            captureEpoch = origin.captureEpoch
            windowGeneration = win
            current = OneWinPostSeatStudyStatus(
                OneWinPostSeatStudyPhase.WAITING_CALIBRATION,
                "Проверяем расположение аватаров и нижнюю позицию HERO")
        }
        if (current.phase in setOf(OneWinPostSeatStudyPhase.COMPLETE,
                OneWinPostSeatStudyPhase.BLOCKED)) return
        val profile = OnDeviceRealFrameRegions.activeProfile()
        val hero = OneWinPortraitHeroSeat.inferBottomSeat(profile)
        if (hero == null || profile == null || !profile.valid() ||
            profile.expectedPackage != packageName ||
            profile.frameWidth !in 320..5000 ||
            profile.zones.none { it.role == RealFieldRole.PROFILE_DISMISS } ||
            profile.zones.none {
                it.role == RealFieldRole.SEAT_AVATAR && it.seatIndex != hero
            }) {
            current = OneWinPostSeatStudyStatus(
                OneWinPostSeatStudyPhase.WAITING_CALIBRATION,
                "Нет калибровки аватаров/области закрытия; " +
                    "не угадываем координаты и не открываем окна")
            return
        }
        if (!started) {
            if (surface != RecordedSurfaceStatus.TABLE_LIKE ||
                nowMs - lastTryAtMs < 3_000L) {
                current = OneWinPostSeatStudyStatus(
                    OneWinPostSeatStudyPhase.WAITING_SAFE_TABLE,
                    "Ждём безопасного состояния стола для обхода соперников",
                    identifiedHeroSeat = hero)
                return
            }
            lastTryAtMs = nowMs
            val launched = OnDeviceOpponentProfileSweep.start(context)
            if (!launched) {
                current = OneWinPostSeatStudyStatus(
                    OneWinPostSeatStudyPhase.WAITING_SAFE_TABLE,
                    "Обход не активирован: ожидаем отсутствие " +
                        "конфликтующих действий и доступность калибровки",
                    identifiedHeroSeat = hero)
                return
            }
            started = true
        }
        val sweep = OnDeviceOpponentProfileSweep.status()
        when (sweep.phase) {
            OpponentProfileSweepPhase.COMPLETE -> {
                sessionProfiles = sweep.results
                    .filter { it.seatIndex != hero }
                    .distinctBy { it.seatIndex }
                current = OneWinPostSeatStudyStatus(
                    OneWinPostSeatStudyPhase.COMPLETE,
                    "Обход завершён: " + sessionProfiles.size +
                        " профилей, без HERO. Результаты доступны " +
                        "для анализа текущей сессии, не являются " +
                        "доказанной постоянной личностью.",
                    sessionProfiles.size, hero)
            }
            OpponentProfileSweepPhase.BLOCKED -> {
                current = OneWinPostSeatStudyStatus(
                    OneWinPostSeatStudyPhase.BLOCKED,
                    "Обход заблокирован: " + sweep.description +
                        "; без повторных жестов",
                    sweep.results.size, hero)
            }
            else -> {
                current = OneWinPostSeatStudyStatus(
                    OneWinPostSeatStudyPhase.SCANNING,
                    "Соперники: " + sweep.description,
                    sweep.results.size, hero)
            }
        }
    }
}
