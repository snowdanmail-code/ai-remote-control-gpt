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
    private val tableMemory = T046TableOpponentMemory()

    private fun currentSession(): T046TableSession =
        T046TableSession(packageName, captureEpoch, windowGeneration)

    @Synchronized fun localSeatMemory(): List<T046OpponentSeatMemory> =
        tableMemory.snapshot(currentSession())

    /**
     * Authoritative action sources may update the current hand. This is
     * deliberately NOT invoked by unverified seat-label OCR.
     */
    @Synchronized fun acceptVerifiedParticipation(
        handId: String, eventId: String, seat: Int,
        participation: T046HandParticipation, evidence: T046Evidence
    ): Boolean =
        tableMemory.applyVerifiedEvent(currentSession(),
            handId, eventId, seat, participation, evidence)

    @Synchronized fun verifiedHandSeatSummary(
        observation: AutonomousObservation
    ): String {
        if (!observation.tableHandIndependentlyVerified ||
            !observation.windowIndependentlyVerified ||
            observation.view.captureEpoch != captureEpoch ||
            observation.windowGeneration != windowGeneration ||
            current.phase != OneWinPostSeatStudyPhase.COMPLETE) return ""
        val key = currentSession()
        tableMemory.beginVerifiedHand(key, observation.handId, true)
        return tableMemory.summaryForChatGpt(key, observation.handId)
    }
    private var autoTransferAttempted = false
    private var cloudSending = false
    private var cloudStatus = "ChatGPT: данные текущего стола ещё не отправлялись"

    @Synchronized fun transferStatus(): String = cloudStatus

    /**
     * A one-time OAuth plan-allowance handoff after finishing the profile
     * sweep. Manual requests use this same path. Each turn still sends
     * current stats again because direct Responses requests use store=false.
     */
    @Synchronized fun transferToChatGpt(context: Context): Boolean {
        if (current.phase != OneWinPostSeatStudyPhase.COMPLETE ||
            cloudSending || ChatGptCloudBridge.isBusy() ||
            !ChatGptCloudBridge.planUsageAuthorized(context)) return false
        val origin = FrameStore.windowOrigin ?: return false
        if (!origin.safeForLocalDiagnostics ||
            origin.observedPackage != packageName ||
            origin.captureEpoch != captureEpoch ||
            origin.windowGeneration != windowGeneration ||
            !OnDeviceOneWinPreSeatScanner.verifiedSeatedIn(origin)) return false
        val stats = T045SessionStatsFormatter.formatForHandoff(sessionProfiles)
        if (ChatGptPlanHandoffProtocol.prepare(
                stats, ChatGptAuthProtocol.randomUrlSafe(16)) == null) {
            cloudStatus = "ChatGPT: нет достоверно прочитанных числовых метрик"
            return false
        }
        val expectedPackage = packageName
        val expectedEpoch = captureEpoch
        val expectedWindow = windowGeneration
        autoTransferAttempted = true
        cloudSending = true
        cloudStatus = "ChatGPT: передаю данные соперников через разрешённый лимит подписки"
        ChatGptCloudBridge.sendSessionContext(context.applicationContext, stats) { result ->
            synchronized(this) {
                if (packageName == expectedPackage &&
                    captureEpoch == expectedEpoch &&
                    windowGeneration == expectedWindow) {
                    cloudSending = false
                    cloudStatus = result
                }
            }
        }
        return true
    }

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
        val origin = FrameStore.windowOrigin ?: return ""
        if (current.phase != OneWinPostSeatStudyPhase.COMPLETE ||
            origin.observedPackage != packageName ||
            origin.captureEpoch != captureEpoch ||
            origin.windowGeneration != windowGeneration ||
            observation.view.captureEpoch != captureEpoch ||
            observation.windowGeneration != windowGeneration ||
            !observation.tableHandIndependentlyVerified ||
            !observation.windowIndependentlyVerified) return ""
        val key = currentSession()
        tableMemory.beginVerifiedHand(key, observation.handId, true)
        val safeSeats = tableMemory.snapshot(key)
            .filter { !it.ambiguousIdentity &&
                it.participation != T046HandParticipation.LEFT_TABLE_CONFIRMED }
            .map { it.seat }.toSet()
        return T045SessionStatsFormatter.format(
            sessionProfiles.filter { it.seatIndex in safeSeats })
    }

    @Synchronized fun reset(reason: String = "Сеанс завершён") {
        packageName = ""
        captureEpoch = -1L
        windowGeneration = -1L
        lastTryAtMs = -3_000L
        started = false
        sessionProfiles = emptyList()
        tableMemory.reset()
        autoTransferAttempted = false
        cloudSending = false
        cloudStatus = "ChatGPT: данные сеанса не отправлялись"
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
        // Seat aliases belong to this table/window only. No nickname-based
        // identity is carried across capture sessions or new tables.
        tableMemory.beginSession(currentSession(), hero)
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
                sessionProfiles.forEach {
                    tableMemory.observePopup(currentSession(), it)
                }
                current = OneWinPostSeatStudyStatus(
                    OneWinPostSeatStudyPhase.COMPLETE,
                    "Обход завершён: " + sessionProfiles.size +
                        " профилей, без HERO. Результаты доступны " +
                        "для анализа текущей сессии, не являются " +
                        "доказанной постоянной личностью.",
                    sessionProfiles.size, hero)
                // Send exactly once; if the subscription allowance has not
                // been granted, keep the data locally until operator requests
                // a manual transfer. Never scrape ChatGPT or use an API key.
                if (!autoTransferAttempted &&
                    ChatGptCloudBridge.planUsageAuthorized(context) &&
                    !ChatGptCloudBridge.isBusy()) {
                    transferToChatGpt(context)
                }
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

/** Deterministic, nickname-free context formatted without truncating metric tokens. */
object T045SessionStatsFormatter {
    /** A compact subset sent on EVERY authenticated verified turn. */
    fun format(profiles: List<ObservedOpponentProfile>): String =
        limited(profiles, 280, 4)

    /** Full available numeric popup observations for the one-time OAuth ACK. */
    fun formatForHandoff(profiles: List<ObservedOpponentProfile>): String =
        limited(profiles, 900, 11)

    private fun limited(profiles: List<ObservedOpponentProfile>,
                        maxChars: Int, maxMetricsPerSeat: Int): String {
        val rows = mutableListOf<String>()
        var used = 0
        for (profile in profiles.sortedBy { it.seatIndex }.take(5)) {
            if (profile.seatIndex !in 0..9) continue
            val stats = profile.metrics
                .filter { item ->
                    item.evidence == "POPUP_OCR_ONLY" &&
                        item.amount.isFinite() && item.amount >= 0.0 &&
                        item.amount <=
                            (if (item.metric.percent) 100.0 else 100_000.0)
                }.distinctBy { it.metric }.take(maxMetricsPerSeat)
            var row = "seat_" + profile.seatIndex + ":"
            var count = 0
            for (item in stats) {
                val text = if (item.amount % 1.0 == 0.0)
                    item.amount.toLong().toString()
                else {
                    val tenths = (item.amount * 10.0).toLong()
                    (tenths / 10L).toString() + "." + (tenths % 10L).toString()
                }
                val part = item.metric.name + "=" + text
                val candidate = row + (if (count == 0) "" else ",") + part
                val separator = if (rows.isEmpty()) 0 else 1
                if (used + separator + candidate.length > maxChars) break
                row = candidate
                count++
            }
            if (count > 0) {
                used += (if (rows.isEmpty()) 0 else 1) + row.length
                rows.add(row)
            }
        }
        return rows.joinToString(";")
    }
}
